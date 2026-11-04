package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** VM contract only; the repository explicitly owns any still-replayable original attempt. */
@OptIn(ExperimentalCoroutinesApi::class)
class FullSpeakingTechnicalRetryTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun prepare() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()

    @Test fun explicitTranscriptionRetryUsesTheExistingAttemptWithoutAnotherStartOrFinish() = runTest(dispatcher) {
        val repository = RetryRepository().apply { transcriptionUnavailable = true }
        val model = finalRound(repository)
        advanceUntilIdle()
        model.onAction(FullSentenceSpeakingAction.StartRequested)
        model.onAction(FullSentenceSpeakingAction.FinishedRequested)
        advanceUntilIdle()
        assertTrue(model.uiState.value.captureFailed)
        repository.transcriptionUnavailable = false
        model.onAction(FullSentenceSpeakingAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(2, repository.transcriptions)
        assertEquals(1, repository.starts)
        assertFalse(model.uiState.value.recording)
        assertEquals(TEXT, model.uiState.value.transcript)
        assertTrue(model.uiState.value.transcriptReadyForConfirmation)
        assertEquals(0, repository.evaluations)
    }

    @Test fun uncertainEvaluationKeepsTheConfirmedFactsAndRetriesOnlyThatEvaluation() = runTest(dispatcher) {
        val repository = RetryRepository()
        val model = finalRound(repository)
        advanceUntilIdle()
        model.onAction(FullSentenceSpeakingAction.StartRequested)
        model.onAction(FullSentenceSpeakingAction.FinishedRequested)
        advanceUntilIdle()
        model.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
        advanceUntilIdle()
        assertFalse("The original evidence still exists", model.uiState.value.needsNewRecording)
        assertTrue(model.uiState.value.transcriptReadyForConfirmation)
        model.onAction(FullSentenceSpeakingAction.TranscriptChanged("a different unconfirmed statement"))
        model.onAction(FullSentenceSpeakingAction.AnswerAudioRequested)
        assertEquals(TEXT, model.uiState.value.transcript)
        assertFalse(model.uiState.value.answerAudioPlayed)
        repository.evaluationUnavailable = false
        model.onAction(FullSentenceSpeakingAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(2, repository.evaluations)
        assertEquals(listOf(TEXT to false, TEXT to false), repository.confirmed)
        assertEquals(1, repository.transcriptions)
        assertEquals(1, repository.starts)
        assertEquals(listOf(SpeakingEvaluationDecision.PASS), repository.saved)
    }

    @Test fun repeatedRetryClicksCannotStartConcurrentEvaluations() = runTest(dispatcher) {
        val repository = RetryRepository()
        val model = finalRound(repository)
        advanceUntilIdle()
        model.onAction(FullSentenceSpeakingAction.StartRequested)
        model.onAction(FullSentenceSpeakingAction.FinishedRequested)
        advanceUntilIdle()
        model.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
        advanceUntilIdle()
        repository.evaluationUnavailable = false
        repository.pending = CompletableDeferred()
        try {
            model.onAction(FullSentenceSpeakingAction.RetryRequested)
            advanceUntilIdle()
            assertTrue(model.uiState.value.submitting)
            repeat(3) { model.onAction(FullSentenceSpeakingAction.RetryRequested) }
            advanceUntilIdle()
            assertEquals(2, repository.evaluations)
            assertEquals(1, repository.starts)
            assertTrue(repository.saved.isEmpty())
        } finally { repository.pending?.complete(Unit) }
        advanceUntilIdle()
        assertEquals(listOf(SpeakingEvaluationDecision.PASS), repository.saved)
    }

    @Test fun terminalAttemptNeverPretendsTheAudioIsMissingOrStartsAnotherAttemptOnRetry() = runTest(dispatcher) {
        val repository = RetryRepository().apply { blocked = true }
        val model = finalRound(repository)
        advanceUntilIdle()
        model.onAction(FullSentenceSpeakingAction.StartRequested)
        model.onAction(FullSentenceSpeakingAction.FinishedRequested)
        advanceUntilIdle()
        model.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
        advanceUntilIdle()
        assertFalse("Terminal server status is not missing recording evidence", model.uiState.value.needsNewRecording)
        model.onAction(FullSentenceSpeakingAction.RetryRequested)
        model.onAction(FullSentenceSpeakingAction.TranscriptChanged("unconfirmed replacement"))
        advanceUntilIdle()
        assertEquals(TEXT, model.uiState.value.transcript)
        assertEquals(1, repository.starts)
        assertEquals(1, repository.evaluations)
        assertTrue(repository.saved.isEmpty())
        model.onAction(FullSentenceSpeakingAction.StartRequested)
        assertEquals("Only explicit new recording may create another attempt", 2, repository.starts)
        assertTrue(model.uiState.value.recording)
    }

    private fun finalRound(repository: RetryRepository): FullSentenceSpeakingViewModel {
        val progress = object : LearningProgressStore {
            private var value: Map<String, String>? = mapOf("version" to "1", "attemptIndex" to "4",
                "answerAudioPlayed" to "false", "transcript" to "", "feedbackZh" to "",
                "trainingCanContinue" to "false", "finalDecision" to "")
            override suspend fun read() = value
            override suspend fun write(values: Map<String, String>): Boolean { value = values; return true }
        }
        return FullSentenceSpeakingViewModel(FixedLearningChapter("c", 1,
            listOf(FixedLearningSentence("s", TEXT, "我喝绿茶。", true))), "s", repository, progress)
    }

    private class RetryRepository : FullSentenceSpeakingRepository {
        var starts = 0
        var transcriptions = 0
        var evaluations = 0
        var transcriptionUnavailable = false
        var evaluationUnavailable = true
        var pending: CompletableDeferred<Unit>? = null
        var transcriptionPending = false
        var evaluationPending = false
        var blocked = false
        val confirmed = mutableListOf<Pair<String, Boolean>>()
        val saved = mutableListOf<SpeakingEvaluationDecision>()
        override fun startCapture(): Boolean { starts++; return true }
        override fun cancelCapture() = Unit
        override fun hasPendingTranscriptionRetry() = transcriptionPending
        override fun hasPendingEvaluationRetry() = evaluationPending && !blocked
        override fun technicalRetryBlocked() = evaluationPending && blocked
        override suspend fun trainingFeedback(sentenceId: String, mode: SpeakingTrainingMode) = ReadingFeedbackResult.Failed
        override suspend fun transcribeIndependentRetelling(sentenceId: String): SpeakingTranscriptionResult {
            transcriptions++
            transcriptionPending = transcriptionUnavailable
            return if (transcriptionUnavailable) SpeakingTranscriptionResult.Failed
            else SpeakingTranscriptionResult.Ready(SpeakingTranscription(TEXT, emptyList(), null))
        }
        override suspend fun evaluateIndependentRetelling(sentenceId: String, confirmedTranscript: String,
            answerAudioPlayed: Boolean): SpeakingEvaluationResult {
            evaluations++
            confirmed += confirmedTranscript to answerAudioPlayed
            pending?.await()
            evaluationPending = evaluationUnavailable
            return if (evaluationUnavailable) SpeakingEvaluationResult.Failed else SpeakingEvaluationResult.Ready(
                SpeakingEvaluationCandidate(SpeakingEvaluationDecision.PASS, 95, true, true, "隔离反馈"))
        }
        override suspend fun saveIndependentDecision(sentenceId: String, decision: SpeakingEvaluationDecision): SpeakingDecisionSaveResult {
            saved += decision
            return SpeakingDecisionSaveResult.Saved
        }
    }

    private companion object { const val TEXT = "I drink green tea." }
}
