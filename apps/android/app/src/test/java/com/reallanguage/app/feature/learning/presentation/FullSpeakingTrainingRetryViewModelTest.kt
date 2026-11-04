package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModelStore
import com.reallanguage.app.feature.learning.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Training-only VM contract. A fake repository explicitly owns each retained attempt.
 * Checkpoints only select a fixture round; no prior learning, SQL, audio or paid AI is simulated. */
@OptIn(ExperimentalCoroutinesApi::class)
class FullSpeakingTrainingRetryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    @Before fun prepare() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() {
        owners.forEach(ViewModelStore::clear)
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun firstThreeTrainingRoundsRetryOnlyTheirOriginalSentenceAndMode() = runTest(dispatcher) {
        for (attempt in 0..2) for (action in ReadingFeedbackAction.entries) assertUnknownRetry(attempt, action)
    }

    @Test fun fourthTrainingRoundRetriesItsFeedbackWithoutEnteringIndependentEvaluation() = runTest(dispatcher) {
        for (action in ReadingFeedbackAction.entries) assertUnknownRetry(3, action)
    }

    @Test fun terminalTrainingAttemptCannotRetryAndOnlyExplicitStartCreatesNewRecording() = runTest(dispatcher) {
        for (attempt in 0..3) {
            val (vm, repository) = failedTraining(attempt, Failure.TERMINAL)
            val original = vm.uiState.value
            assertTrue(original.technicalRetryBlocked)
            assertFalse(original.technicalRetryPending)
            repeat(3) { vm.onAction(FullSentenceSpeakingAction.RetryRequested) }
            vm.onAction(FullSentenceSpeakingAction.ContinueTrainingRequested)
            vm.onAction(FullSentenceSpeakingAction.TranscriptChanged("unconfirmed replacement"))
            advanceUntilIdle()
            assertPosition(vm, attempt)
            assertEquals(original.transcript, vm.uiState.value.transcript)
            assertEquals(original.feedbackZh, vm.uiState.value.feedbackZh)
            assertTrue(vm.uiState.value.technicalRetryBlocked)
            assertEquals(listOf(Call(SENTENCE, mode(attempt))), repository.calls)
            assertEquals(1, repository.starts)
            assertEquals(0, repository.cancels)

            repository.failure = null
            vm.onAction(FullSentenceSpeakingAction.StartRequested)
            assertEquals(2, repository.starts)
            assertTrue(vm.uiState.value.recording)
            assertFalse(vm.uiState.value.technicalRetryBlocked)
            assertFalse(vm.uiState.value.technicalRetryPending)
            assertEquals(1, repository.calls.size)
            vm.onAction(FullSentenceSpeakingAction.FinishedRequested)
            advanceUntilIdle()
            assertEquals(listOf(Call(SENTENCE, mode(attempt)), Call(SENTENCE, mode(attempt))), repository.calls)
            assertTrue(vm.uiState.value.trainingCanContinue)
            assertPosition(vm, attempt)
            repository.assertNoIndependentWork()
        }
    }

    @Test fun failureWithoutRetainedAudioKeepsExistingExplicitRerecordingFlow() = runTest(dispatcher) {
        for (attempt in listOf(0, 3)) {
            val (vm, repository) = failedTraining(attempt, Failure.NO_RETAINED_AUDIO)
            assertFalse(vm.uiState.value.technicalRetryPending)
            assertFalse(vm.uiState.value.technicalRetryBlocked)
            vm.onAction(FullSentenceSpeakingAction.RetryRequested)
            advanceUntilIdle()
            assertEquals(1, repository.calls.size)
            assertEquals(1, repository.starts)
            assertFalse(vm.uiState.value.recording)
            assertFalse(vm.uiState.value.trainingCanContinue)
            assertPosition(vm, attempt)
            repository.failure = null
            vm.onAction(FullSentenceSpeakingAction.StartRequested)
            assertEquals(2, repository.starts)
            assertTrue(vm.uiState.value.recording)
            assertEquals("Starting does not itself submit feedback", 1, repository.calls.size)
            repository.assertNoIndependentWork()
        }
    }

