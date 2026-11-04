package com.reallanguage.app.feature.learning.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SceneDecisionStoreTest {
    @Test fun inconsistentStoredTaskFailsWithoutRegenerationAndArchivedTasksRemainReadableForReview() = runBlocking {
        withChapter { context, account, runtime, chapter ->
            val repository = repository(context, runtime)
            FormationDatabase(context).use { helper ->
                helper.writableDatabase.execSQL("update chapters set status='COMPLETED' where account_id=?", arrayOf(account))
            }
            assertTrue(repository.generateTasks(chapter) is SceneTaskGenerationResult.Ready)
            FormationDatabase(context).use { helper ->
                helper.writableDatabase.execSQL("update scene_response_tasks set target_sentence_en='Wrong saved target.' where account_id=?", arrayOf(account))
            }
            var requests = 0
            val failed = AndroidSceneResponseRepository(context, HttpTransport { requests++; error("Unexpected regeneration") }, runtime,
                object : ReadSpeakAudioCapture { override fun start() = false; override fun finish(): CapturedReadSpeakAudio? = null; override fun cancel() = Unit })
            assertEquals(SceneTaskGenerationResult.Failed, failed.generateTasks(chapter))
            assertEquals(0, requests)
        }
    }
    @Test fun onlyActiveFixedFocusCanReceiveASavedDecision() = runBlocking {
        withChapter { context, account, runtime, chapter ->
            val repository = repository(context, runtime)
            assertTrue(repository.generateTasks(chapter) is SceneTaskGenerationResult.Ready)
            assertEquals(SceneResponseSaveResult.Failed, repository.saveFinalDecision("c1", "missing", SceneEvaluationDecision.PASS))
            assertEquals(SceneResponseSaveResult.Failed, repository.saveFinalDecision("c1", "context", SceneEvaluationDecision.PASS))
            assertEquals(SceneResponseSaveResult.Saved, repository.saveFinalDecision("c1", "s1", SceneEvaluationDecision.PASS))
            FormationDatabase(context).use { helper ->
                helper.readableDatabase.rawQuery("select passed from scene_response_result_drafts where account_id=? and chapter_id='c1' and sentence_id='s1'", arrayOf(account)).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
                helper.writableDatabase.execSQL("update chapters set status='COMPLETED' where account_id=?", arrayOf(account))
            }
            assertEquals(SceneResponseSaveResult.Failed, repository.saveFinalDecision("c1", "s1", SceneEvaluationDecision.FAIL))
        }
    }

    @Test fun oldRepositoryCannotSaveOrLoadAfterAccountSwitch() = runBlocking {
        withChapter { context, account, runtime, chapter ->
            val repository = repository(context, runtime)
            assertTrue(repository.generateTasks(chapter) is SceneTaskGenerationResult.Ready)
            runtime.update("isolated-other-token", "$account-other")
            assertFalse(repository.startCapture())
            assertEquals(SceneResponseSaveResult.Failed, repository.saveFinalDecision("c1", "s1", SceneEvaluationDecision.PASS))
            runtime.clear()
            assertEquals(SceneResponseSaveResult.Failed, repository.saveFinalDecision("c1", "s1", SceneEvaluationDecision.PASS))
        }
    }

    private fun repository(context: Context, runtime: SessionRuntime) = AndroidSceneResponseRepository(
        context, HttpTransport { error("This persistence test must not use any network") }, runtime,
        object : ReadSpeakAudioCapture { override fun start() = true; override fun finish(): CapturedReadSpeakAudio? = null; override fun cancel() = Unit },
    )

    private suspend fun withChapter(block: suspend (Context, String, SessionRuntime, FixedLearningChapter) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val account = "scene-store-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("isolated-token-not-real", account) }
        val chapter = FixedLearningChapter("c1", 1, listOf(
            FixedLearningSentence("s1", "Test.", "测试。", true),
            FixedLearningSentence("context", "Test.", "测试。", false),
        ))
        FormationDatabase(context).use { helper ->
            helper.writableDatabase.execSQL("insert into chapters values (?, 'flow', 'c1', 0, 'NOT_STARTED')", arrayOf(account))
            chapter.sentences.forEachIndexed { index, sentence -> helper.writableDatabase.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?, 'c1', ?, ?, ?, ?, ?)", arrayOf<Any>(account, sentence.sentenceId, index, sentence.textZh, sentence.textEn, if (sentence.isFocus) "FOCUS" else "CONTEXT")) }
            helper.writableDatabase.execSQL("insert into scene_response_tasks values (?, 'c1', 't1', 's1', 0, 'Context', ?, 'Test.')", arrayOf(account, "AI\u001fQuestion?"))
        }
        try { block(context, account, runtime, chapter) }
        finally { FormationDatabase(context).use { helper -> listOf("scene_response_result_drafts", "scene_response_tasks", "fixed_sentences", "chapters").forEach { table -> listOf(account, "$account-other").forEach { helper.writableDatabase.delete(table, "account_id=?", arrayOf(it)) } } } }
    }
}
