package com.reallanguage.app.feature.learning.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.*
import com.reallanguage.app.feature.learning.presentation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Storage/recovery only. Feedback is an explicit isolated test double, never real audio evidence. */
class FullSpeakingProcessRecoveryTest {
    @Test fun roundsAndPendingJudgmentSurviveSeparateProcesses() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("speakingPhase")
        assumeTrue(phase in setOf("train", "pending", "read"))
        val account = requireNotNull(args.getString("progressAccount")).also { require(it.matches(Regex("process-test-[a-z0-9-]+"))) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runtime = SessionRuntime().apply { update("isolated-token-not-a-real-session", account) }
        val chapter = FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I felt calm.", "我很平静。", true)))
        if (phase == "train") FormationDatabase(context).use { helper ->
            helper.writableDatabase.execSQL("insert into chapters values (?, 'process-test-flow', 'c1', 0, 'NOT_STARTED')", arrayOf(account))
            helper.writableDatabase.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?, 'c1', 's1', 0, '我很平静。', 'I felt calm.', 'FOCUS')", arrayOf(account))
        }
        var aiRequests = 0
        var savedDecisions = 0
        val repository = object : FullSentenceSpeakingRepository {
            override fun startCapture() = true
            override fun cancelCapture() = Unit
            override suspend fun trainingFeedback(sentenceId: String, mode: SpeakingTrainingMode): ReadingFeedbackResult {
                check(phase == "train"); aiRequests++
                return ReadingFeedbackResult.Ready(ReadingFeedback("隔离测试反馈", ReadingFeedbackAction.CONTINUE))
            }
            override suspend fun transcribeIndependentRetelling(sentenceId: String): SpeakingTranscriptionResult {
                check(phase == "pending"); aiRequests++
                return SpeakingTranscriptionResult.Ready(SpeakingTranscription("I felt calm.", emptyList(), null))
            }
            override suspend fun evaluateIndependentRetelling(sentenceId: String, confirmedTranscript: String, answerAudioPlayed: Boolean): SpeakingEvaluationResult {
                check(phase == "pending"); aiRequests++
                return SpeakingEvaluationResult.Ready(SpeakingEvaluationCandidate(SpeakingEvaluationDecision.PASS, 90, true, true, "隔离测试评价，不是真实原音结果"))
            }
            override suspend fun saveIndependentDecision(sentenceId: String, decision: SpeakingEvaluationDecision): SpeakingDecisionSaveResult {
                if (phase == "pending") return SpeakingDecisionSaveResult.Failed
                check(phase == "read"); savedDecisions++
                assertEquals(SpeakingEvaluationDecision.FAIL, decision)
                // Isolate the round/recovery invariant without claiming the production result repository passed.
                return SpeakingDecisionSaveResult.Saved
            }
        }
        val store = AndroidLearningProgressStore(context, runtime, "c1", "s1", "LR-06")
        val model = withContext(Dispatchers.Main) { FullSentenceSpeakingViewModel(chapter, "s1", repository, store) }
        suspend fun settled() = withTimeout(5_000) { model.uiState.first { !it.submitting } }.also { assertFalse(it.progressFailed) }
        suspend fun act(action: FullSentenceSpeakingAction) {
            withContext(Dispatchers.Main) { model.onAction(action) }
            settled()
        }
        settled()
        when (phase) {
            "train" -> {
                repeat(4) {
                    act(FullSentenceSpeakingAction.StartRequested)
                    act(FullSentenceSpeakingAction.FinishedRequested)
                    act(FullSentenceSpeakingAction.ContinueTrainingRequested)
                }
                act(FullSentenceSpeakingAction.AnswerAudioRequested)
                act(FullSentenceSpeakingAction.BackRequested)
                assertEquals(FullSentenceSpeakingEffect.ReturnToAudioDictation, withTimeout(5_000) { model.effects.first() })
                assertEquals("4", store.read()?.get("attemptIndex"))
                assertEquals("true", store.read()?.get("answerAudioPlayed"))
                assertEquals(4, aiRequests)
            }
            "pending" -> {
                assertEquals(4, model.uiState.value.attemptIndex)
                assertTrue(model.uiState.value.answerAudioPlayed)
                act(FullSentenceSpeakingAction.StartRequested)
                act(FullSentenceSpeakingAction.FinishedRequested)
                act(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
                assertTrue(model.uiState.value.decisionSaveFailed)
                act(FullSentenceSpeakingAction.BackRequested)
                withTimeout(5_000) { model.effects.first() }
                assertEquals("PASS", store.read()?.get("candidateDecision"))
                assertEquals("", store.read()?.get("finalDecision"))
                assertEquals(2, aiRequests)
            }
            "read" -> {
                assertEquals(4, model.uiState.value.attemptIndex)
                assertEquals("I felt calm.", model.uiState.value.transcript)
                assertTrue(model.uiState.value.answerAudioPlayed)
                act(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
                assertEquals(SpeakingEvaluationDecision.FAIL, model.uiState.value.finalDecision)
                assertEquals(0, aiRequests); assertEquals(1, savedDecisions)
                act(FullSentenceSpeakingAction.BackRequested)
                withTimeout(5_000) { model.effects.first() }
                assertEquals("FAIL", store.read()?.get("finalDecision"))
                FormationDatabase(context).use { helper ->
                    listOf("learning_module_progress", "learning_checkpoints", "fixed_sentences", "chapters").forEach {
                        helper.writableDatabase.delete(it, "account_id=?", arrayOf(account))
                    }
                }
            }
        }
    }
}
