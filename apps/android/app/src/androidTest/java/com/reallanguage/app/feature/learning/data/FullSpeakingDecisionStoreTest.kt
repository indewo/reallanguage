package com.reallanguage.app.feature.learning.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.SpeakingDecisionSaveResult
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationDecision
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FullSpeakingDecisionStoreTest {
    @Test fun rejectsMissingNonFocusAndArchivedSentencesAndReadsBackSavedDecision() = runBlocking {
        withChapter { context, account, runtime ->
            val repository = repository(context, runtime)
            assertEquals(SpeakingDecisionSaveResult.Failed, repository.saveIndependentDecision("missing", SpeakingEvaluationDecision.PASS))
            assertEquals(SpeakingDecisionSaveResult.Failed, repository.saveIndependentDecision("context", SpeakingEvaluationDecision.PASS))
            assertEquals(SpeakingDecisionSaveResult.Saved, repository.saveIndependentDecision("s1", SpeakingEvaluationDecision.PASS))
            FormationDatabase(context).use { helper ->
                helper.readableDatabase.rawQuery("select passed from full_speaking_result_drafts where account_id=? and chapter_id='c1' and sentence_id='s1'", arrayOf(account)).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
                helper.writableDatabase.execSQL("update chapters set status='COMPLETED' where account_id=?", arrayOf(account))
            }
            assertEquals(SpeakingDecisionSaveResult.Failed, repository.saveIndependentDecision("s1", SpeakingEvaluationDecision.FAIL))
        }
    }

    @Test fun aLateDecisionCannotBeWrittenIntoAnotherAccountOrAfterLogout() = runBlocking {
        withChapter { context, account, runtime ->
            val repository = repository(context, runtime)
            runtime.update("isolated-other-session", "$account-other")
            assertEquals(SpeakingDecisionSaveResult.Failed, repository.saveIndependentDecision("s1", SpeakingEvaluationDecision.PASS))
            runtime.clear()
            assertEquals(SpeakingDecisionSaveResult.Failed, repository.saveIndependentDecision("s1", SpeakingEvaluationDecision.PASS))
        }
    }

    private fun repository(context: Context, runtime: SessionRuntime) = AndroidFullSentenceSpeakingRepository(
        context, HttpTransport { error("No network is used in this storage test") }, runtime, "c1", "Test.",
        object : ReadSpeakAudioCapture { override fun start() = false; override fun finish(): CapturedReadSpeakAudio? = null; override fun cancel() = Unit },
    )

    private suspend fun withChapter(block: suspend (Context, String, SessionRuntime) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val account = "speaking-store-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("isolated-session-not-real", account) }
        FormationDatabase(context).use { helper ->
            helper.writableDatabase.execSQL("insert into chapters values (?, 'flow', 'c1', 0, 'NOT_STARTED')", arrayOf(account))
            listOf("s1", "context").forEachIndexed { index, id -> helper.writableDatabase.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?, 'c1', ?, ?, '测试', 'Test.', ?)", arrayOf<Any>(account, id, index, if (id == "s1") "FOCUS" else "CONTEXT")) }
        }
        try { block(context, account, runtime) }
        finally { FormationDatabase(context).use { helper -> listOf("full_speaking_result_drafts", "fixed_sentences", "chapters").forEach { table -> listOf(account, "$account-other").forEach { helper.writableDatabase.delete(table, "account_id=?", arrayOf(it)) } } } }
    }
}
