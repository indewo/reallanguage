package com.reallanguage.app.feature.learning.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.app.ProductionAppGraph
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.ChapterArchiveResult
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.FixedSceneResponseTask
import com.reallanguage.app.feature.learning.domain.SceneDialogueLine
import com.reallanguage.app.feature.learning.domain.SceneTaskGenerationResult
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual Graph/JSON/SQLite boundaries. HTTP in the first-generation control is a fixture, not AI. */
class SceneReviewSavedTaskBoundaryTest {
    @Test fun actualGraphSingleSentenceReviewReadsOnlyItsOwnTaskFromAMultiFocusChapter() = runBlocking {
        Fixture().use { f ->
            f.seedOrdinarySource()
            f.archiveAcceptedFixture()
            val before = f.rows()
            val focused = f.chapter.copy(sentences = f.chapter.sentences.map {
                it.copy(isFocus = it.sentenceId == f.secondId)
            })
            val result = f.graph().reviewSceneSession().generateTasks(focused)
            assertEquals("The production current-sentence projection must not lose a valid saved task",
                SceneTaskGenerationResult.Ready(listOf(f.tasks[1])), result)
            assertEquals(before, f.rows())
            f.assertNoExternalWork()
        }
    }

    @Test fun archivedReviewWithMissingTasksFailsWithoutCallingGeneration() = runBlocking {
        Fixture().use { f ->
            f.seedOrdinarySource()
            f.archiveAcceptedFixture()
            f.removeOnlyOwnTasks()
            val before = f.rows()
            // Full fixed facts isolate the missing-cache boundary from the projection regression.
            val result = f.graph().reviewSceneSession().generateTasks(f.chapter)
            assertEquals(SceneTaskGenerationResult.Failed, result)
            assertEquals(before, f.rows())
            f.assertNoExternalWork()
        }
    }

    @Test fun ordinaryUnarchivedFirstGenerationStillUsesTheRealCodecAndOnlyPersistsTasks() = runBlocking {
        Fixture().use { f ->
            f.seedOrdinarySource()
            val before = f.rows()
            val graph = f.graph(allowFixtureGeneration = true)
            val result = graph.sceneResponseSession().generateTasks(f.chapter)
            assertEquals(SceneTaskGenerationResult.Ready(f.tasks), result)
            assertEquals("One bounded fixture request for each of the two focus sentences", 2, f.requests.get())
            val after = f.rows()
            assertEquals("First generation must not change progress, results, archives or other tables",
                before - "scene_response_tasks", after - "scene_response_tasks")
            assertEquals(2, after.getValue("scene_response_tasks").size)
            val reopened = graph.sceneResponseSession().restoreTasks(f.chapter)
            assertEquals(result, reopened)
            assertEquals("Saved task recovery cannot generate again", 2, f.requests.get())
            assertEquals(after, f.rows())
            f.assertNoCredentialsOrAudio()
        }
    }

    @Test fun reviewFocusProjectionCannotChangeFixedFactsOrderOrPromoteAContextSentence() = runBlocking {
        Fixture().use { f ->
            f.seedOrdinarySource()
            f.archiveAcceptedFixture()
            val before = f.rows()
            val focused = f.chapter.copy(sentences = f.chapter.sentences.map {
                it.copy(isFocus = it.sentenceId == f.secondId)
            })
            val contextId = f.chapter.sentences.single { !it.isFocus }.sentenceId
            val invalid = linkedMapOf(
                "English changed" to focused.copy(sentences = focused.sentences.map {
                    if (it.sentenceId == f.secondId) it.copy(textEn = "I take a bus.") else it
                }),
                "Chinese changed" to focused.copy(sentences = focused.sentences.map {
                    if (it.sentenceId == f.secondId) it.copy(textZh = "我坐公交车。") else it
                }),
                "Sentence ID changed" to focused.copy(sentences = focused.sentences.map {
                    if (it.sentenceId == f.secondId) it.copy(sentenceId = "unrelated-${f.secondId}") else it
                }),
                "Fixed order changed" to focused.copy(sentences = focused.sentences.reversed()),
                "Context promoted to focus" to focused.copy(sentences = focused.sentences.map {
                    if (it.sentenceId == contextId) it.copy(isFocus = true) else it
                }),
            )
            val graph = f.graph()
            invalid.forEach { (reason, changed) ->
                assertEquals(reason, SceneTaskGenerationResult.Failed,
                    graph.reviewSceneSession().generateTasks(changed))
                assertEquals("Rejected projection must leave all tables unchanged: $reason", before, f.rows())
                f.assertNoExternalWork()
            }
        }
    }

