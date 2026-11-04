package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.FixedSceneResponseTask
import com.reallanguage.app.feature.learning.domain.SceneDialogueLine
import com.reallanguage.app.feature.learning.domain.SceneEvaluationCandidate
import com.reallanguage.app.feature.learning.domain.SceneEvaluationDecision
import com.reallanguage.app.feature.learning.domain.SceneEvaluationResult
import com.reallanguage.app.feature.learning.domain.SceneResponseRepository
import com.reallanguage.app.feature.learning.domain.SceneResponseSaveResult
import com.reallanguage.app.feature.learning.domain.SceneTaskGenerationResult
import com.reallanguage.app.feature.learning.domain.SceneTrainingFeedbackResult
import com.reallanguage.app.feature.learning.domain.SpeakingTranscription
import com.reallanguage.app.feature.learning.domain.SpeakingTranscriptionResult
import com.reallanguage.app.feature.learning.domain.LearningProgressStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
class SceneResponseViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun acceptedEvaluationRestoresAndRetriesOnlyThePendingLocalSave() = runTest(dispatcher) {
        val store = MemoryProgress()
        val repository = FakeRepository(saveResult = SceneResponseSaveResult.Failed)
        val vm = SceneResponseViewModel(chapter, listOf(task), repository, store)
        advanceUntilIdle()
        repeat(2) {
            respond(vm, task.targetSentenceEn)
            vm.onAction(SceneResponseAction.ContinueRoundRequested)
            advanceUntilIdle()
        }
        respond(vm, task.targetSentenceEn)
        assertTrue(vm.uiState.value.saveFailed)
        val resumedRepository = FakeRepository()
        val resumed = SceneResponseViewModel(chapter, listOf(task), resumedRepository, store)
        advanceUntilIdle()
        assertEquals(SceneEvaluationDecision.PASS, resumed.uiState.value.finalDecision)
        assertTrue(resumed.uiState.value.saveFailed)
        val effect = async { resumed.effects.first() }
        resumed.onAction(SceneResponseAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(0, resumedRepository.evaluations)
        assertEquals(0, resumedRepository.transcriptions)
        assertEquals(1, resumedRepository.saves)
        assertEquals(SceneResponseEffect.OpenChapterResult, effect.await())
    }

    @Test fun savedTrainingRoundRestoresWithoutAnotherFeedbackRequest() = runTest(dispatcher) {
        val store = MemoryProgress()
        val vm = SceneResponseViewModel(chapter, listOf(task), FakeRepository(), store)
        advanceUntilIdle()
        respond(vm, task.targetSentenceEn)
        vm.onAction(SceneResponseAction.ContinueRoundRequested)
        advanceUntilIdle()
        val repository = FakeRepository()
        val restored = SceneResponseViewModel(chapter, listOf(task), repository, store)
        advanceUntilIdle()
        assertEquals(1, restored.uiState.value.roundIndex)
        assertEquals(0, repository.transcriptions)
        assertTrue(repository.trainingRounds.isEmpty())
    }

    @Test fun failedProgressReadDoesNotStartRequestsOrOverwriteLocalData() = runTest(dispatcher) {
        val store = MemoryProgress().apply { readable = false }
        val repository = FakeRepository()
        val vm = SceneResponseViewModel(chapter, null, repository, store)
        advanceUntilIdle()
        vm.onAction(SceneResponseAction.StartRequested)
        advanceUntilIdle()
        assertEquals(0, repository.generations)
        assertEquals(0, store.writes)
        assertFalse(vm.uiState.value.recording)
    }

