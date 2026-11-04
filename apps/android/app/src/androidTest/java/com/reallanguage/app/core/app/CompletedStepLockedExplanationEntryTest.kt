package com.reallanguage.app.core.app

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.formation.data.toJson
import com.reallanguage.app.feature.formation.domain.SourceEvidence
import com.reallanguage.app.feature.learning.data.AndroidLearningProgressStore
import com.reallanguage.app.feature.learning.domain.CompletedStepSnapshot
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.SelectableExpression
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Production Graph + saved LR-02 SQLite progress. No network, credentials, or main database. */
class CompletedStepLockedExplanationEntryTest {
    @Test fun completedStepRejectsSavedMeaningThatContradictsTheCancelledWordsFixedGlossary() = fixture {
        saveExplanation("茶树")
        val before = savedRows()
        val load = requireNotNull(graph.completedStepSnapshotLoader(snapshot()))
        repeat(2) {
            assertTrue("Read-only retry must reject the conflicting saved meaning", runCatching { load() }.isFailure)
            assertEquals(before, savedRows())
            assertNoExternalWork()
        }
    }

    @Test fun completedStepReadsExactSavedMeaningAndDoesNotMoveTheCurrentTaskOrCreateVocabulary() = fixture {
        saveExplanation(meaning)
        val before = savedRows()
        val loaded = requireNotNull(graph.completedStepSnapshotLoader(snapshot()))()
        val reviewed = requireNotNull(loaded.reviewedExpression)
        assertEquals(expression(), reviewed.expression)
        assertEquals(meaning, reviewed.explanation.contextualMeaningZh)
        assertEquals("已保存的用法说明。", reviewed.explanation.usageZh)
        assertEquals("先前交付的完整讲解。", reviewed.explanation.explanationZh)
        assertEquals(before, savedRows())
        assertEquals(0, count("vocabulary_items"))
        assertEquals(1, count("target_selections")) // Only the selected PATTERN, not its cancelled WORD.
        assertNoExternalWork()
    }

    @Test fun genuineLegacyUngroupedExplanationStaysReadableWithoutInventingConfirmedMeaning() = fixture {
        saveExplanation(null)
        val before = savedRows()
        val loaded = requireNotNull(graph.completedStepSnapshotLoader(snapshot()))()
        val explanation = requireNotNull(loaded.reviewedExpression).explanation
        assertEquals("先前交付的完整讲解。", explanation.explanationZh)
        assertNull(explanation.contextualMeaningZh)
        assertNull(explanation.usageZh)
        assertNull(explanation.expressionHintZh)
        assertEquals(before, savedRows())
        assertNoExternalWork()
    }

    @Test fun routeEnglishCannotDisagreeWithTheFixedSentenceEvenWhenTheSavedWordStillMatches() = fixture {
        saveExplanation(meaning)
        val before = savedRows()
        val changed = snapshot().copy(contentEn = english + " Changed route content.")
        val load = requireNotNull(graph.completedStepSnapshotLoader(changed))
        assertTrue("Matching expression text must not authorize a different fixed sentence", runCatching { load() }.isFailure)
        assertEquals(before, savedRows())
        assertNoExternalWork()
    }

    @Test fun legacyWordIdCannotBeReboundToTheNextUnicodeWordInACompletedRoute() = fixture("I’m used to drink tea.") {
        // The old tokenizer assigned I=0, m=1, used=2, to=3, drink=4, tea=5.
        // Reindexing Unicode words would silently make old word-3 mean drink.
        saveExplanation(null, expressionId = "word-3")
        val drinkStart = english.indexOf("drink")
        val forged = SelectableExpression("word-3", "drink", drinkStart, drinkStart + "drink".length)
        val before = savedRows()
        val load = requireNotNull(graph.completedStepSnapshotLoader(snapshot().copy(expressions = listOf(forged))))
        assertTrue("A valid substring is insufficient: the legacy occurrence ID must still identify to", runCatching { load() }.isFailure)
        assertEquals(before, savedRows())
        assertNoExternalWork()
    }

    private fun fixture(english: String = "I used to drink tea.", block: suspend Fixture.() -> Unit) = runBlocking {
        Fixture(english).use { it.block() }
    }

