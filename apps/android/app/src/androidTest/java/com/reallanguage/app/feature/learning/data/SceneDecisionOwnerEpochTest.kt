package com.reallanguage.app.feature.learning.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.SceneEvaluationDecision
import com.reallanguage.app.feature.learning.domain.SceneResponseSaveResult
import com.reallanguage.app.feature.learning.domain.SceneTaskGenerationResult
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Actual isolated SQLite/session leases; supplied decisions are not audio-quality evidence. */
class SceneDecisionOwnerEpochTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private lateinit var runtime: SessionRuntime
    private val account = "scene-result-owner-${UUID.randomUUID()}"
    private val chapter = FixedLearningChapter(
        "c1", 1,
        listOf(
            FixedLearningSentence("s1", "I drink tea.", "我喝茶。", true),
            FixedLearningSentence("s2", "I ask for feedback.", "我请求反馈。", true),
        ),
    )

    @Before fun setUp() {
        context = IsolatedLearningDatabaseContext(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "scene-decision-owner-epoch",
        )
        runtime = SessionRuntime().apply { update("isolated-first-session", account) }
        database = FormationDatabase(context)
        val db = database.writableDatabase
        db.execSQL(
            "insert into formation_flows values (?, 'flow', 'FIXED', 'DIARY', '隔离场景结果')",
            arrayOf(account),
        )
        db.execSQL("insert into chapters values (?, 'flow', 'c1', 0, 'NOT_STARTED')", arrayOf(account))
        chapter.sentences.forEachIndexed { index, sentence ->
            db.execSQL(
                "insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) " +
                    "values (?, 'c1', ?, ?, ?, ?, 'FOCUS')",
                arrayOf<Any>(account, sentence.sentenceId, index, sentence.textZh, sentence.textEn),
            )
            db.execSQL(
                "insert into scene_response_tasks(account_id,chapter_id,task_id,sentence_id,sentence_order,background_en,context_lines,target_sentence_en) " +
                    "values (?, 'c1', ?, ?, ?, 'An isolated task', ?, ?)",
                arrayOf<Any>(account, "task-$index", sentence.sentenceId, index, "AI\u001fWhat do you do?", sentence.textEn),
            )
        }
    }

    @After fun tearDown() {
        database.close()
        assertTrue(context.deleteDatabase("real-language-learning.db"))
        runtime.clear()
    }

    @Test fun exitedRepositoryCannotReplaceTheNewSameAccountSessionsSavedResult() = runBlocking {
        val old = repository()
        assertTrue(old.restoreTasks(chapter) is SceneTaskGenerationResult.Ready)
        val previousEpoch = runtime.accountEpoch
        runtime.clear()
        runtime.update("isolated-second-session", account)
        assertNotEquals(previousEpoch, runtime.accountEpoch)

        val fresh = repository()
        assertTrue(fresh.restoreTasks(chapter) is SceneTaskGenerationResult.Ready)
        assertEquals(SceneResponseSaveResult.Saved, fresh.saveFinalDecision("c1", "s1", SceneEvaluationDecision.PASS))
        assertEquals(1, savedDecision("s1"))

        assertEquals(
            "An exited entry must not regain write authority after the same account logs in again",
            SceneResponseSaveResult.Failed,
            old.saveFinalDecision("c1", "s1", SceneEvaluationDecision.FAIL),
        )
        assertEquals("The fresh session's persisted result must remain unchanged", 1, savedDecision("s1"))
        assertEquals(SceneResponseSaveResult.Saved, fresh.saveFinalDecision("c1", "s2", SceneEvaluationDecision.FAIL))
        assertEquals(0, savedDecision("s2"))
    }

    @Test fun captureCancellationOnReturnDoesNotLetAnExitedEntryReacquireTasksOrRecording() = runBlocking {
        val capture = CountingCapture()
        val old = repository(capture)
        assertTrue(old.restoreTasks(chapter) is SceneTaskGenerationResult.Ready)
        assertTrue(old.startCapture())
        old.cancelCapture()
        assertEquals(1, capture.starts)
        assertEquals(1, capture.cancels)
        assertNull(savedDecision("s1"))

        runtime.clear()
        runtime.update("isolated-returned-session", account)
        assertEquals(SceneTaskGenerationResult.Failed, old.restoreTasks(chapter))
        assertFalse(old.startCapture())
        assertEquals(1, capture.starts)
        assertEquals(SceneResponseSaveResult.Failed, old.saveFinalDecision("c1", "s1", SceneEvaluationDecision.PASS))
        assertNull(savedDecision("s1"))

        val fresh = repository()
        assertTrue(fresh.restoreTasks(chapter) is SceneTaskGenerationResult.Ready)
        assertEquals(SceneResponseSaveResult.Saved, fresh.saveFinalDecision("c1", "s1", SceneEvaluationDecision.FAIL))
        assertEquals(0, savedDecision("s1"))
    }

    @Test fun returningWithinTheCurrentSessionPreservesSavedResultsAndAllowsANewEntryToContinue() = runBlocking {
        val capture = CountingCapture()
        val first = repository(capture)
        assertTrue(first.restoreTasks(chapter) is SceneTaskGenerationResult.Ready)
        assertEquals(SceneResponseSaveResult.Saved, first.saveFinalDecision("c1", "s1", SceneEvaluationDecision.PASS))
        assertTrue(first.startCapture())
        first.cancelCapture()
        assertEquals(1, capture.cancels)
        assertEquals(1, savedDecision("s1"))
        assertNull(savedDecision("s2"))

        val returned = repository()
        assertTrue(returned.restoreTasks(chapter) is SceneTaskGenerationResult.Ready)
        assertEquals(SceneResponseSaveResult.Saved, returned.saveFinalDecision("c1", "s2", SceneEvaluationDecision.FAIL))
        assertEquals(1, savedDecision("s1"))
        assertEquals(0, savedDecision("s2"))
    }

    @Test fun aCancelledFinalSaveCannotCommitOrReturnASuccess() = runBlocking {
        val repository = repository()
        assertTrue(repository.restoreTasks(chapter) is SceneTaskGenerationResult.Ready)
        var returned = false
        val cancelled = launch {
            currentCoroutineContext().cancel(CancellationException("isolated-entry-return"))
            repository.saveFinalDecision("c1", "s1", SceneEvaluationDecision.PASS)
            returned = true
        }
        cancelled.join()
        assertTrue(cancelled.isCancelled)
        assertFalse(returned)
        assertNull(savedDecision("s1"))
    }

    @Test fun anOrdinaryTokenRefreshKeepsTheCurrentResultRepositoryUsable() = runBlocking {
        val repository = repository()
        assertTrue(repository.restoreTasks(chapter) is SceneTaskGenerationResult.Ready)
        val epoch = runtime.accountEpoch
        runtime.update("isolated-refreshed-session", account)
        assertEquals(epoch, runtime.accountEpoch)
        assertEquals(SceneResponseSaveResult.Saved, repository.saveFinalDecision("c1", "s1", SceneEvaluationDecision.PASS))
        assertEquals(1, savedDecision("s1"))
    }

    private fun repository(capture: CountingCapture = CountingCapture()) = AndroidSceneResponseRepository(
        context,
        HttpTransport { error("This storage verification must not make HTTP or AI calls") },
        runtime,
        capture,
    )

    /** Every assertion reads through a separate helper, not a repository's in-memory state. */
    private fun savedDecision(sentenceId: String): Int? = FormationDatabase(context).use { helper ->
        helper.readableDatabase.rawQuery(
            "select passed from scene_response_result_drafts where account_id=? and chapter_id='c1' and sentence_id=?",
            arrayOf(account, sentenceId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getInt(0).also { assertFalse(cursor.moveToNext()) }
        }
    }

    private class CountingCapture : ReadSpeakAudioCapture {
        var starts = 0
        var cancels = 0
        override fun start(): Boolean { starts++; return true }
        override fun finish(): CapturedReadSpeakAudio? = null
        override fun cancel() { cancels++ }
    }
}