    private suspend fun TestScope.assertUnknownRetry(attempt: Int, action: ReadingFeedbackAction) {
        val (vm, repository) = failedTraining(attempt, Failure.UNKNOWN)
        val exact = Call(SENTENCE, mode(attempt))
        assertTrue("The original training request is still replayable", vm.uiState.value.technicalRetryPending)
        assertFalse(vm.uiState.value.technicalRetryBlocked)
        vm.onAction(FullSentenceSpeakingAction.StartRequested)
        vm.onAction(FullSentenceSpeakingAction.ContinueTrainingRequested)
        vm.onAction(FullSentenceSpeakingAction.TranscriptChanged("unconfirmed replacement"))
        vm.onAction(FullSentenceSpeakingAction.AnswerAudioRequested)
        assertEquals(1, repository.starts)
        assertEquals("", vm.uiState.value.transcript)
        assertFalse(vm.uiState.value.answerAudioPlayed)
        assertPosition(vm, attempt)
        repository.failure = null
        repository.action = action
        val gate = CompletableDeferred<Unit>().also { repository.gate = it }
        try {
            repeat(3) { vm.onAction(FullSentenceSpeakingAction.RetryRequested) }
            assertTrue("First retry occupies submission before coroutine dispatch", vm.uiState.value.submitting)
            advanceUntilIdle()
            vm.onAction(FullSentenceSpeakingAction.RetryRequested)
            vm.onAction(FullSentenceSpeakingAction.StartRequested)
            vm.onAction(FullSentenceSpeakingAction.FinishedRequested)
            vm.onAction(FullSentenceSpeakingAction.ContinueTrainingRequested)
            advanceUntilIdle()
            assertEquals(listOf(exact, exact), repository.calls)
            assertEquals(1, repository.starts)
            assertEquals(0, repository.cancels)
            assertFalse(vm.uiState.value.recording)
            assertFalse(vm.uiState.value.trainingCanContinue)
            assertPosition(vm, attempt)
            repository.assertNoIndependentWork()
        } finally { gate.complete(Unit) }
        advanceUntilIdle()
        assertFalse(vm.uiState.value.submitting)
        assertFalse(vm.uiState.value.technicalRetryPending)
        assertFalse(vm.uiState.value.technicalRetryBlocked)
        assertEquals(FEEDBACK, vm.uiState.value.feedbackZh)
        // PRODUCT_CONTEXT 245: uncertain feedback requests a reread, never a formal failure.
        assertEquals(action == ReadingFeedbackAction.CONTINUE, vm.uiState.value.trainingCanContinue)
        assertPosition(vm, attempt)
        vm.onAction(FullSentenceSpeakingAction.ContinueTrainingRequested)
        advanceUntilIdle()
        val next = if (action == ReadingFeedbackAction.CONTINUE) attempt + 1 else attempt
        assertEquals(next, vm.uiState.value.attemptIndex)
        assertEquals(SENTENCE, vm.uiState.value.currentFocusSentenceId)
        assertEquals(next == 4, vm.uiState.value.isIndependentRetelling)
        assertFalse(vm.uiState.value.trainingCanContinue)
        assertEquals(listOf(exact, exact), repository.calls)
        assertEquals(1, repository.starts)
        repository.assertNoIndependentWork()
        if (action == ReadingFeedbackAction.REPEAT_FOR_CLARITY) {
            vm.onAction(FullSentenceSpeakingAction.StartRequested)
            assertTrue(vm.uiState.value.recording)
            assertEquals(2, repository.starts)
            assertPosition(vm, attempt)
            assertEquals(listOf(exact, exact), repository.calls)
        }
    }

