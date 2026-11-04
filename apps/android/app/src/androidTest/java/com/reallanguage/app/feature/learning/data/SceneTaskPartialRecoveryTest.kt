package com.reallanguage.app.feature.learning.data

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.*
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.*
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual private SQL/JSON with isolated transport. Not real AI quality evidence. */
class SceneTaskPartialRecoveryTest {
    @Test fun failedSecondTaskDoesNotLoseOrRequestTheAcceptedFirstTaskAfterReentry() = runBlocking {
        Fixture().use { f ->
            assertEquals(SceneTaskGenerationResult.Failed, f.repository().generateTasks(f.chapter))
            assertEquals(listOf("a", "b"), f.requestedSentences())
            assertEquals(1, f.savedCount())
            assertEquals(SceneTaskGenerationResult.Failed, f.repository().restoreTasks(f.chapter))
            val originalB = f.requests.last().body
            f.failSecond = false
            val ready = f.repository().generateTasks(f.chapter) as SceneTaskGenerationResult.Ready
            assertEquals(listOf("a", "b"), ready.tasks.map { it.sentenceId })
            assertEquals(listOf("a", "b", "b"), f.requestedSentences())
            assertEquals(originalB, f.requests.last().body)
            assertEquals(2, f.savedCount())
            assertEquals(ready, f.repository().restoreTasks(f.chapter))
            assertEquals(ready, f.repository().generateTasks(f.chapter))
            assertEquals(3, f.requests.size)
        }
    }

    @Test fun concurrentRepositoriesShareAcceptedTasksWithoutDuplicateGeneration() = runBlocking {
        Fixture().use { f ->
            f.failSecond = false
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.onFirst = { entered.complete(Unit); release.await() }
            val first = async { f.repository().generateTasks(f.chapter) }
            entered.await()
            val second = async { f.repository().generateTasks(f.chapter) }
            yield()
            release.complete(Unit)
            val result = first.await()
            assertTrue(result is SceneTaskGenerationResult.Ready)
            assertEquals(result, second.await())
            assertEquals(listOf("a", "b"), f.requestedSentences())
            assertEquals(2, f.savedCount())
        }
    }

    @Test fun acceptedPartialTaskCannotBeReusedAgainstChangedFixedFacts() = runBlocking {
        Fixture().use { f ->
            f.repository().generateTasks(f.chapter)
            assertEquals(1, f.savedCount())
            withContext(Dispatchers.IO) { FormationDatabase(f.context).use {
                it.writableDatabase.execSQL("update fixed_sentences set text_en='Changed fixed fact' where account_id=? and chapter_id=? and unit_id='a'", arrayOf(f.owner, f.chapter.chapterId))
            } }
            f.failSecond = false
            assertEquals(SceneTaskGenerationResult.Failed, f.repository().generateTasks(f.chapter))
            assertEquals(2, f.requests.size)
            assertEquals(1, f.savedCount())
        }
    }

    private class Fixture : Closeable {
        val owner = "scene-partial-${UUID.randomUUID()}"
        val context = IsolatedLearningDatabaseContext(InstrumentationRegistry.getInstrumentation().targetContext, owner)
        val runtime = SessionRuntime().apply { update("isolated", owner) }
        val chapter = FixedLearningChapter("chapter", 1, listOf(
            FixedLearningSentence("a", "I drink tea.", "我喝茶。", true),
            FixedLearningSentence("b", "It tastes good.", "味道很好。", true),
        ))
        val requests = mutableListOf<HttpRequest>()
        var failSecond = true
        var onFirst: suspend () -> Unit = {}
        init { FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("insert into formation_flows values(?,?,'FIXED','DIARY','隔离测试')", arrayOf(owner, owner))
            db.execSQL("insert into chapters values(?,?,?,0,'IN_PROGRESS')", arrayOf(owner, owner, chapter.chapterId))
            chapter.sentences.forEachIndexed { index, sentence ->
                db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,?,?,?,?,?,'FOCUS')", arrayOf<Any>(owner, chapter.chapterId, sentence.sentenceId, index, sentence.textZh, sentence.textEn))
            }
        } }
        fun repository() = AndroidSceneResponseRepository(context, HttpTransport { request ->
            check(request.path == "/api/v1/learning/scene-response-tasks")
            requests += request
            val id = JSONObject(requireNotNull(request.body)).getString("focusSentenceId")
            if (id == "a") onFirst()
            if (id == "b" && failSecond) HttpResponse(503, "") else {
                val sentence = chapter.sentences.single { it.sentenceId == id }
                HttpResponse(200, JSONObject().put("taskId", "task-$id").put("sentenceId", id)
                    .put("backgroundEn", "Drinks").put("contextLines", org.json.JSONArray())
                    .put("targetSentenceEn", sentence.textEn).toString())
            }
        }, runtime, object : ReadSpeakAudioCapture {
            override fun start(): Boolean = error("Must not capture while generating tasks")
            override fun finish(): CapturedReadSpeakAudio? = error("Must not capture")
            override fun cancel() = Unit
        })
        fun requestedSentences() = requests.map { JSONObject(requireNotNull(it.body)).getString("focusSentenceId") }
        fun savedCount() = FormationDatabase(context).use { it.readableDatabase.rawQuery(
            "select count(*) from scene_response_tasks where account_id=?", arrayOf(owner),
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) } }
        override fun close() { runtime.clear(); check(context.deleteDatabase("real-language-learning.db")) }
    }
}