    private class Fixture : AutoCloseable {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val runId = UUID.randomUUID().toString()
        private val account = "scene-review-boundary-$runId"
        private val chapterId = "chapter-$runId"
        private val firstId = "first-$runId"
        val secondId = "second-$runId"
        private val directory = File(base.cacheDir, "scene-review-boundary-$runId")
        private val preferenceNames = mutableSetOf<String>()
        private val isolatedDb = IsolatedLearningDatabaseContext(base, "scene-review-boundary-$runId")
        private val context = object : ContextWrapper(isolatedDb) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = File(directory, "private")
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = "scene-review-boundary-$runId-$name"
                synchronized(preferenceNames) { preferenceNames.add(isolatedName) }
                return base.getSharedPreferences(isolatedName, mode)
            }
        }
        private val runtime = SessionRuntime().apply { update("isolated-not-a-real-session", account) }
        val requests = AtomicInteger()
        private val credentials = AtomicInteger()
        val chapter = FixedLearningChapter(chapterId, 1, listOf(
            FixedLearningSentence(firstId, "I drink tea.", "我喝茶。", true),
            FixedLearningSentence("context-$runId", "Then I go outside.", "然后我去外面。", false),
            FixedLearningSentence(secondId, "I take a walk.", "我散步。", true),
        ))
        val tasks = listOf(
            FixedSceneResponseTask("task-first-$runId", firstId, "At breakfast.",
                listOf(SceneDialogueLine("AI", "What do you drink?")), "I drink tea."),
            FixedSceneResponseTask("task-second-$runId", secondId, "In the park.",
                listOf(SceneDialogueLine("AI", "What do you do next?")), "I take a walk."),
        )

        fun seedOrdinarySource() = FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("insert into formation_flows values(?,?,'FIXED','DIARY','隔离场景回顾边界')", arrayOf(account, "flow-$runId"))
            db.execSQL("insert into chapters values(?,?,?,0,'NOT_STARTED')", arrayOf(account, "flow-$runId", chapterId))
            chapter.sentences.forEachIndexed { index, sentence ->
                db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,?,?,?,?,?,?)",
                    arrayOf<Any>(account, chapterId, sentence.sentenceId, index, sentence.textZh, sentence.textEn,
                        if (sentence.isFocus) "FOCUS" else "CONTEXT"))
            }
        }

        suspend fun archiveAcceptedFixture() {
            FormationDatabase(context).use { helper ->
                val db = helper.writableDatabase
                db.execSQL("insert into writing_batch_completions values(?,?,?,'INDEPENDENT')", arrayOf(account, chapterId, "write-$runId"))
                tasks.forEachIndexed { index, task ->
                    db.execSQL("insert into scene_response_tasks values(?,?,?,?,?,?,?,?)", arrayOf<Any>(account, chapterId,
                        task.taskId, task.sentenceId, index, requireNotNull(task.backgroundEn),
                        "AI\u001f${task.contextLines.single().textEn}", task.targetSentenceEn))
                    // Explicit already-accepted interface fixtures; not evidence of original-audio assessment.
                    db.execSQL("insert into writing_result_drafts values(?,?,?,'WHOLE_WRITING','',1)", arrayOf(account, chapterId, task.sentenceId))
                    db.execSQL("insert into full_speaking_result_drafts values(?,?,?,1)", arrayOf(account, chapterId, task.sentenceId))
                    db.execSQL("insert into scene_response_result_drafts values(?,?,?,1)", arrayOf(account, chapterId, task.sentenceId))
                }
            }
            assertTrue("Normal archived fixtures use the real transaction, never a fake archive row",
                AndroidChapterArchiveRepository(context, runtime).archive(chapterId, "archive-$runId") is ChapterArchiveResult.Archived)
        }

        fun removeOnlyOwnTasks() = FormationDatabase(context).use { helper ->
            assertEquals(2, helper.writableDatabase.delete("scene_response_tasks", "account_id=? and chapter_id=?", arrayOf(account, chapterId)))
        }

        fun graph(allowFixtureGeneration: Boolean = false) = ProductionAppGraph(context, runtime, HttpTransport { request ->
            requests.incrementAndGet()
            check(allowFixtureGeneration) { "Saved review tasks must never call HTTP or AI" }
            assertEquals("/api/v1/learning/scene-response-tasks", request.path)
            assertEquals("POST", request.method)
            val input = JSONObject(requireNotNull(request.body))
            assertEquals(chapterId, input.getString("chapterId"))
            val task = tasks.single { it.sentenceId == input.getString("focusSentenceId") }
            HttpResponse(200, JSONObject().put("taskId", task.taskId).put("sentenceId", task.sentenceId)
                .put("backgroundEn", task.backgroundEn).put("targetSentenceEn", task.targetSentenceEn)
                .put("contextLines", JSONArray(task.contextLines.map { line ->
                    JSONObject().put("speaker", line.speaker).put("textEn", line.textEn)
                })).toString())
        }, object : SessionCredentialStore {
            override suspend fun load(): StoredSession? { credentials.incrementAndGet(); error("No credential read") }
            override suspend fun save(session: StoredSession) { credentials.incrementAndGet(); error("No credential write") }
            override suspend fun clear() { credentials.incrementAndGet(); error("No credential clear") }
        })

        fun rows(): Map<String, List<List<String?>>> = FormationDatabase(context).use { helper ->
            val db = helper.readableDatabase
            val tables = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name", null)
                .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            tables.associateWith { table ->
                require(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
                db.rawQuery("select * from $table order by rowid", null).use { cursor -> buildList {
                    while (cursor.moveToNext()) add(List(cursor.columnCount) { if (cursor.isNull(it)) null else cursor.getString(it) })
                } }
            }
        }

        fun assertNoExternalWork() { assertEquals("No generation during saved review", 0, requests.get()); assertNoCredentialsOrAudio() }
        fun assertNoCredentialsOrAudio() {
            assertEquals(0, credentials.get())
            assertFalse(directory.exists() && directory.walkTopDown().any {
                it.isFile && it.extension.lowercase() in setOf("m4a", "wav", "mp3", "aac", "3gp")
            })
        }

        override fun close() {
            context.deleteDatabase("real-language-learning.db")
            synchronized(preferenceNames) { preferenceNames.toList() }.forEach { name ->
                check(name.startsWith("scene-review-boundary-$runId-")); base.deleteSharedPreferences(name)
            }
            check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            check(directory.name == "scene-review-boundary-$runId")
            check(!directory.exists() || directory.deleteRecursively())
        }
    }
}