    @Test fun failedProgressWriteDoesNotConfirmNextRound() = runTest(dispatcher) {
        val store = MemoryProgress()
        val vm = SceneResponseViewModel(chapter, listOf(task), FakeRepository(), store)
        advanceUntilIdle()
        respond(vm, task.targetSentenceEn)
        store.writable = false
        vm.onAction(SceneResponseAction.ContinueRoundRequested)
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.roundIndex)
        store.writable = true
        vm.onAction(SceneResponseAction.ContinueRoundRequested)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.roundIndex)
    }

    @Test fun retryAfterSavedPassPersistsTheNextTaskWithoutRepeatingItsEvaluation() = runTest(dispatcher) {
        val secondTask = task.copy(taskId = "t2", sentenceId = "s2",
            targetSentenceEn = chapter.sentences[1].textEn)
        val tasks = listOf(task, secondTask)
        val store = MemoryProgress()
        val repository = FakeRepository()
        val vm = SceneResponseViewModel(chapter, tasks, repository, store)
        advanceUntilIdle()
        repeat(2) {
            respond(vm, task.targetSentenceEn)
            vm.onAction(SceneResponseAction.ContinueRoundRequested)
            advanceUntilIdle()
        }
        store.rejectWrite = { it["taskIndex"] == "1" }
        respond(vm, task.targetSentenceEn)
        assertEquals(1, repository.saves)
        assertEquals(1, repository.evaluations)
        assertTrue(store.rejectedWrites > 0)
        assertTrue(vm.uiState.value.progressFailed)
        assertEquals(0, vm.uiState.value.taskIndex)
        assertEquals("0", store.values?.get("taskIndex"))
        assertEquals(SceneEvaluationDecision.PASS, vm.uiState.value.finalDecision)

        store.rejectWrite = { false }
        vm.onAction(SceneResponseAction.RetryRequested)
        vm.onAction(SceneResponseAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.taskIndex)
        assertEquals("s2", vm.uiState.value.currentFocusSentenceId)
        assertEquals(0, vm.uiState.value.roundIndex)
        assertNull(vm.uiState.value.finalDecision)
        assertFalse(vm.uiState.value.progressFailed)
        assertEquals("1", store.values?.get("taskIndex"))
        assertEquals("0", store.values?.get("roundIndex"))
        assertFalse(requireNotNull(store.values).containsKey("candidateDecision"))
        assertEquals(1, repository.saves)
        assertEquals(1, repository.evaluations)
        assertEquals(3, repository.transcriptions)

        val restoredRepository = FakeRepository()
        val freshReader = object : LearningProgressStore by store {}
        val restored = SceneResponseViewModel(chapter, tasks, restoredRepository, freshReader)
        advanceUntilIdle()
        assertEquals(1, restored.uiState.value.taskIndex)
        assertEquals(secondTask, restored.uiState.value.currentTask)
        assertEquals(0, restored.uiState.value.roundIndex)
        assertFalse(restored.uiState.value.progressFailed)
        assertEquals(0, restoredRepository.generations)
        assertEquals(0, restoredRepository.transcriptions)
        assertEquals(0, restoredRepository.evaluations)
        assertEquals(0, restoredRepository.saves)
    }

    @Test fun retryAfterSavedLastPassPersistsCompletionAndRestoresWithoutNewRequests() = runTest(dispatcher) {
        val store = MemoryProgress()
        val repository = FakeRepository()
        val vm = SceneResponseViewModel(chapter, listOf(task), repository, store)
        advanceUntilIdle()
        repeat(2) {
            respond(vm, task.targetSentenceEn)
            vm.onAction(SceneResponseAction.ContinueRoundRequested)
            advanceUntilIdle()
        }
        store.rejectWrite = { it["completed"] == "true" }
        val effect = async { vm.effects.first() }
        respond(vm, task.targetSentenceEn)
        assertEquals(1, repository.saves)
        assertEquals(1, repository.evaluations)
        assertTrue(store.rejectedWrites > 0)
        assertTrue(vm.uiState.value.progressFailed)
        assertFalse(vm.uiState.value.completed)
        assertEquals("false", store.values?.get("completed"))
        assertFalse(effect.isCompleted)

        store.rejectWrite = { false }
        vm.onAction(SceneResponseAction.RetryRequested)
        vm.onAction(SceneResponseAction.RetryRequested)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.completed)
        assertFalse(vm.uiState.value.progressFailed)
        assertEquals("true", store.values?.get("completed"))
        assertTrue(effect.isCompleted)
        assertEquals(SceneResponseEffect.OpenChapterResult, effect.await())
        assertEquals(1, repository.saves)
        assertEquals(1, repository.evaluations)
        assertEquals(3, repository.transcriptions)

        val restoredRepository = FakeRepository()
        val freshReader = object : LearningProgressStore by store {}
        val restored = SceneResponseViewModel(chapter, listOf(task), restoredRepository, freshReader)
        val restoredEffect = async { restored.effects.first() }
        advanceUntilIdle()
        assertTrue(restored.uiState.value.completed)
        assertFalse(restored.uiState.value.progressFailed)
        assertTrue(restoredEffect.isCompleted)
        assertEquals(SceneResponseEffect.OpenChapterResult, restoredEffect.await())
        assertEquals(0, restoredRepository.generations)
        assertEquals(0, restoredRepository.transcriptions)
        assertEquals(0, restoredRepository.evaluations)
        assertEquals(0, restoredRepository.saves)
    }

    @Test fun rapidFinishAndConfirmCannotDuplicateRequestsOrResetPendingResult() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = createViewModel(listOf(task), repository)
        vm.onAction(SceneResponseAction.StartRequested)
        vm.onAction(SceneResponseAction.FinishedRequested)
        vm.onAction(SceneResponseAction.FinishedRequested)
        advanceUntilIdle()
        assertEquals(1, repository.transcriptions)
        vm.onAction(SceneResponseAction.ConfirmTranscriptRequested)
        vm.onAction(SceneResponseAction.ConfirmTranscriptRequested)
        vm.onAction(SceneResponseAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(listOf(0), repository.trainingRounds)
        assertTrue(vm.uiState.value.trainingRoundCompleted)
    }

    @Test fun finishAndConfirmExposeRealBusyStateUntilTheirRequestsComplete() = runTest(dispatcher) {
        val transcriptionGate = CompletableDeferred<Unit>()
        val evaluationGate = CompletableDeferred<Unit>()
        val repository = FakeRepository(
            transcriptionGate = transcriptionGate,
            trainingGate = evaluationGate,
        )
        val viewModel = createViewModel(listOf(task), repository)

        viewModel.onAction(SceneResponseAction.StartRequested)
        viewModel.onAction(SceneResponseAction.FinishedRequested)
        runCurrent()
        assertTrue("The page must show checking immediately after the user finishes", viewModel.uiState.value.transcribing)
        assertFalse(viewModel.uiState.value.transcriptReady)

        transcriptionGate.complete(Unit)
        runCurrent()
        assertFalse(viewModel.uiState.value.transcribing)
        assertTrue(viewModel.uiState.value.transcriptReady)

        viewModel.onAction(SceneResponseAction.ConfirmTranscriptRequested)
        runCurrent()
        assertTrue("The page must keep showing checking while feedback is evaluated", viewModel.uiState.value.submitting)
        evaluationGate.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.submitting)
        assertTrue(viewModel.uiState.value.trainingRoundCompleted)
    }

    @Test fun retryingSaveFailureReusesConfirmedEvaluationWithoutRerecording() = runTest(dispatcher) {
        val repository = FakeRepository(saveResult = SceneResponseSaveResult.Failed)
        val vm = atFinalRound(repository)
        respond(vm, task.targetSentenceEn)
        assertTrue(vm.uiState.value.saveFailed)
        repository.saveResult = SceneResponseSaveResult.Saved
        val effect = async { vm.effects.first() }
        vm.onAction(SceneResponseAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(2, repository.saves)
        assertEquals(1, repository.evaluations)
        assertEquals(SceneResponseEffect.OpenChapterResult, effect.await())
    }

    @Test fun stoppingOneFailedTaskStillContinuesRemainingFocusSentences() = runTest(dispatcher) {
        val repository = FakeRepository(finalCandidate = passingCandidate.copy(decision = SceneEvaluationDecision.FAIL))
        val vm = createViewModel(listOf(task, task.copy(taskId = "t2", sentenceId = "s2")), repository)
        advanceThroughTraining(vm)
        respond(vm, "wrong")
        vm.onAction(SceneResponseAction.StopPractisingRequested)
        vm.onAction(SceneResponseAction.StopPractisingRequested)
        advanceUntilIdle()
        assertEquals(1, repository.saves)
        assertEquals(1, vm.uiState.value.taskIndex)
        assertEquals("s2", vm.uiState.value.currentFocusSentenceId)
        assertEquals(0, vm.uiState.value.roundIndex)
    }

    @Test
    fun emptyTasksContinueToChapterResult() = runTest(dispatcher) {
        val viewModel = createViewModel(emptyList(), FakeRepository())
        assertEquals(SceneResponseEffect.OpenChapterResult, viewModel.effects.first())
    }

    @Test
    fun firstTwoRoundsOnlyReturnTrainingFeedback() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = createViewModel(listOf(task), repository)
        repeat(2) { round ->
            respond(viewModel, "I wrote a diary entry.")
            assertEquals(round, repository.trainingRounds.last())
            assertNull(viewModel.uiState.value.finalDecision)
            assertTrue(viewModel.uiState.value.trainingRoundCompleted)
            viewModel.onAction(SceneResponseAction.ContinueRoundRequested)
        }
        assertTrue(viewModel.uiState.value.isFinalRound)
    }

    @Test
    fun finalRoundPassRequiresCorrectTurnCompleteTargetAndIntelligibleAudio() = runTest(dispatcher) {
        val repository = FakeRepository(
            finalCandidate = passingCandidate.copy(correctTurn = false),
        )
        val viewModel = atFinalRound(repository)
        respond(viewModel, task.targetSentenceEn)
        assertEquals(SceneEvaluationDecision.FAIL, viewModel.uiState.value.finalDecision)
    }

    @Test
    fun validThirdRoundOnlyUpdatesSceneResponseAndOpensResult() = runTest(dispatcher) {
        val viewModel = atFinalRound(FakeRepository())
        val effect = async { viewModel.effects.first() }
        respond(viewModel, task.targetSentenceEn)
        advanceUntilIdle()
        assertEquals(SceneResponseEffect.OpenChapterResult, effect.await())
    }

    @Test
    fun inconclusiveAndBlankTranscriptNeverCreateFailure() = runTest(dispatcher) {
        val repository = FakeRepository(
            finalCandidate = passingCandidate.copy(
                decision = SceneEvaluationDecision.INCONCLUSIVE,
            ),
            transcript = "",
        )
        val viewModel = atFinalRound(repository)
        viewModel.onAction(SceneResponseAction.StartRequested)
        viewModel.onAction(SceneResponseAction.FinishedRequested)
        advanceUntilIdle()
        viewModel.onAction(SceneResponseAction.ConfirmTranscriptRequested)
        assertTrue(viewModel.uiState.value.captureFailed)
        assertNull(viewModel.uiState.value.finalDecision)
        repository.transcript = "unclear"
        viewModel.onAction(SceneResponseAction.RetryRequested)
        respond(viewModel, "unclear")
        assertEquals(SceneEvaluationDecision.INCONCLUSIVE, viewModel.uiState.value.finalDecision)
    }

    @Test
    fun finalFailureCanRetryOrArchiveAndContinue() = runTest(dispatcher) {
        val repository = FakeRepository(
            finalCandidate = passingCandidate.copy(decision = SceneEvaluationDecision.FAIL),
        )
        val viewModel = atFinalRound(repository)
        respond(viewModel, "wrong response")
        viewModel.onAction(SceneResponseAction.RetryRequested)
        assertNull(viewModel.uiState.value.finalDecision)
        val effect = async { viewModel.effects.first() }
        respond(viewModel, "wrong response")
        viewModel.onAction(SceneResponseAction.StopPractisingRequested)
        advanceUntilIdle()
        assertEquals(SceneResponseEffect.ArchiveWrongAndContinue, effect.await())
    }

    @Test
    fun fixedTasksStayOrderedAcrossFocusSentences() = runTest(dispatcher) {
        val secondTask = task.copy(taskId = "t2", sentenceId = "s2")
        val viewModel = createViewModel(listOf(task, secondTask), FakeRepository())
        advanceThroughTraining(viewModel)
        respond(viewModel, task.targetSentenceEn)
        assertEquals(1, viewModel.uiState.value.taskIndex)
        assertEquals("s2", viewModel.uiState.value.currentFocusSentenceId)
        assertEquals(0, viewModel.uiState.value.roundIndex)
    }

    @Test
    fun backAudioAndFailedCaptureRemainLocal() = runTest(dispatcher) {
        val repository = FakeRepository(
            trainingResult = SceneTrainingFeedbackResult.Inconclusive,
        )
        val viewModel = createViewModel(listOf(task), repository)
        viewModel.onAction(SceneResponseAction.AudioToggled)
        assertTrue(viewModel.uiState.value.audioMuted)
        respond(viewModel, "unclear")
        assertTrue(viewModel.uiState.value.captureFailed)
        assertFalse(viewModel.uiState.value.trainingRoundCompleted)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(SceneResponseAction.BackRequested)
        advanceUntilIdle()
        assertEquals(SceneResponseEffect.ReturnToCompleteWriting, effect.await())
    }

    @Test
    fun productionLoadAndCaptureFailuresStayOnCurrentTaskAndCanRetry() = runTest(dispatcher) {
        val repository = FakeRepository(
            generationResult = SceneTaskGenerationResult.Failed,
            captureStarts = false,
        )
        val viewModel = SceneResponseViewModel(chapter, null, repository)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.captureFailed)
        assertTrue(viewModel.uiState.value.tasks.isEmpty())

        repository.generationResult = SceneTaskGenerationResult.Ready(listOf(task))
        viewModel.onAction(SceneResponseAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(task, viewModel.uiState.value.currentTask)
        viewModel.onAction(SceneResponseAction.StartRequested)
        assertTrue(viewModel.uiState.value.captureFailed)
        assertFalse(viewModel.uiState.value.recording)
    }

    @Test
    fun finalDecisionMustBeSavedBeforeOpeningChapterResult() = runTest(dispatcher) {
        val repository = FakeRepository(saveResult = SceneResponseSaveResult.Failed)
        val viewModel = atFinalRound(repository)
        respond(viewModel, task.targetSentenceEn)
        assertTrue(viewModel.uiState.value.saveFailed)
        assertEquals(SceneEvaluationDecision.PASS, viewModel.uiState.value.finalDecision)
    }

    @Test
    fun outOfPhaseRecordingAndSubmissionActionsAreIgnored() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = createViewModel(listOf(task), repository)
        viewModel.onAction(SceneResponseAction.TranscriptChanged("too early"))
        viewModel.onAction(SceneResponseAction.FinishedRequested)
        viewModel.onAction(SceneResponseAction.ConfirmTranscriptRequested)
        viewModel.onAction(SceneResponseAction.ContinueRoundRequested)
        viewModel.onAction(SceneResponseAction.StopPractisingRequested)
        assertEquals("", viewModel.uiState.value.transcript)
        assertEquals(0, viewModel.uiState.value.roundIndex)

        viewModel.onAction(SceneResponseAction.StartRequested)
        viewModel.onAction(SceneResponseAction.StartRequested)
        assertTrue(viewModel.uiState.value.recording)
        viewModel.onAction(SceneResponseAction.FinishedRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.transcriptReady)
        viewModel.onAction(SceneResponseAction.StartRequested)
        assertFalse(viewModel.uiState.value.recording)

        val failing = FakeRepository(
            finalCandidate = passingCandidate.copy(decision = SceneEvaluationDecision.FAIL),
            saveResult = SceneResponseSaveResult.Failed,
        )
        val finalViewModel = atFinalRound(failing)
        respond(finalViewModel, "wrong")
        finalViewModel.onAction(SceneResponseAction.StartRequested)
        finalViewModel.onAction(SceneResponseAction.ContinueRoundRequested)
        finalViewModel.onAction(SceneResponseAction.StopPractisingRequested)
        advanceUntilIdle()
        assertTrue(finalViewModel.uiState.value.saveFailed)
        assertFalse(finalViewModel.uiState.value.recording)
    }

    private suspend fun TestScope.atFinalRound(
        repository: FakeRepository,
    ): SceneResponseViewModel {
        val viewModel = createViewModel(listOf(task), repository)
        advanceThroughTraining(viewModel)
        return viewModel
    }

    private suspend fun TestScope.advanceThroughTraining(viewModel: SceneResponseViewModel) {
        repeat(2) {
            respond(viewModel, task.targetSentenceEn)
            viewModel.onAction(SceneResponseAction.ContinueRoundRequested)
        }
    }

    private suspend fun TestScope.respond(
        viewModel: SceneResponseViewModel,
        transcript: String,
    ) {
        viewModel.onAction(SceneResponseAction.StartRequested)
        viewModel.onAction(SceneResponseAction.FinishedRequested)
        advanceUntilIdle()
        viewModel.onAction(SceneResponseAction.TranscriptChanged(transcript))
        viewModel.onAction(SceneResponseAction.ConfirmTranscriptRequested)
        advanceUntilIdle()
    }

    private fun createViewModel(
        tasks: List<FixedSceneResponseTask>,
        repository: FakeRepository,
    ) = SceneResponseViewModel(chapter, tasks, repository)

    private class FakeRepository(
        var trainingResult: SceneTrainingFeedbackResult =
            SceneTrainingFeedbackResult.Ready("回应内容和发音清楚。"),
        var finalCandidate: SceneEvaluationCandidate = passingCandidate,
        var transcript: String = task.targetSentenceEn,
        var generationResult: SceneTaskGenerationResult =
            SceneTaskGenerationResult.Ready(listOf(task)),
        var captureStarts: Boolean = true,
        var saveResult: SceneResponseSaveResult = SceneResponseSaveResult.Saved,
        private val transcriptionGate: CompletableDeferred<Unit>? = null,
        private val trainingGate: CompletableDeferred<Unit>? = null,
    ) : SceneResponseRepository {
        val trainingRounds = mutableListOf<Int>()
        var transcriptions = 0
        var evaluations = 0
        var saves = 0
        var generations = 0

        override suspend fun generateTasks(chapter: FixedLearningChapter): SceneTaskGenerationResult { generations++; return generationResult }

        override fun startCapture() = captureStarts
        override fun cancelCapture() = Unit
        override suspend fun transcribeResponse(taskId: String): SpeakingTranscriptionResult {
            transcriptions++
            transcriptionGate?.await()
            return SpeakingTranscriptionResult.Ready(
                SpeakingTranscription(transcript, emptyList(), 0.95),
            )
        }

        override suspend fun trainingFeedback(
            taskId: String,
            roundIndex: Int,
            confirmedTranscript: String,
        ): SceneTrainingFeedbackResult {
            trainingRounds += roundIndex
            trainingGate?.await()
            return trainingResult
        }

        override suspend fun evaluateFinalRound(
            taskId: String,
            confirmedTranscript: String,
        ): SceneEvaluationResult { evaluations++; return SceneEvaluationResult.Ready(finalCandidate) }

        override suspend fun saveFinalDecision(
            chapterId: String,
            sentenceId: String,
            decision: SceneEvaluationDecision,
        ): SceneResponseSaveResult { saves++; return saveResult }
    }

    companion object {
        private val chapter = FixedLearningChapter(
            "c1",
            1,
            listOf(
                FixedLearningSentence("s1", "I wrote a diary entry.", "我写了一篇日记。", true),
                FixedLearningSentence("s2", "I felt more confident.", "我更有信心了。", true),
            ),
        )
        private val task = FixedSceneResponseTask(
            taskId = "t1",
            sentenceId = "s1",
            backgroundEn = "You are talking about your evening.",
            contextLines = listOf(
                SceneDialogueLine("AI", "What did you do after dinner?"),
            ),
            targetSentenceEn = "I wrote a diary entry.",
        )
        private val passingCandidate = SceneEvaluationCandidate(
            decision = SceneEvaluationDecision.PASS,
            correctTurn = true,
            targetSentenceComplete = true,
            pronunciationIntelligible = true,
            feedbackZh = "在正确位置说出了完整目标句，发音可理解。",
        )
    }

    private class MemoryProgress : LearningProgressStore {
        var values: Map<String, String>? = null
        var readable = true
        var writable = true
        var writes = 0
        var rejectedWrites = 0
        var rejectWrite: (Map<String, String>) -> Boolean = { false }
        override suspend fun read(): Map<String, String>? { check(readable); return values }
        override suspend fun write(values: Map<String, String>): Boolean {
            writes++
            if (!writable || rejectWrite(values)) { rejectedWrites++; return false }
            this.values = values.toMap(); return true
        }
    }
}