    private class Fixture(val english: String) : AutoCloseable {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val runId = UUID.randomUUID().toString()
        private val isolated = IsolatedLearningDatabaseContext(base, "completed-locked-$runId")
        private val directory = File(base.cacheDir, "completed-locked-$runId")
        private val preferenceNames = mutableSetOf<String>()
        private val context = object : ContextWrapper(isolated) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = "completed-locked-$runId-$name"
                preferenceNames.add(isolatedName)
                return base.getSharedPreferences(isolatedName, mode)
            }
        }
        private val account = "completed-owner-$runId"
        private val runtime = SessionRuntime().apply { update("isolated-session", account) }
        private val requests = AtomicInteger()
        private val credentials = AtomicInteger()
        private val helper = FormationDatabase(context)
        private val db get() = helper.writableDatabase
        val meaning = "茶（饮品）"
        val graph by lazy {
            ProductionAppGraph(context, runtime, HttpTransport {
                requests.incrementAndGet()
                error("Completed-step review must not call HTTP or AI")
            }, object : SessionCredentialStore {
                override suspend fun load(): StoredSession? { credentials.incrementAndGet(); error("No credential reads") }
                override suspend fun save(session: StoredSession) { credentials.incrementAndGet(); error("No credential writes") }
                override suspend fun clear() { credentials.incrementAndGet(); error("No credential clearing") }
            })
        }

        init {
            db.execSQL("insert into chapters values(?,'flow','chapter',0,'NOT_STARTED')", arrayOf(account))
            db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,'chapter','focus',0,'我过去喝茶。',?,'FOCUS')",
                arrayOf(account, english))
            val glossary = JSONObject().put("version", 1).put("sourceTextEn", english).put("unitId", "focus")
                .put("items", JSONArray().put(JSONObject().put("itemRef", "cancelled-tea").put("unitId", "focus")
                    .put("display", "tea").put("senseZh", meaning).put("unitType", "WORD")
                    .put("sourceEvidence", evidence("tea").toJson())))
                .put("refs", JSONArray(listOf("cancelled-tea")))
            db.insertOrThrow("target_selections", null, ContentValues().apply {
                put("account_id", account); put("chapter_id", "chapter"); put("unit_id", "focus"); put("target_id", "selected-pattern")
                put("type", "PATTERN"); put("display", "used to + verb"); put("explanation_zh", "过去习惯")
                put("structure_parts", "used to\u001fverb"); put("related_vocabulary", "tea")
                put("source_evidence", evidence("used to drink").toJson().toString())
                put("related_vocabulary_snapshot", glossary.toString())
            })
        }

        fun expression(): SelectableExpression {
            val match = Regex("[A-Za-z]+(?:'[A-Za-z]+)?").findAll(english).toList()
            val index = match.indexOfFirst { it.value == "tea" }
            val start = match[index].range.first
            return SelectableExpression("word-$index", "tea", start, start + 3)
        }

        fun snapshot() = CompletedStepSnapshot("LR-02", "讲解阅读", english, "我过去喝茶。", "已交付的句子讲解。",
            contextSentences = listOf(FixedLearningSentence("focus", english, "我过去喝茶。", true)),
            activeSentenceId = "focus", chapterId = "chapter", expressions = listOf(expression()))

        suspend fun saveExplanation(sense: String?, expressionId: String = expression().expressionId) {
            val values = buildMap {
                put("version", "1"); put("expressionId", expressionId); put("activeExpressionId", expressionId)
                put("expressionZh", "先前交付的完整讲解。")
                if (sense != null) {
                    put("expressionContextualMeaningZh", sense); put("expressionUsageZh", "已保存的用法说明。")
                    put("expressionHintZh", "")
                }
            }
            assertTrue(AndroidLearningProgressStore(context, runtime, "chapter", "focus", "LR-02").write(values))
            // User is already continuing LR-03; opening an old step must not move this pointer back.
            db.execSQL("update learning_checkpoints set module_id='LR-03',step_id='focus' where account_id=?", arrayOf(account))
        }

        private fun evidence(surface: String): SourceEvidence {
            val start = english.indexOf(surface)
            return SourceEvidence(1, start, start + surface.length, surface, surface, listOf(surface))
        }

        fun savedRows() = listOf("chapters", "fixed_sentences", "target_selections", "learning_module_progress",
            "learning_checkpoints", "learning_explanation_cache", "vocabulary_items", "pattern_items").associateWith { table ->
            db.rawQuery("select * from $table order by rowid", null).use { cursor -> buildList {
                while (cursor.moveToNext()) add(List(cursor.columnCount) { if (cursor.isNull(it)) null else cursor.getString(it) })
            } }
        }

        fun count(table: String) = db.rawQuery("select count(*) from $table", null).use { it.moveToFirst(); it.getInt(0) }
        fun assertNoExternalWork() {
            assertEquals("No HTTP or AI requests", 0, requests.get())
            assertEquals("No user credentials", 0, credentials.get())
        }
        override fun close() {
            helper.close()
            context.deleteDatabase("real-language-learning.db")
            preferenceNames.forEach { name -> check(name.startsWith("completed-locked-$runId-")); base.deleteSharedPreferences(name) }
            check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            check(directory.name == "completed-locked-$runId")
            check(!directory.exists() || directory.deleteRecursively())
        }
    }
}
