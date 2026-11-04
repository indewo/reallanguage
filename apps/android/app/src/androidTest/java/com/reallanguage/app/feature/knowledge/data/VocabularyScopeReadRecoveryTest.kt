package com.reallanguage.app.feature.knowledge.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.presentation.VocabularyBookAction
import com.reallanguage.app.feature.knowledge.presentation.VocabularyBookViewModel
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Real HttpCurrentVocabularyScopeSource -> SQLite repository -> production VM.
 * Only the HTTP transport/runtime identities are isolated synthetic fixtures;
 * the UUID cache database contains no user data. No real HTTP, AI or audio.
 */
class VocabularyScopeReadRecoveryTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private lateinit var privateFile: File
    private val owner = "scope-recovery-owner-${UUID.randomUUID()}"

    @Before fun createOnlyPrivateDatabase() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        context = IsolatedLearningDatabaseContext(base, LABEL)
        privateFile = context.getDatabasePath(DATABASE_NAME).canonicalFile
        assertPrivateScope()
        assertFalse("The UUID fixture cannot reuse a database", privateFile.exists())
        database = FormationDatabase(context)
        assertEquals(privateFile, File(database.writableDatabase.path).canonicalFile)
        seedSense(owner, "run-business", "run", "经营", "CET_4", "VERIFIED", 2)
        seedSense(owner, "allocate-other-scope", "allocate", "分配", "CET_6", "LEARNING", 1)
        seedSense("other-$owner", "foreign-sense", "calm", "平静", "CET_4", "VERIFIED", 3)
        repeat(3) { index ->
            insert("vocabulary_examples", "account_id" to owner, "item_id" to "run-business", "example_order" to index,
                "text_en" to "Stored neutral example $index.", "text_zh" to "隔离保存例句 $index。", "audio_id" to "Stored neutral example $index.")
            insert("vocabulary_occurrences", "account_id" to owner, "item_id" to "run-business", "chapter_id" to "scope-fixture-chapter-$index",
                "sentence_id" to "scope-fixture-sentence-$index", "is_focus" to if (index == 0) 1 else 0)
        }
    }

    @After fun deleteOnlyValidatedPrivateDatabase() {
        if (::database.isInitialized) database.close()
        if (::privateFile.isInitialized) {
            assertPrivateScope()
            assertTrue("Only this test's UUID SQLite file should be removed", context.deleteDatabase(DATABASE_NAME))
            assertFalse(privateFile.exists())
        }
    }

    @Test
    fun invalidRemoteScopeIsExplicitFailureAndUserRetryRestoresStoredSenseWithoutChangingAnyTable() = runBlocking {
        val before = allPrivateTables()
        val runtime = SessionRuntime().apply { update("isolated-scope-recovery-token", owner) }
        val epoch = runtime.accountEpoch
        val responses = AtomicReference(scopeBody("NOT_A_SCOPE"))
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val transport = HttpTransport { request ->
            requests += request
            check(requests.size <= 2) { "Only the initial scope read and a user retry are permitted" }
            HttpResponse(200, responses.get())
        }
        val source = HttpCurrentVocabularyScopeSource(transport, runtime)
        val repository = AndroidVocabularyBookRepository(context, runtime, source)
        val store = ViewModelStore()
        try {
            val model = withContext(Dispatchers.Main) {
                VocabularyBookViewModel(repository).also { store.put("isolated-scope-recovery", it) }
            }
            val invalid = withTimeout(5_000) { model.uiState.first { !it.loading } }
            assertEquals(1, requests.size)
            assertEquals("An invalid scope read cannot mutate any private table", before, allPrivateTables())
            assertTrue("HTTP 200 with NOT_A_SCOPE is a failed read, not successful empty knowledge", invalid.loadFailed)
            assertTrue(invalid.entries.isEmpty())
            assertEquals("An invalid remote enum cannot become the visible current scope", "", invalid.currentScope)

            responses.set(scopeBody("CET_4"))
            withContext(Dispatchers.Main) { model.onAction(VocabularyBookAction.RetryRequested) }
            val recovered = withTimeout(5_000) { model.uiState.first { !it.loading } }
            assertFalse(recovered.loadFailed)
            assertEquals("CET_4", recovered.currentScope)
            val entry = recovered.entries.single()
            assertEquals("run", entry.englishForm)
            val sense = entry.senses.single()
            assertEquals("run-business", sense.senseId)
            assertEquals("经营", sense.meaningZh)
            assertEquals(setOf("CET_4"), sense.learningScopes)
            assertEquals(KnowledgeStatus.MASTERED, sense.status)
            assertEquals(2, sense.effectiveLearnedCount)
            assertEquals(1, sense.focusOccurrenceCount)
            assertEquals(2, sense.contextOccurrenceCount)
            assertEquals("/rʌn/", sense.phonetic)
            assertEquals("run", sense.pronunciationText)
            assertEquals((0..2).map { "Stored neutral example $it." }, sense.examples.map { it.textEn })
            assertNull(recovered.expandedSenseId)
            assertFalse(recovered.pronunciationBusy)
            assertFalse(recovered.pronunciationFailed)
            assertEquals(2, requests.size)
            requests.forEach { request ->
                assertEquals("GET", request.method)
                assertEquals("/api/v1/settings/current", request.path)
                assertTrue(request.body.isEmpty())
                assertEquals("Bearer isolated-scope-recovery-token", request.headers["Authorization"])
            }
            assertEquals(owner, runtime.accountId)
            assertEquals(epoch, runtime.accountEpoch)
            assertEquals("Successful scope filtering preserves other scopes/accounts and every stored status/count", before, allPrivateTables())
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
        }
    }

    private fun scopeBody(basic: String) = JSONObject()
        .put("basicVocabulary", basic).put("purposeVocabularies", JSONArray()).toString()

    private fun assertPrivateScope() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(base.cacheDir.canonicalFile, privateFile.parentFile)
        assertNotEquals(base.getDatabasePath(DATABASE_NAME).canonicalFile, privateFile)
        val uuid = privateFile.name.removePrefix("$LABEL-").removeSuffix(".db")
        assertEquals("$LABEL-${UUID.fromString(uuid)}.db", privateFile.name)
        assertEquals(privateFile, context.getDatabasePath(DATABASE_NAME).canonicalFile)
    }

    private fun seedSense(account: String, id: String, display: String, meaning: String, scope: String, status: String, count: Int) {
        insert("vocabulary_items", "account_id" to account, "item_id" to id, "normalized_form" to display,
            "semantic_sense_key" to "$display|$meaning", "display" to display, "meaning_zh" to meaning,
            "status" to status, "verified_success_count" to count, "phonetic" to if (display == "run") "/rʌn/" else "",
            "pronunciation_audio_id" to display)
        insert("vocabulary_wordlists", "account_id" to account, "item_id" to id, "wordlist" to scope)
    }

    private fun insert(table: String, vararg fields: Pair<String, Any>) {
        check(table in setOf("vocabulary_items", "vocabulary_wordlists", "vocabulary_examples", "vocabulary_occurrences"))
        database.writableDatabase.insertOrThrow(table, null, ContentValues().apply {
            fields.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, value)
                    is Int -> put(key, value)
                    else -> error("Unsupported isolated fixture value")
                }
            }
        })
    }

    /** Include all product/internal tables, field types and columns in this UUID file. */
    private fun allPrivateTables(): Map<String, TableSnapshot> {
        assertPrivateScope()
        val db = database.readableDatabase
        assertEquals(privateFile, File(db.path).canonicalFile)
        val tables = db.rawQuery("select name from sqlite_master where type='table' order by name", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return tables.associateWith { table ->
            check(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            db.rawQuery("select * from \"$table\"", null).use { cursor ->
                val columns = cursor.columnNames.toList()
                val rows = buildList {
                    while (cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("") { index ->
                        val value = when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> "null"
                            Cursor.FIELD_TYPE_INTEGER -> "integer:${cursor.getLong(index)}"
                            Cursor.FIELD_TYPE_FLOAT -> "float:${cursor.getDouble(index)}"
                            Cursor.FIELD_TYPE_STRING -> "string:${cursor.getString(index)}"
                            Cursor.FIELD_TYPE_BLOB -> "blob:${cursor.getBlob(index).joinToString(",") { it.toString() }}"
                            else -> error("Unsupported isolated SQLite field type")
                        }
                        "${value.length}:$value"
                    })
                }
                TableSnapshot(columns, rows.sorted())
            }
        }
    }

    private data class TableSnapshot(val columns: List<String>, val rows: List<String>)

    private companion object {
        const val LABEL = "vocabulary-scope-read-recovery"
        const val DATABASE_NAME = "real-language-learning.db"
    }
}
