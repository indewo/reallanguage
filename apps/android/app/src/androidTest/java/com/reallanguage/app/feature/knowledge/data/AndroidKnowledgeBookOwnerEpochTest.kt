package com.reallanguage.app.feature.knowledge.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.PatternLoadResult
import com.reallanguage.app.feature.knowledge.domain.VocabularyLoadResult
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real SQLite readers with synthetic knowledge/session fixtures; no credentials or HTTP ports. */
class AndroidKnowledgeBookOwnerEpochTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private lateinit var runtime: SessionRuntime
    private val owner = "knowledge-epoch-owner-${UUID.randomUUID()}"

    @Before fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        context = IsolatedLearningDatabaseContext(base, "knowledge-book-owner-epoch")
        val privateFile = context.getDatabasePath(DATABASE_NAME).canonicalFile
        assertEquals(base.cacheDir.canonicalFile, privateFile.parentFile)
        assertNotEquals(base.getDatabasePath(DATABASE_NAME).canonicalFile, privateFile)
        val suffix = privateFile.name.removePrefix("knowledge-book-owner-epoch-").removeSuffix(".db")
        assertEquals("knowledge-book-owner-epoch-${UUID.fromString(suffix)}.db", privateFile.name)
        assertFalse("The UUID fixture must not reuse an existing database", privateFile.exists())
        database = FormationDatabase(context)
        assertEquals(privateFile, File(database.writableDatabase.path).canonicalFile)
        runtime = SessionRuntime().apply { update("synthetic-first-token", owner) }
        seedAcceptedKnowledge()
    }

    @After fun cleanOnlyPrivateDatabase() {
        if (::database.isInitialized) database.close()
        if (::context.isInitialized) context.deleteDatabase(DATABASE_NAME)
    }

    @Test fun sameAccountReloginInvalidatesOldVocabularyReaderBeforeAnyScopeRead() = runBlocking {
        val before = allPrivateTables()
        val oldScopeCalls = AtomicInteger()
        val old = AndroidVocabularyBookRepository(context, runtime) {
            oldScopeCalls.incrementAndGet()
            setOf("CET_4")
        }
        val initial = old.loadAllRecords()
        assertTrue("The fixture must be readable before the epoch boundary", initial is VocabularyLoadResult.Ready)
        val record = (initial as VocabularyLoadResult.Ready).records.single()
        assertEquals("sense-run", record.senseId)
        assertEquals("经营", record.meaningZh)
        assertEquals(KnowledgeStatus.MASTERED, record.status)
        assertEquals(2, record.effectiveLearnedCount)
        assertEquals(listOf("Vocabulary example 0", "Vocabulary example 1", "Vocabulary example 2"), record.examples.map { it.textEn })
        assertEquals(1, oldScopeCalls.get())

        val originalEpoch = runtime.accountEpoch
        runtime.update("synthetic-refresh-token", owner)
        assertEquals(originalEpoch, runtime.accountEpoch)
        assertEquals("Ordinary token refresh must not invalidate an active reader", initial, old.loadAllRecords())

        runtime.clear()
        runtime.update("synthetic-relogin-token", owner)
        assertEquals(owner, runtime.accountId)
        assertTrue(runtime.accountEpoch > originalEpoch)
        val freshScopeCalls = AtomicInteger()
        val fresh = AndroidVocabularyBookRepository(context, runtime) {
            freshScopeCalls.incrementAndGet()
            setOf("CET_4")
        }
        assertEquals("A newly entered reader must retain access to the unchanged stored knowledge", initial, fresh.loadAllRecords())
        assertEquals(1, freshScopeCalls.get())

        oldScopeCalls.set(0)
        val stale = old.loadAllRecords()
        assertEquals("Read/relogin must not mutate any private product table", before, allPrivateTables())
        assertTrue(
            "An exited reader must fail before scope lookup; result=${stale::class.simpleName}, scopeCalls=${oldScopeCalls.get()}",
            stale == VocabularyLoadResult.Failed && oldScopeCalls.get() == 0,
        )
    }

    @Test fun sameAccountReloginInvalidatesOldPatternReaderWhileNewReaderKeepsStoredData() = runBlocking {
        val before = allPrivateTables()
        val old = AndroidPatternBookRepository(context, runtime)
        val initial = old.loadAllPatterns()
        assertTrue("The fixture must be readable before the epoch boundary", initial is PatternLoadResult.Ready)
        val record = (initial as PatternLoadResult.Ready).patterns.single()
        assertEquals("pattern-used-to", record.patternId)
        assertEquals("过去的习惯", record.functionZh)
        assertEquals(KnowledgeStatus.MASTERED, record.writingStatus)
        assertEquals(KnowledgeStatus.LEARNING, record.speakingStatus)
        assertEquals(3, record.writingConsecutiveSuccessCount)
        assertEquals(1, record.speakingConsecutiveSuccessCount)
        assertEquals(listOf("Pattern example 0", "Pattern example 1", "Pattern example 2"), record.examples.map { it.textEn })

        val originalEpoch = runtime.accountEpoch
        runtime.update("synthetic-refresh-token", owner)
        assertEquals(originalEpoch, runtime.accountEpoch)
        assertEquals("Ordinary token refresh must not invalidate an active reader", initial, old.loadAllPatterns())

        runtime.clear()
        runtime.update("synthetic-relogin-token", owner)
        assertEquals(owner, runtime.accountId)
        assertTrue(runtime.accountEpoch > originalEpoch)
        val fresh = AndroidPatternBookRepository(context, runtime)
        assertEquals("A new reader must retain independent abilities and the original examples", initial, fresh.loadAllPatterns())

        val stale = old.loadAllPatterns()
        assertEquals("Read/relogin must not mutate any private product table", before, allPrivateTables())
        assertEquals("An exited reader must not regain authority after the same account logs in", PatternLoadResult.Failed, stale)
    }

    private fun seedAcceptedKnowledge() {
        insert("vocabulary_items", "account_id" to owner, "item_id" to "sense-run",
            "normalized_form" to "run", "semantic_sense_key" to "经营", "display" to "run",
            "meaning_zh" to "经营", "status" to "VERIFIED", "verified_success_count" to 2,
            "phonetic" to "/rʌn/", "pronunciation_audio_id" to "run", "representative_scene" to "隔离夹具：经营咖啡店")
        insert("vocabulary_wordlists", "account_id" to owner, "item_id" to "sense-run", "wordlist" to "CET_4")
        insert("pattern_items", "account_id" to owner, "item_id" to "pattern-used-to",
            "semantic_pattern_key" to "used to|过去的习惯", "structure_en" to "used to", "meaning_zh" to "过去的习惯",
            "writing_status" to "VERIFIED", "speaking_status" to "LEARNING", "writing_streak" to 3,
            "speaking_streak" to 1, "active_validation" to 1, "representative_scene" to "隔离夹具：回忆过去的习惯",
            "natural_revalidation" to 1)
        repeat(3) { index ->
            insert("vocabulary_examples", "account_id" to owner, "item_id" to "sense-run", "example_order" to index,
                "text_en" to "Vocabulary example $index", "text_zh" to "隔离词汇例句 $index", "audio_id" to "Vocabulary example $index")
            insert("pattern_examples", "account_id" to owner, "item_id" to "pattern-used-to", "example_order" to index,
                "text_en" to "Pattern example $index", "text_zh" to "隔离句式例句 $index", "pronunciation_text" to "Pattern example $index")
        }
    }

    private fun insert(table: String, vararg fields: Pair<String, Any>) {
        database.writableDatabase.insertOrThrow(table, null, ContentValues().apply {
            fields.forEach { (name, value) ->
                when (value) {
                    is String -> put(name, value)
                    is Int -> put(name, value)
                    else -> error("Unsupported synthetic fixture type")
                }
            }
        })
    }

    /** Snapshot every table in this UUID file, not just the queried knowledge rows. */
    private fun allPrivateTables(): Map<String, TableSnapshot> {
        val db = database.readableDatabase
        val tables = db.rawQuery("select name from sqlite_master where type='table' order by name", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return tables.associateWith { table ->
            check(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            db.rawQuery("select * from \"$table\"", null).use { cursor ->
                val columns = cursor.columnNames.toList()
                val rows = buildList {
                    while (cursor.moveToNext()) {
                        add((0 until cursor.columnCount).joinToString("") { index ->
                            val value = when (cursor.getType(index)) {
                                Cursor.FIELD_TYPE_NULL -> "null"
                                Cursor.FIELD_TYPE_INTEGER -> "integer:${cursor.getLong(index)}"
                                Cursor.FIELD_TYPE_FLOAT -> "float:${cursor.getDouble(index)}"
                                Cursor.FIELD_TYPE_STRING -> "string:${cursor.getString(index)}"
                                Cursor.FIELD_TYPE_BLOB -> "blob:${cursor.getBlob(index).joinToString(",") { it.toString() }}"
                                else -> error("Unsupported SQLite field type")
                            }
                            "${value.length}:$value"
                        })
                    }
                }
                TableSnapshot(columns, rows.sorted())
            }
        }
    }

    private data class TableSnapshot(val columns: List<String>, val rows: List<String>)

    private companion object {
        const val DATABASE_NAME = "real-language-learning.db"
    }
}
