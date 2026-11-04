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

/** VM-only technical retry ownership; fake repository flags attest its retained attempt.
 * The second-task/round checkpoint is fixture input, not prior learning, SQL, HTTP or AI evidence.
 * Accepted-candidate local-save retries remain covered by SceneResponseViewModelTest. */
@OptIn(ExperimentalCoroutinesApi::class)
class SceneTechnicalRetryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    @Before fun prepare() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() {
        owners.forEach(ViewModelStore::clear)
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun unknownTranscriptionRetriesTheSameTaskWithoutAnotherCaptureOrAutomaticConfirmation() = runTest(dispatcher) {
        val (vm, repository) = failedAttempt(Stage.TRANSCRIPTION, Outcome.UNKNOWN)
        assertTrue(vm.uiState.value.captureFailed)
        repository.transcriptionOutcome = Outcome.READY
        val gate = CompletableDeferred<Unit>().also { repository.gate = it }
        try {
            repeat(3) { vm.onAction(SceneResponseAction.RetryRequested) }
            assertTrue("The first explicit retry must synchronously occupy transcription", vm.uiState.value.transcribing)
            advanceUntilIdle()
            vm.onAction(SceneResponseAction.RetryRequested)
            vm.onAction(SceneResponseAction.StartRequested)
            vm.onAction(SceneResponseAction.FinishedRequested)
            advanceUntilIdle()
            assertEquals(listOf("t2", "t2"), repository.transcriptions)
            assertEquals(1, repository.starts)
            assertEquals(0, repository.cancels)
            assertTrue(repository.confirmed.isEmpty())
            assertFalse(vm.uiState.value.recording)
            assertPosition(vm, Stage.TRANSCRIPTION)
        } finally { gate.complete(Unit) }
        advanceUntilIdle()
        assertEquals(RAW_TRANSCRIPT, vm.uiState.value.transcript)
        assertTrue(vm.uiState.value.transcriptReady)
        assertFalse(vm.uiState.value.technicalRetryPending)
        assertFalse(vm.uiState.value.technicalRetryBlocked)
        assertTrue("Transcription success still requires the user's confirmation", repository.confirmed.isEmpty())
        assertEquals(0, repository.generations)
        assertEquals(0, repository.saves)
    }

    @Test fun unknownTrainingFeedbackRetriesItsExactTrimmedConfirmationAndRoundWithoutRecording() = runTest(dispatcher) {
        retryConfirmed(Stage.TRAINING)
    }

    @Test fun unknownFinalEvaluationRetriesItsExactTrimmedConfirmationWithoutRecordingOrAdvance() = runTest(dispatcher) {
        retryConfirmed(Stage.FINAL)
    }

    @Test fun terminalAttemptRejectsRetryWithoutLosingTextAndOnlyExplicitStartRecordsAgain() = runTest(dispatcher) {
        Stage.entries.forEach { stage ->
            val (vm, repository) = failedAttempt(stage, Outcome.TERMINAL)
            val transcript = vm.uiState.value.transcript
            val beforeTranscriptions = repository.transcriptions.toList()
            val beforeConfirmed = repository.confirmed.toList()
            assertTrue("Terminal response is not a retryable unknown failure", vm.uiState.value.technicalRetryBlocked)
            assertFalse(vm.uiState.value.technicalRetryPending)
            repeat(2) { vm.onAction(SceneResponseAction.RetryRequested) }
            vm.onAction(SceneResponseAction.TranscriptChanged("unconfirmed replacement"))
            advanceUntilIdle()
            assertTrue(vm.uiState.value.technicalRetryBlocked)
            assertEquals(transcript, vm.uiState.value.transcript)
            assertEquals(beforeTranscriptions, repository.transcriptions)
            assertEquals(beforeConfirmed, repository.confirmed)
            assertEquals(1, repository.starts)
            assertEquals(0, repository.cancels)
            assertPosition(vm, stage)
            vm.onAction(SceneResponseAction.StartRequested)
            assertEquals("Only this explicit action starts a new recording", 2, repository.starts)
            assertTrue(vm.uiState.value.recording)
            assertFalse(vm.uiState.value.technicalRetryPending)
            assertFalse(vm.uiState.value.technicalRetryBlocked)
            assertEquals(beforeTranscriptions, repository.transcriptions)
            assertEquals(beforeConfirmed, repository.confirmed)
            assertEquals(0, repository.generations)
            assertEquals(0, repository.saves)
        }
    }

