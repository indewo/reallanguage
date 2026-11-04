package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModelStore
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.LearningProgressStore
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.FullSentenceSpeakingRepository
import com.reallanguage.app.feature.learning.domain.ReadingFeedback
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackAction
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackResult
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationCandidate
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationDecision
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationResult
import com.reallanguage.app.feature.learning.domain.SpeakingDecisionSaveResult
import com.reallanguage.app.feature.learning.domain.SpeakingTrainingMode
import com.reallanguage.app.feature.learning.domain.SpeakingTranscription
import com.reallanguage.app.feature.learning.domain.SpeakingTranscriptionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FullSentenceSpeakingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val chapter = FixedLearningChapter(
        "c1", 1,
        listOf(FixedLearningSentence("s1", "I expressed myself clearly.", "我清楚地表达了自己。", true)),
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun failedEvaluationRequiresNewAudioInsteadOfConfirmingAConsumedRecordingForever() = runTest(dispatcher) {
        val repository = FakeRepository(evaluationFails = true)
        val viewModel = atFinalAttempt(repository)
        viewModel.onAction(FullSentenceSpeakingAction.StartRequested)
        viewModel.onAction(FullSentenceSpeakingAction.FinishedRequested)
        advanceUntilIdle()
        viewModel.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.needsNewRecording)
        assertFalse(viewModel.uiState.value.transcriptReadyForConfirmation)
        assertEquals("recognized transcript", viewModel.uiState.value.transcript)
        viewModel.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
        advanceUntilIdle()
        assertEquals(1, repository.evaluations)
        assertTrue(repository.savedDecisions.isEmpty())
        viewModel.onAction(FullSentenceSpeakingAction.StartRequested)
        assertTrue(viewModel.uiState.value.recording)
    }

    @Test
    fun restoresTrainingRoundAndHeardAnswerWithoutRepeatingFeedback() = runTest(dispatcher) {
        val store = MemoryProgress()
        val repository = FakeRepository()
        val vm = FullSentenceSpeakingViewModel(chapter, "s1", repository, store)
        advanceUntilIdle()
        repeat(4) {
            vm.onAction(FullSentenceSpeakingAction.StartRequested)
            vm.onAction(FullSentenceSpeakingAction.FinishedRequested)
            advanceUntilIdle()
            vm.onAction(FullSentenceSpeakingAction.ContinueTrainingRequested)
            advanceUntilIdle()
        }
        vm.onAction(FullSentenceSpeakingAction.AnswerAudioRequested)
        advanceUntilIdle()
        val restoredRepository = FakeRepository()
        val restored = FullSentenceSpeakingViewModel(chapter, "s1", restoredRepository, store)
        advanceUntilIdle()
        assertEquals(4, restored.uiState.value.attemptIndex)
        assertTrue(restored.uiState.value.answerAudioPlayed)
        assertEquals(0, restoredRepository.trainingModes.size)
    }

    @Test
    fun restoresPendingEvaluationAndRetriesOnlyTheLocalSave() = runTest(dispatcher) {
        val store = MemoryProgress()
        val repository = FakeRepository(saveResult = SpeakingDecisionSaveResult.Failed)
        val vm = FullSentenceSpeakingViewModel(chapter, "s1", repository, store)
        advanceUntilIdle()
        repeat(4) {
            vm.onAction(FullSentenceSpeakingAction.StartRequested)
            vm.onAction(FullSentenceSpeakingAction.FinishedRequested)
            advanceUntilIdle()
            vm.onAction(FullSentenceSpeakingAction.ContinueTrainingRequested)
            advanceUntilIdle()
        }
        vm.onAction(FullSentenceSpeakingAction.StartRequested)
        vm.onAction(FullSentenceSpeakingAction.FinishedRequested)
        advanceUntilIdle()
        vm.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
        advanceUntilIdle()
        val nextRepository = FakeRepository()
        val restored = FullSentenceSpeakingViewModel(chapter, "s1", nextRepository, store)
        advanceUntilIdle()
        restored.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
        advanceUntilIdle()
        assertEquals(0, nextRepository.evaluations)
        assertEquals(listOf(SpeakingEvaluationDecision.PASS), nextRepository.savedDecisions)
    }

    @Test
    fun failedProgressWriteDoesNotConfirmTheNextRound() = runTest(dispatcher) {
        val store = MemoryProgress()
        val vm = FullSentenceSpeakingViewModel(chapter, "s1", FakeRepository(), store)
        advanceUntilIdle()
        vm.onAction(FullSentenceSpeakingAction.StartRequested)
        vm.onAction(FullSentenceSpeakingAction.FinishedRequested)
        advanceUntilIdle()
        store.writable = false
        vm.onAction(FullSentenceSpeakingAction.ContinueTrainingRequested)
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.attemptIndex)
        assertTrue(vm.uiState.value.progressFailed)
        store.writable = true
        vm.onAction(FullSentenceSpeakingAction.ContinueTrainingRequested)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.attemptIndex)
    }

    @Test
    fun queuedAutoSaveCannotRevertAnAcknowledgedTrainingRoundBeforeCatchingUp() = runTest(dispatcher) {
        // A Main.immediate mutex waiter can resume inside unlock, before the
        // transition's caller publishes its next UI state. Both I/O gates are real
        // suspension points; no private state/reflection or artificial thread is used.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = GatedNextRoundProgress()
        val repository = FakeRepository()
        val models = ViewModelStore()
        try {
            val vm = FullSentenceSpeakingViewModel(chapter, "s1", repository, store)
                .also { models.put("queued-round", it) }
            assertTrue("The initial ordinary auto-save must really own the save mutex", store.firstWriteStarted.isCompleted)
            assertFalse(vm.uiState.value.submitting)
            vm.onAction(FullSentenceSpeakingAction.StartRequested)
            vm.onAction(FullSentenceSpeakingAction.FinishedRequested)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.trainingCanContinue)
            assertEquals(listOf(SpeakingTrainingMode.BILINGUAL_FOLLOW), repository.trainingModes)

            // The collector is still saving the initial state. The latest training
            // feedback remains queued while the explicit next-round save waits.
            vm.onAction(FullSentenceSpeakingAction.ContinueTrainingRequested)
            assertTrue(vm.uiState.value.submitting)
            store.releaseFirstWrite.complete(Unit)
            advanceUntilIdle()
            assertTrue("Next-round persistence must acquire the lock before the queued feedback auto-save", store.transitionWriteStarted.isCompleted)
            val transition = store.transitionWriteStarted.await()
            assertEquals("1", transition["attemptIndex"])
            assertEquals("false", transition["trainingCanContinue"])

            store.releaseTransitionWrite.complete(Unit)
            advanceUntilIdle()
            val committedIndex = store.committed.indexOf(transition)
            assertTrue("The next round must actually be committed, not skipped", committedIndex >= 0)
            val laterWrites = store.committed.drop(committedIndex + 1)
            assertTrue("An acknowledged round cannot be replaced by the previous round, even transiently: $laterWrites",
                laterWrites.all { it["attemptIndex"] == "1" && it["trainingCanContinue"] == "false" })
            assertEquals(1, vm.uiState.value.attemptIndex)
            assertEquals("1", store.payload?.get("attemptIndex"))
            assertFalse(vm.uiState.value.progressFailed)
            assertEquals(1, repository.trainingModes.size)
            assertEquals(0, repository.evaluations)
            assertTrue(repository.savedDecisions.isEmpty())

            val freshRepository = FakeRepository()
            val restored = FullSentenceSpeakingViewModel(chapter, "s1", freshRepository, store)
                .also { models.put("restored-round", it) }
            advanceUntilIdle()
            assertEquals(1, restored.uiState.value.attemptIndex)
            assertFalse(restored.uiState.value.trainingCanContinue)
            assertTrue(freshRepository.trainingModes.isEmpty())
            assertEquals(0, freshRepository.evaluations)
            assertTrue(freshRepository.savedDecisions.isEmpty())
        } finally {
            store.releaseFirstWrite.complete(Unit)
            store.releaseTransitionWrite.complete(Unit)
            models.clear()
        }
    }

    @Test
    fun rapidFinishAndConfirmationDoNotDuplicatePaidRequests() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = createViewModel(repository)
        viewModel.onAction(FullSentenceSpeakingAction.StartRequested)
        viewModel.onAction(FullSentenceSpeakingAction.FinishedRequested)
        viewModel.onAction(FullSentenceSpeakingAction.FinishedRequested)
        advanceUntilIdle()
        assertEquals(1, repository.trainingModes.size)
        val final = atFinalAttempt(repository)
        final.onAction(FullSentenceSpeakingAction.StartRequested)
        final.onAction(FullSentenceSpeakingAction.FinishedRequested)
        advanceUntilIdle()
        final.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
        final.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
        final.onAction(FullSentenceSpeakingAction.TranscriptChanged("late edit"))
        advanceUntilIdle()
        assertEquals(1, repository.evaluations)
        assertEquals("recognized transcript", final.uiState.value.transcript)
    }

    @Test
    fun savedPassCanRetryFailedCheckpointWithoutRepeatingEvaluationOrResultSave() = runTest(dispatcher) {
        val progress = MemoryProgress()
        val repository = FakeRepository()
        val vm = FullSentenceSpeakingViewModel(chapter, "s1", repository, progress)
        advanceUntilIdle()
        repeat(4) {
            vm.onAction(FullSentenceSpeakingAction.StartRequested)
            vm.onAction(FullSentenceSpeakingAction.FinishedRequested)
            advanceUntilIdle()
            vm.onAction(FullSentenceSpeakingAction.ContinueTrainingRequested)
            advanceUntilIdle()
        }
        vm.onAction(FullSentenceSpeakingAction.StartRequested)
        vm.onAction(FullSentenceSpeakingAction.FinishedRequested)
        advanceUntilIdle()
        progress.rejectFinalDecision = true
        val next = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { vm.effects.first() }
        vm.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
        advanceUntilIdle()
        assertEquals(listOf(SpeakingEvaluationDecision.PASS), repository.savedDecisions)
        assertTrue(vm.uiState.value.progressFailed)
        assertEquals(SpeakingEvaluationDecision.PASS, vm.uiState.value.finalDecision)
        assertFalse(next.isCompleted)

        progress.rejectFinalDecision = false
        vm.onAction(FullSentenceSpeakingAction.RetryRequested)
        advanceUntilIdle()
        assertEquals("PASS", progress.payload?.get("finalDecision"))
        assertEquals(SpeakingEvaluationDecision.PASS, vm.uiState.value.finalDecision)
        assertFalse(vm.uiState.value.progressFailed)
        assertTrue("Retry must deliver the already saved completion", next.isCompleted)
        assertEquals(FullSentenceSpeakingEffect.OpenPatternSpeaking, next.await())
        assertEquals(1, repository.evaluations)
        assertEquals(listOf(SpeakingEvaluationDecision.PASS), repository.savedDecisions)
    }

    @Test
    fun repeatedCheckpointRetryRemainsBlockedUntilSavedThenEmitsOnlyOneCompletion() = runTest(dispatcher) {
        val progress = MemoryProgress()
        val repository = FakeRepository()
        val vm = FullSentenceSpeakingViewModel(chapter, "s1", repository, progress)
        advanceUntilIdle()
        repeat(4) {
            vm.onAction(FullSentenceSpeakingAction.StartRequested)
            vm.onAction(FullSentenceSpeakingAction.FinishedRequested)
            advanceUntilIdle()
            vm.onAction(FullSentenceSpeakingAction.ContinueTrainingRequested)
            advanceUntilIdle()
        }
        vm.onAction(FullSentenceSpeakingAction.StartRequested)
        vm.onAction(FullSentenceSpeakingAction.FinishedRequested)
        advanceUntilIdle()
        progress.rejectFinalDecision = true
        val next = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { vm.effects.first() }
        vm.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
        advanceUntilIdle()
        vm.onAction(FullSentenceSpeakingAction.RetryRequested)
        vm.onAction(FullSentenceSpeakingAction.RetryRequested)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.progressFailed)
        assertEquals(SpeakingEvaluationDecision.PASS, vm.uiState.value.finalDecision)
        assertFalse(next.isCompleted)
        progress.rejectFinalDecision = false
        vm.onAction(FullSentenceSpeakingAction.RetryRequested)
        vm.onAction(FullSentenceSpeakingAction.RetryRequested)
        advanceUntilIdle()
        assertTrue(next.isCompleted)
        assertEquals(FullSentenceSpeakingEffect.OpenPatternSpeaking, next.await())
        val duplicate = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { vm.effects.first() }
        advanceUntilIdle()
        assertFalse("Rapid retry must not skip another sentence", duplicate.isCompleted)
        assertEquals(1, repository.evaluations)
        assertEquals(listOf(SpeakingEvaluationDecision.PASS), repository.savedDecisions)
    }

    @Test
    fun retryAfterHearingAnswerCannotBecomeAnIndependentPass() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = atFinalAttempt(repository)
        viewModel.onAction(FullSentenceSpeakingAction.AnswerAudioRequested)
        viewModel.onAction(FullSentenceSpeakingAction.StartRequested)
        viewModel.onAction(FullSentenceSpeakingAction.FinishedRequested)
        advanceUntilIdle()
        viewModel.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
        advanceUntilIdle()
        assertEquals(SpeakingEvaluationDecision.FAIL, viewModel.uiState.value.finalDecision)
        viewModel.onAction(FullSentenceSpeakingAction.RetryRequested)
        assertTrue(viewModel.uiState.value.answerAudioPlayed)
        viewModel.onAction(FullSentenceSpeakingAction.StartRequested)
        viewModel.onAction(FullSentenceSpeakingAction.FinishedRequested)
        advanceUntilIdle()
        viewModel.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
        advanceUntilIdle()
        assertEquals(SpeakingEvaluationDecision.FAIL, viewModel.uiState.value.finalDecision)
    }

    @Test
    fun retryingFailedLocalDecisionSaveDoesNotEvaluateAudioAgain() = runTest(dispatcher) {
        val repository = FakeRepository(saveResult = SpeakingDecisionSaveResult.Failed)
        val viewModel = atFinalAttempt(repository)
        viewModel.onAction(FullSentenceSpeakingAction.StartRequested)
        viewModel.onAction(FullSentenceSpeakingAction.FinishedRequested)
        advanceUntilIdle()
        viewModel.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
        advanceUntilIdle()
        repository.saveResult = SpeakingDecisionSaveResult.Saved
        viewModel.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
        advanceUntilIdle()
        assertEquals(1, repository.evaluations)
        assertEquals(SpeakingEvaluationDecision.PASS, viewModel.uiState.value.finalDecision)
    }

    @Test
    fun followsTwoBilingualThenReadsTwoEnglishWithoutMasteryDecision() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = createViewModel(repository)
        repeat(4) { attempt ->
            val before = viewModel.uiState.value
            assertEquals(attempt < 2, before.showChinese)
            assertTrue(before.showEnglish)
            viewModel.onAction(FullSentenceSpeakingAction.StartRequested)
            assertTrue(viewModel.uiState.value.recording)
            viewModel.onAction(FullSentenceSpeakingAction.FinishedRequested)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.finalDecision)
            assertTrue(viewModel.uiState.value.trainingCanContinue)
            viewModel.onAction(FullSentenceSpeakingAction.ContinueTrainingRequested)
        }
        assertTrue(viewModel.uiState.value.isIndependentRetelling)
        assertFalse(viewModel.uiState.value.showEnglish)
        assertTrue(viewModel.uiState.value.showChinese)
        assertEquals(
            listOf(
                SpeakingTrainingMode.BILINGUAL_FOLLOW,
                SpeakingTrainingMode.BILINGUAL_FOLLOW,
                SpeakingTrainingMode.ENGLISH_READING,
                SpeakingTrainingMode.ENGLISH_READING,
            ),
            repository.trainingModes,
        )
    }

    @Test
    fun fifthAttemptUsesEditedTranscriptAndEligiblePassContinues() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = atFinalAttempt(repository)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(FullSentenceSpeakingAction.StartRequested)
        viewModel.onAction(FullSentenceSpeakingAction.FinishedRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.transcriptReadyForConfirmation)
        viewModel.onAction(FullSentenceSpeakingAction.TranscriptChanged(" edited transcript "))
        viewModel.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
        advanceUntilIdle()
        assertEquals("edited transcript", repository.lastTranscript)
        assertEquals(listOf(SpeakingEvaluationDecision.PASS), repository.savedDecisions)
        assertEquals(SpeakingEvaluationDecision.PASS, viewModel.uiState.value.finalDecision)
        assertEquals(FullSentenceSpeakingEffect.OpenPatternSpeaking, effect.await())
    }

    @Test
    fun playingAnswerOnFifthAttemptMakesPassCandidateFail() = runTest(dispatcher) {
        val viewModel = atFinalAttempt(FakeRepository())
        viewModel.onAction(FullSentenceSpeakingAction.AnswerAudioRequested)
        viewModel.onAction(FullSentenceSpeakingAction.StartRequested)
        viewModel.onAction(FullSentenceSpeakingAction.FinishedRequested)
        advanceUntilIdle()
        viewModel.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.answerAudioPlayed)
        assertEquals(SpeakingEvaluationDecision.FAIL, viewModel.uiState.value.finalDecision)
    }

    @Test
    fun thresholdAndCoreMeaningAreEnforcedLocally() = runTest(dispatcher) {
        val repository = FakeRepository(
            candidate = passingCandidate.copy(completenessScore = 79),
        )
        val viewModel = atFinalAttempt(repository)
        viewModel.onAction(FullSentenceSpeakingAction.StartRequested)
        viewModel.onAction(FullSentenceSpeakingAction.FinishedRequested)
        advanceUntilIdle()
        viewModel.onAction(FullSentenceSpeakingAction.TranscriptChanged("almost complete"))
        viewModel.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
        advanceUntilIdle()
        assertEquals(SpeakingEvaluationDecision.FAIL, viewModel.uiState.value.finalDecision)
    }

    @Test
    fun inconclusiveAndBlankTranscriptNeverCreateAValidResult() = runTest(dispatcher) {
        val repository = FakeRepository(
            candidate = passingCandidate.copy(decision = SpeakingEvaluationDecision.INCONCLUSIVE),
            transcript = "",
        )
        val viewModel = atFinalAttempt(repository)
        viewModel.onAction(FullSentenceSpeakingAction.StartRequested)
        viewModel.onAction(FullSentenceSpeakingAction.FinishedRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.transcriptReadyForConfirmation)
        assertNull(viewModel.uiState.value.finalDecision)
        viewModel.onAction(FullSentenceSpeakingAction.TranscriptChanged("unclear capture"))
        viewModel.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
        advanceUntilIdle()
        assertEquals(SpeakingEvaluationDecision.INCONCLUSIVE, viewModel.uiState.value.finalDecision)
    }

    @Test
    fun failedUserCanRetryOrArchiveAndContinue() = runTest(dispatcher) {
        val repository = FakeRepository(
            candidate = passingCandidate.copy(decision = SpeakingEvaluationDecision.FAIL),
        )
        val viewModel = atFinalAttempt(repository)
        viewModel.onAction(FullSentenceSpeakingAction.StartRequested)
        viewModel.onAction(FullSentenceSpeakingAction.FinishedRequested)
        advanceUntilIdle()
        viewModel.onAction(FullSentenceSpeakingAction.TranscriptChanged("wrong"))
        viewModel.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
        advanceUntilIdle()
        viewModel.onAction(FullSentenceSpeakingAction.RetryRequested)
        assertNull(viewModel.uiState.value.finalDecision)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(FullSentenceSpeakingAction.StopPractisingRequested)
        advanceUntilIdle()
        assertEquals(FullSentenceSpeakingEffect.ArchiveWrongAndContinue, effect.await())
    }

    @Test
    fun localDraftMustSaveBeforePassCanNavigate() = runTest(dispatcher) {
        val repository = FakeRepository(saveResult = SpeakingDecisionSaveResult.Failed)
        val viewModel = atFinalAttempt(repository)
        viewModel.onAction(FullSentenceSpeakingAction.StartRequested)
        viewModel.onAction(FullSentenceSpeakingAction.FinishedRequested)
        advanceUntilIdle()
        viewModel.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.captureFailed)
        assertNull(viewModel.uiState.value.finalDecision)
    }

    private fun createViewModel(repository: FakeRepository) = FullSentenceSpeakingViewModel(
        chapter = chapter,
        currentFocusSentenceId = "s1",
        repository = repository,
    )

    private fun TestScope.atFinalAttempt(
        repository: FakeRepository,
    ): FullSentenceSpeakingViewModel {
        val viewModel = createViewModel(repository)
        repeat(4) {
            viewModel.onAction(FullSentenceSpeakingAction.StartRequested)
            viewModel.onAction(FullSentenceSpeakingAction.FinishedRequested)
            advanceUntilIdle()
            viewModel.onAction(FullSentenceSpeakingAction.ContinueTrainingRequested)
        }
        return viewModel
    }

    private class FakeRepository(
        private val candidate: SpeakingEvaluationCandidate = passingCandidate,
        private val transcript: String = "recognized transcript",
        var saveResult: SpeakingDecisionSaveResult = SpeakingDecisionSaveResult.Saved,
        val evaluationFails: Boolean = false,
    ) : FullSentenceSpeakingRepository {
        val trainingModes = mutableListOf<SpeakingTrainingMode>()
        var lastTranscript: String? = null
        var evaluations = 0
        val savedDecisions = mutableListOf<SpeakingEvaluationDecision>()

        override fun startCapture(): Boolean = true

        override fun cancelCapture() = Unit

        override suspend fun trainingFeedback(
            sentenceId: String,
            mode: SpeakingTrainingMode,
        ): ReadingFeedbackResult {
            trainingModes += mode
            return ReadingFeedbackResult.Ready(
                ReadingFeedback("内容完整，关键音和节奏清楚。", ReadingFeedbackAction.CONTINUE),
            )
        }

        override suspend fun evaluateIndependentRetelling(
            sentenceId: String,
            confirmedTranscript: String,
            answerAudioPlayed: Boolean,
        ): SpeakingEvaluationResult {
            evaluations++
            lastTranscript = confirmedTranscript
            if (evaluationFails) return SpeakingEvaluationResult.Failed
            return SpeakingEvaluationResult.Ready(candidate)
        }

        override suspend fun transcribeIndependentRetelling(
            sentenceId: String,
        ): SpeakingTranscriptionResult = SpeakingTranscriptionResult.Ready(
            SpeakingTranscription(transcript, emptyList(), 0.9),
        )

        override suspend fun saveIndependentDecision(
            sentenceId: String,
            decision: SpeakingEvaluationDecision,
        ): SpeakingDecisionSaveResult {
            savedDecisions += decision
            return saveResult
        }
    }

    private class GatedNextRoundProgress : LearningProgressStore {
        var payload: Map<String, String>? = null
        private var writes = 0
        val committed = mutableListOf<Map<String, String>>()
        val firstWriteStarted = CompletableDeferred<Unit>()
        val transitionWriteStarted = CompletableDeferred<Map<String, String>>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val releaseTransitionWrite = CompletableDeferred<Unit>()
        override suspend fun read() = payload?.toMap()
        override suspend fun write(values: Map<String, String>): Boolean {
            val snapshot = values.toMap()
            when (++writes) {
                1 -> { firstWriteStarted.complete(Unit); releaseFirstWrite.await() }
                2 -> { transitionWriteStarted.complete(snapshot); releaseTransitionWrite.await() }
            }
            payload = snapshot
            committed.add(snapshot)
            return true
        }
    }

    private class MemoryProgress : LearningProgressStore {
        var payload: Map<String, String>? = null
        var writable = true
        var rejectFinalDecision = false
        override suspend fun read() = payload
        override suspend fun write(values: Map<String, String>): Boolean {
            if (!writable || (rejectFinalDecision && values["finalDecision"] == "PASS")) return false
            payload = values.toMap()
            return true
        }
    }

    companion object {
        private val passingCandidate = SpeakingEvaluationCandidate(
            decision = SpeakingEvaluationDecision.PASS,
            completenessScore = 90,
            coreMeaningRecognizable = true,
            pronunciationIntelligible = true,
            feedbackZh = "内容完整且发音清楚。",
        )
    }
}