    private suspend fun TestScope.failedTraining(attempt: Int, failure: Failure): Pair<FullSentenceSpeakingViewModel, RetryRepository> {
        val repository = RetryRepository().apply { this.failure = failure }
        val vm = FullSentenceSpeakingViewModel(CHAPTER, SENTENCE, repository, MemoryProgress(attempt))
        owners += ViewModelStore().also { it.put("training", vm) }
        advanceUntilIdle()
        assertPosition(vm, attempt)
        vm.onAction(FullSentenceSpeakingAction.StartRequested)
        assertTrue(vm.uiState.value.recording)
        vm.onAction(FullSentenceSpeakingAction.FinishedRequested)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.submitting)
        assertFalse(vm.uiState.value.recording)
        assertTrue(vm.uiState.value.captureFailed)
        assertEquals(listOf(Call(SENTENCE, mode(attempt))), repository.calls)
        return vm to repository
    }

    private fun assertPosition(vm: FullSentenceSpeakingViewModel, attempt: Int) {
        val state = vm.uiState.value
        assertEquals(CHAPTER, state.chapter)
        assertEquals(SENTENCE, state.currentFocusSentenceId)
        assertEquals(attempt, state.attemptIndex)
        assertEquals(mode(attempt), state.trainingMode)
        assertFalse(state.isIndependentRetelling)
        assertNull(state.finalDecision)
        assertFalse(state.evaluationReceived)
    }
    private enum class Failure { UNKNOWN, TERMINAL, NO_RETAINED_AUDIO }
    private data class Call(val sentenceId: String, val mode: SpeakingTrainingMode)
    private class RetryRepository : FullSentenceSpeakingRepository {
        var starts = 0
        var cancels = 0
        private var independentCalls = 0
        private var formalSaves = 0
        var failure: Failure? = Failure.UNKNOWN
        var action = ReadingFeedbackAction.CONTINUE
        var gate: CompletableDeferred<Unit>? = null
        private var retained = false
        private var blocked = false
        val calls = mutableListOf<Call>()
        override fun startCapture(): Boolean { starts++; retained = false; blocked = false; return true }
        override fun cancelCapture() { cancels++; retained = false; blocked = false }
        override fun hasPendingTrainingRetry() = retained
        override fun technicalRetryBlocked() = blocked
        override suspend fun trainingFeedback(sentenceId: String, mode: SpeakingTrainingMode): ReadingFeedbackResult {
            calls += Call(sentenceId, mode)
            gate?.await()
            retained = failure == Failure.UNKNOWN
            blocked = failure == Failure.TERMINAL
            return if (failure == null) ReadingFeedbackResult.Ready(ReadingFeedback(FEEDBACK, action))
            else ReadingFeedbackResult.Failed
        }
        override suspend fun transcribeIndependentRetelling(sentenceId: String): SpeakingTranscriptionResult {
            independentCalls++; return SpeakingTranscriptionResult.Failed
        }
        override suspend fun evaluateIndependentRetelling(sentenceId: String, confirmedTranscript: String, answerAudioPlayed: Boolean): SpeakingEvaluationResult {
            independentCalls++; return SpeakingEvaluationResult.Failed
        }
        override suspend fun saveIndependentDecision(sentenceId: String, decision: SpeakingEvaluationDecision): SpeakingDecisionSaveResult {
            formalSaves++; return SpeakingDecisionSaveResult.Failed
        }
        fun assertNoIndependentWork() { assertEquals(0, independentCalls); assertEquals(0, formalSaves) }
    }
    private class MemoryProgress(attempt: Int) : LearningProgressStore {
        private var values = mapOf("version" to "1", "attemptIndex" to attempt.toString(),
            "answerAudioPlayed" to "false", "transcript" to "", "feedbackZh" to "",
            "trainingCanContinue" to "false", "finalDecision" to "")
        override suspend fun read() = values.toMap()
        override suspend fun write(values: Map<String, String>): Boolean { this.values = values.toMap(); return true }
    }
    private companion object {
        const val SENTENCE = "s2"
        const val FEEDBACK = "内容已收到，请注意连读。"
        val CHAPTER = FixedLearningChapter("c1", 1, listOf(
            FixedLearningSentence("s1", "I drink tea.", "我喝茶。", true),
            FixedLearningSentence(SENTENCE, "I felt calm.", "我感到平静。", true)))
        fun mode(attempt: Int) = if (attempt < 2) SpeakingTrainingMode.BILINGUAL_FOLLOW else SpeakingTrainingMode.ENGLISH_READING
    }
}