    @Test fun deliveredInconclusiveKeepsTheExistingExplicitPracticeAgainFlowInsteadOfTechnicalReplay() = runTest(dispatcher) {
        listOf(Stage.TRAINING, Stage.FINAL).forEach { stage ->
            val (vm, repository) = failedAttempt(stage, Outcome.INCONCLUSIVE)
            val before = repository.confirmed.toList()
            assertFalse(vm.uiState.value.technicalRetryPending)
            assertFalse(vm.uiState.value.technicalRetryBlocked)
            if (stage == Stage.FINAL) assertEquals(SceneEvaluationDecision.INCONCLUSIVE, vm.uiState.value.finalDecision)
            else assertFalse(vm.uiState.value.trainingRoundCompleted)
            vm.onAction(SceneResponseAction.RetryRequested)
            advanceUntilIdle()
            assertEquals(before, repository.confirmed)
            assertEquals(listOf("t2"), repository.transcriptions)
            assertEquals(1, repository.starts)
            assertEquals("", vm.uiState.value.transcript)
            assertNull(vm.uiState.value.finalDecision)
            vm.onAction(SceneResponseAction.StartRequested)
            assertEquals(2, repository.starts)
            assertTrue(vm.uiState.value.recording)
            assertEquals(before, repository.confirmed)
            assertPosition(vm, stage)
            assertEquals(0, repository.saves)
        }
    }

    private suspend fun TestScope.retryConfirmed(stage: Stage) {
        val (vm, repository) = failedAttempt(stage, Outcome.UNKNOWN)
        val transcript = vm.uiState.value.transcript
        val exact = ConfirmedCall(stage, "t2", stage.round, CONFIRMED)
        assertEquals(listOf(exact), repository.confirmed)
        assertTrue(vm.uiState.value.technicalRetryPending)
        assertFalse(vm.uiState.value.technicalRetryBlocked)
        vm.onAction(SceneResponseAction.TranscriptChanged("unconfirmed replacement"))
        assertEquals("A pending attempt retains the user's already confirmed text", transcript, vm.uiState.value.transcript)
        repository.feedbackOutcome = Outcome.READY
        val gate = CompletableDeferred<Unit>().also { repository.gate = it }
        try {
            repeat(3) { vm.onAction(SceneResponseAction.RetryRequested) }
            assertTrue("One retry must occupy submission before dispatch", vm.uiState.value.submitting)
            advanceUntilIdle()
            vm.onAction(SceneResponseAction.TranscriptChanged("late unconfirmed replacement"))
            vm.onAction(SceneResponseAction.RetryRequested)
            vm.onAction(SceneResponseAction.ConfirmTranscriptRequested)
            vm.onAction(SceneResponseAction.StartRequested)
            vm.onAction(SceneResponseAction.ContinueRoundRequested)
            advanceUntilIdle()
            assertEquals(listOf(exact, exact), repository.confirmed)
            assertEquals(transcript, vm.uiState.value.transcript)
            assertEquals(listOf("t2"), repository.transcriptions)
            assertEquals(1, repository.starts)
            assertEquals(0, repository.cancels)
            assertFalse(vm.uiState.value.recording)
            assertPosition(vm, stage)
        } finally { gate.complete(Unit) }
        advanceUntilIdle()
        assertFalse(vm.uiState.value.technicalRetryPending)
        assertFalse(vm.uiState.value.technicalRetryBlocked)
        assertEquals(FEEDBACK, vm.uiState.value.feedbackZh)
        if (stage == Stage.TRAINING) assertTrue(vm.uiState.value.trainingRoundCompleted)
        else assertEquals(SceneEvaluationDecision.INCONCLUSIVE, vm.uiState.value.finalDecision)
        assertPosition(vm, stage)
        assertEquals(0, repository.generations)
        assertEquals("Inconclusive final evidence cannot create a formal result", 0, repository.saves)
    }

    private suspend fun TestScope.failedAttempt(stage: Stage, outcome: Outcome): Pair<SceneResponseViewModel, RetryRepository> {
        val repository = RetryRepository().apply {
            transcriptionOutcome = if (stage == Stage.TRANSCRIPTION) outcome else Outcome.READY
            feedbackOutcome = outcome
        }
        val progress = MemoryProgress(encodeSceneProgress(SceneResponseUiState(
            chapter, tasks, taskIndex = 1, roundIndex = stage.round, audioMuted = true), null))
        val vm = SceneResponseViewModel(chapter, tasks, repository, progress)
        owners += ViewModelStore().also { it.put("scene", vm) }
        advanceUntilIdle()
        assertPosition(vm, stage)
        vm.onAction(SceneResponseAction.StartRequested)
        vm.onAction(SceneResponseAction.FinishedRequested)
        advanceUntilIdle()
        if (stage != Stage.TRANSCRIPTION) {
            assertTrue(vm.uiState.value.transcriptReady)
            vm.onAction(SceneResponseAction.ConfirmTranscriptRequested)
            advanceUntilIdle()
        }
        assertFalse(vm.uiState.value.recording)
        assertFalse(vm.uiState.value.submitting)
        assertFalse(vm.uiState.value.transcribing)
        assertEquals(1, repository.starts)
        return vm to repository
    }
    private fun assertPosition(vm: SceneResponseViewModel, stage: Stage) {
        assertEquals(tasks, vm.uiState.value.tasks)
        assertEquals(tasks[1], vm.uiState.value.currentTask)
        assertEquals(1, vm.uiState.value.taskIndex)
        assertEquals(stage.round, vm.uiState.value.roundIndex)
        assertFalse(vm.uiState.value.completed)
    }
    private enum class Stage(val round: Int) { TRANSCRIPTION(1), TRAINING(1), FINAL(2) }
    private enum class Outcome { READY, UNKNOWN, TERMINAL, INCONCLUSIVE }
    private data class ConfirmedCall(val stage: Stage, val task: String, val round: Int, val transcript: String)
    private class RetryRepository : SceneResponseRepository {
        var starts = 0
        var cancels = 0
        var saves = 0
        var generations = 0
        var transcriptionOutcome = Outcome.READY
        var feedbackOutcome = Outcome.READY
        var gate: CompletableDeferred<Unit>? = null
        private var transcriptionPending = false
        private var evaluationPending = false
        private var blocked = false
        val transcriptions = mutableListOf<String>()
        val confirmed = mutableListOf<ConfirmedCall>()
        override suspend fun generateTasks(chapter: FixedLearningChapter): SceneTaskGenerationResult {
            generations++; return SceneTaskGenerationResult.Ready(tasks)
        }
        override fun startCapture(): Boolean {
            starts++; transcriptionPending = false; evaluationPending = false; blocked = false; return true
        }
        override fun cancelCapture() { cancels++; transcriptionPending = false; evaluationPending = false; blocked = false }
        override fun hasPendingTranscriptionRetry() = transcriptionPending
        override fun hasPendingEvaluationRetry() = evaluationPending
        override fun technicalRetryBlocked() = blocked
        override suspend fun transcribeResponse(taskId: String): SpeakingTranscriptionResult {
            transcriptions += taskId
            gate?.await()
            transcriptionPending = transcriptionOutcome == Outcome.UNKNOWN
            blocked = transcriptionOutcome == Outcome.TERMINAL
            return if (transcriptionOutcome == Outcome.READY) SpeakingTranscriptionResult.Ready(
                SpeakingTranscription(RAW_TRANSCRIPT, emptyList(), null)) else SpeakingTranscriptionResult.Failed
        }
        override suspend fun trainingFeedback(taskId: String, roundIndex: Int, confirmedTranscript: String): SceneTrainingFeedbackResult {
            confirmed += ConfirmedCall(Stage.TRAINING, taskId, roundIndex, confirmedTranscript)
            awaitFeedback()
            return when (feedbackOutcome) {
                Outcome.UNKNOWN, Outcome.TERMINAL -> SceneTrainingFeedbackResult.Failed
                Outcome.INCONCLUSIVE -> SceneTrainingFeedbackResult.Inconclusive
                Outcome.READY -> SceneTrainingFeedbackResult.Ready(FEEDBACK)
            }
        }
        override suspend fun evaluateFinalRound(taskId: String, confirmedTranscript: String): SceneEvaluationResult {
            confirmed += ConfirmedCall(Stage.FINAL, taskId, 2, confirmedTranscript)
            awaitFeedback()
            return if (feedbackOutcome in setOf(Outcome.UNKNOWN, Outcome.TERMINAL)) SceneEvaluationResult.Failed
            else SceneEvaluationResult.Ready(SceneEvaluationCandidate(SceneEvaluationDecision.INCONCLUSIVE, false, false, false, FEEDBACK))
        }
        private suspend fun awaitFeedback() {
            gate?.await()
            evaluationPending = feedbackOutcome == Outcome.UNKNOWN
            blocked = feedbackOutcome == Outcome.TERMINAL
        }
        override suspend fun saveFinalDecision(chapterId: String, sentenceId: String, decision: SceneEvaluationDecision): SceneResponseSaveResult {
            saves++; return SceneResponseSaveResult.Saved
        }
    }
    private class MemoryProgress(private var values: Map<String, String>) : LearningProgressStore {
        override suspend fun read() = values.toMap()
        override suspend fun write(values: Map<String, String>): Boolean { this.values = values.toMap(); return true }
    }
    private companion object {
        const val CONFIRMED = "I would like green tea."
        const val RAW_TRANSCRIPT = "  I would like green tea.  \n"
        const val FEEDBACK = "隔离场景反馈。"
        val chapter = FixedLearningChapter("c1", 1, listOf(
            FixedLearningSentence("s1", "I drink tea.", "我喝茶。", true),
            FixedLearningSentence("s2", CONFIRMED, "我想要绿茶。", true)))
        val tasks = chapter.sentences.mapIndexed { index, sentence -> FixedSceneResponseTask(
            "t${index + 1}", sentence.sentenceId, "Ordering a drink.",
            listOf(SceneDialogueLine("AI", "What would you like?")), sentence.textEn) }
    }
}
