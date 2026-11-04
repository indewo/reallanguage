package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.FixedSceneResponseTask
import com.reallanguage.app.feature.learning.domain.LearningProgressStore
import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.feature.learning.domain.PatternPracticeExample
import com.reallanguage.app.feature.learning.domain.PatternPracticeFeedback
import com.reallanguage.app.feature.learning.domain.PatternPracticeFeedbackResult
import com.reallanguage.app.feature.learning.domain.PatternPracticeGenerationResult
import com.reallanguage.app.feature.learning.domain.PatternPracticeSet
import com.reallanguage.app.feature.learning.domain.PatternSpeakingRepository
import com.reallanguage.app.feature.learning.domain.ReadSpeakCompletionSignal
import com.reallanguage.app.feature.learning.domain.ReadSpeakFeedbackResult
import com.reallanguage.app.feature.learning.domain.ReadSpeakRepository
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** VM state/cancellation boundary only: all repositories and progress are in memory; no audio or AI. */
@OptIn(ExperimentalCoroutinesApi::class)
class LearningLiveCaptureInterruptionTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() { stores.forEach { it.clear() }; Dispatchers.resetMain() }

    @Test fun readSpeakLiveInterruptionCancelsOnceAndRequiresExplicitRestart() = runTest(dispatcher) {
        val repo = ReadRepository()
        val vm = own(ReadSpeakViewModel(chapter, "s1", repo))
        vm.onAction(ReadSpeakAction.StartRequested)
        assertTrue(vm.uiState.value.listening)
        vm.interruptUnsubmittedCapture()
        vm.interruptUnsubmittedCapture()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.listening)
        assertTrue(vm.uiState.value.captureFailed)
        assertFalse(vm.uiState.value.completed)
        assertEquals(1, repo.cancels)
        vm.onAction(ReadSpeakAction.RecognitionCompleted)
        advanceUntilIdle()
        assertEquals(0, repo.calls)
        vm.onAction(ReadSpeakAction.RetryRequested)
        assertTrue(vm.uiState.value.listening)
        assertEquals(0, repo.calls)
    }

    @Test fun readSpeakSubmittedFeedbackAndFailedLocalSaveAreNeverCancelled() = runTest(dispatcher) {
        val response = CompletableDeferred<ReadSpeakFeedbackResult>()
        val repo = ReadRepository().apply { answer = { response.await() } }
        val progress = MemoryProgress()
        val vm = own(ReadSpeakViewModel(chapter, "s1", repo, progress))
        advanceUntilIdle()
        vm.onAction(ReadSpeakAction.StartRequested)
        vm.onAction(ReadSpeakAction.RecognitionCompleted)
        vm.interruptUnsubmittedCapture() // Already submitted, even before its coroutine is dispatched.
        runCurrent()
        assertTrue(vm.uiState.value.submitting)
        vm.interruptUnsubmittedCapture()
        assertEquals(0, repo.cancels)
        assertEquals(1, repo.calls)
        progress.writable = false
        response.complete(ReadSpeakFeedbackResult.Ready("已交付的朗读反馈。"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.completed)
        assertTrue(vm.uiState.value.progressFailed)
        val accepted = vm.uiState.value
        vm.interruptUnsubmittedCapture()
        advanceUntilIdle()
        assertEquals(accepted, vm.uiState.value)
        assertEquals(0, repo.cancels)
        progress.writable = true
        vm.onAction(ReadSpeakAction.ContinueRequested)
        advanceUntilIdle()
        assertEquals(1, repo.calls)
        assertEquals("true", progress.values?.get("completed"))
    }

    @Test fun patternReadingInterruptionClearsOnlyLiveCaptureAndPreservesItsPractice() = runTest(dispatcher) {
        val repo = PatternRepository()
        val vm = own(PatternSpeakingViewModel(chapter, "s1", listOf(pattern), repo))
        advanceUntilIdle()
        vm.onAction(PatternSpeakingAction.StartReadingRequested("e1"))
        assertTrue(vm.uiState.value.recording)
        vm.interruptUnsubmittedCapture()
        vm.interruptUnsubmittedCapture()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.recording)
        assertNull(vm.uiState.value.readingExampleId)
        assertTrue(vm.uiState.value.failureMessageVisible)
        assertEquals(listOf(example), vm.uiState.value.examples)
        assertTrue(vm.uiState.value.completedReadingExampleIds.isEmpty())
        assertEquals(1, repo.cancels)
        assertEquals(1, repo.starts)
        vm.onAction(PatternSpeakingAction.ReadingFinished("e1"))
        advanceUntilIdle()
        assertEquals(0, repo.feedbackCalls)
        vm.onAction(PatternSpeakingAction.StartReadingRequested("e1"))
        assertTrue(vm.uiState.value.recording)
        assertEquals(2, repo.starts)
        assertEquals(1, repo.generations)
    }

    @Test fun patternResponseInterruptionDoesNotRevokeEarlierReadingOrCompleteTheScene() = runTest(dispatcher) {
        val repo = PatternRepository()
        val vm = own(PatternSpeakingViewModel(chapter, "s1", listOf(pattern), repo))
        enterPatternResponses(vm)
        vm.onAction(PatternSpeakingAction.StartResponseRequested)
        assertTrue(vm.uiState.value.recording)
        vm.interruptUnsubmittedCapture()
        vm.interruptUnsubmittedCapture()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.recording)
        assertTrue(vm.uiState.value.failureMessageVisible)
        assertEquals(setOf("e1"), vm.uiState.value.completedReadingExampleIds)
        assertFalse(vm.uiState.value.currentResponseCompleted)
        assertTrue(vm.uiState.value.processedPatternIds.isEmpty())
        assertEquals(1, repo.cancels)
        assertEquals(2, repo.starts)
        vm.onAction(PatternSpeakingAction.FinishResponseRequested)
        advanceUntilIdle()
        assertEquals(1, repo.feedbackCalls)
        vm.onAction(PatternSpeakingAction.StartResponseRequested)
        assertTrue(vm.uiState.value.recording)
        assertEquals(3, repo.starts)
    }

    @Test fun patternSubmittedReadingOrResponseSurvivesInterruptionAndFailedProgressSave() = runTest(dispatcher) {
        for (responseMode in listOf(false, true)) {
            val repo = PatternRepository()
            val progress = MemoryProgress()
            val vm = own(PatternSpeakingViewModel(chapter, "s1", listOf(pattern), repo, progress))
            if (responseMode) enterPatternResponses(vm) else advanceUntilIdle()
            val response = CompletableDeferred<PatternPracticeFeedbackResult>()
            repo.answerFeedback = { response.await() }
            val previousCalls = repo.feedbackCalls
            vm.onAction(if (responseMode) PatternSpeakingAction.StartResponseRequested else PatternSpeakingAction.StartReadingRequested("e1"))
            vm.onAction(if (responseMode) PatternSpeakingAction.FinishResponseRequested else PatternSpeakingAction.ReadingFinished("e1"))
            vm.interruptUnsubmittedCapture()
            runCurrent()
            assertTrue(vm.uiState.value.submitting)
            vm.interruptUnsubmittedCapture()
            assertEquals(0, repo.cancels)
            progress.writable = false
            response.complete(patternFeedback)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.progressFailed)
            assertEquals("已交付的句式反馈。", vm.uiState.value.feedbackZh)
            if (responseMode) assertTrue(vm.uiState.value.currentResponseCompleted)
            else assertEquals(setOf("e1"), vm.uiState.value.completedReadingExampleIds)
            val accepted = vm.uiState.value
            val starts = repo.starts
            vm.interruptUnsubmittedCapture()
            advanceUntilIdle()
            assertEquals(accepted, vm.uiState.value)
            assertEquals(0, repo.cancels)
            assertEquals(starts, repo.starts)
            progress.writable = true
            vm.onAction(PatternSpeakingAction.RetryProgressRequested)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.progressFailed)
            assertEquals(previousCalls + 1, repo.feedbackCalls)
            assertEquals(1, repo.generations)
        }
    }

    @Test fun patternGenerationInFlightIsNotCancelledAsIfItWereLiveRecording() = runTest(dispatcher) {
        val response = CompletableDeferred<PatternPracticeGenerationResult>()
        val repo = PatternRepository().apply { answerGeneration = { response.await() } }
        val vm = own(PatternSpeakingViewModel(chapter, "s1", listOf(pattern), repo))
        runCurrent()
        assertTrue(vm.uiState.value.submitting)
        vm.interruptUnsubmittedCapture()
        vm.interruptUnsubmittedCapture()
        assertEquals(0, repo.cancels)
        response.complete(PatternPracticeGenerationResult.Ready(PatternPracticeSet("p1", listOf(example))))
        advanceUntilIdle()
        assertEquals(listOf(example), vm.uiState.value.examples)
        assertEquals(1, repo.generations)
        assertEquals(0, repo.starts)
    }

    @Test fun sceneLiveInterruptionStopsOnceWithoutSubmittingOrLosingTheCurrentTask() = runTest(dispatcher) {
        val repo = SceneRepository()
        val vm = own(SceneResponseViewModel(chapter, listOf(task), repo))
        vm.onAction(SceneResponseAction.StartRequested)
        assertTrue(vm.uiState.value.recording)
        vm.interruptUnsubmittedCapture()
        vm.interruptUnsubmittedCapture()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.recording)
        assertTrue(vm.uiState.value.captureFailed)
        assertEquals(task, vm.uiState.value.currentTask)
        assertEquals(0, vm.uiState.value.roundIndex)
        assertEquals(1, repo.cancels)
        assertEquals(1, repo.starts)
        vm.onAction(SceneResponseAction.FinishedRequested)
        advanceUntilIdle()
        assertEquals(0, repo.transcriptions)
        assertEquals(0, repo.trainingCalls + repo.evaluations)
        vm.onAction(SceneResponseAction.StartRequested)
        assertTrue(vm.uiState.value.recording)
        assertEquals(2, repo.starts)
    }

    @Test fun sceneTranscriptionConfirmationAndSubmittedTrainingKeepTheirOriginalEvidence() = runTest(dispatcher) {
        val transcription = CompletableDeferred<SpeakingTranscriptionResult>()
        val feedback = CompletableDeferred<SceneTrainingFeedbackResult>()
        val repo = SceneRepository().apply {
            answerTranscription = { transcription.await() }
            answerTraining = { feedback.await() }
        }
        val vm = own(SceneResponseViewModel(chapter, listOf(task), repo))
        vm.onAction(SceneResponseAction.StartRequested)
        vm.onAction(SceneResponseAction.FinishedRequested)
        vm.interruptUnsubmittedCapture()
        runCurrent()
        assertTrue(vm.uiState.value.transcribing)
        vm.interruptUnsubmittedCapture()
        assertEquals(0, repo.cancels)
        transcription.complete(transcribed)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.transcriptReady)
        val awaitingConfirmation = vm.uiState.value
        vm.interruptUnsubmittedCapture()
        advanceUntilIdle()
        assertEquals(awaitingConfirmation, vm.uiState.value)
        assertEquals(0, repo.trainingCalls)
        vm.onAction(SceneResponseAction.ConfirmTranscriptRequested)
        vm.interruptUnsubmittedCapture()
        runCurrent()
        assertTrue(vm.uiState.value.submitting)
        vm.interruptUnsubmittedCapture()
        feedback.complete(SceneTrainingFeedbackResult.Ready("已交付的场景反馈。"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.trainingRoundCompleted)
        assertEquals("已交付的场景反馈。", vm.uiState.value.feedbackZh)
        assertEquals(0, repo.cancels)
        assertEquals(1, repo.starts)
        assertEquals(1, repo.transcriptions)
        assertEquals(1, repo.trainingCalls)
    }

    @Test fun sceneAcceptedFinalCandidateKeepsFailedLocalSaveAndRetriesWithoutNewEvaluation() = runTest(dispatcher) {
        val repo = SceneRepository().apply { saveResult = SceneResponseSaveResult.Failed }
        val progress = MemoryProgress()
        val vm = own(SceneResponseViewModel(chapter, listOf(task), repo, progress))
        advanceUntilIdle()
        repeat(2) {
            completeSceneRound(vm)
            vm.onAction(SceneResponseAction.ContinueRoundRequested)
            advanceUntilIdle()
        }
        completeSceneRound(vm)
        assertEquals(SceneEvaluationDecision.PASS, vm.uiState.value.finalDecision)
        assertTrue(vm.uiState.value.saveFailed)
        val accepted = vm.uiState.value
        val savedCandidate = progress.values?.toMap()
        vm.interruptUnsubmittedCapture()
        vm.interruptUnsubmittedCapture()
        advanceUntilIdle()
        assertEquals(accepted, vm.uiState.value)
        assertEquals(savedCandidate, progress.values)
        assertEquals(0, repo.cancels)
        repo.saveResult = SceneResponseSaveResult.Saved
        vm.onAction(SceneResponseAction.RetryRequested)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.completed)
        assertEquals(3, repo.starts)
        assertEquals(3, repo.transcriptions)
        assertEquals(2, repo.trainingCalls)
        assertEquals(1, repo.evaluations)
        assertEquals(2, repo.saves)
    }

    private fun <T : ViewModel> own(vm: T): T = vm.also {
        stores += ViewModelStore().apply { put("isolated-vm", vm) }
    }
    private fun TestScope.enterPatternResponses(vm: PatternSpeakingViewModel) {
        advanceUntilIdle()
        vm.onAction(PatternSpeakingAction.StartReadingRequested("e1"))
        vm.onAction(PatternSpeakingAction.ReadingFinished("e1"))
        advanceUntilIdle()
        vm.onAction(PatternSpeakingAction.StartResponsesRequested)
        advanceUntilIdle()
        assertEquals(PatternSpeakingStage.RESPONDING, vm.uiState.value.stage)
    }
    private fun TestScope.completeSceneRound(vm: SceneResponseViewModel) {
        vm.onAction(SceneResponseAction.StartRequested)
        vm.onAction(SceneResponseAction.FinishedRequested)
        advanceUntilIdle()
        vm.onAction(SceneResponseAction.ConfirmTranscriptRequested)
        advanceUntilIdle()
    }
    private class MemoryProgress : LearningProgressStore {
        var values: Map<String, String>? = null
        var writable = true
        override suspend fun read() = values?.toMap()
        override suspend fun write(values: Map<String, String>): Boolean {
            if (!writable) return false
            this.values = values.toMap()
            return true
        }
    }
    private class ReadRepository : ReadSpeakRepository {
        var calls = 0
        var cancels = 0
        var answer: suspend () -> ReadSpeakFeedbackResult = { ReadSpeakFeedbackResult.Ready("已交付的朗读反馈。") }
        override fun cancelCapture() { cancels++ }
        override suspend fun feedback(sentenceId: String, completionSignal: ReadSpeakCompletionSignal): ReadSpeakFeedbackResult {
            calls++
            return answer()
        }
    }
    private class PatternRepository : PatternSpeakingRepository {
        var starts = 0
        var cancels = 0
        var generations = 0
        var feedbackCalls = 0
        var answerGeneration: suspend () -> PatternPracticeGenerationResult = {
            PatternPracticeGenerationResult.Ready(PatternPracticeSet("p1", listOf(example)))
        }
        var answerFeedback: suspend () -> PatternPracticeFeedbackResult = { patternFeedback }
        override fun startCapture(): Boolean { starts++; return true }
        override fun cancelCapture() { cancels++ }
        override suspend fun generate(pattern: LockedSpeakingPattern): PatternPracticeGenerationResult { generations++; return answerGeneration() }
        override suspend fun readingFeedback(patternId: String, exampleId: String): PatternPracticeFeedbackResult { feedbackCalls++; return answerFeedback() }
        override suspend fun responseFeedback(patternId: String, exampleId: String): PatternPracticeFeedbackResult { feedbackCalls++; return answerFeedback() }
    }
    private class SceneRepository : SceneResponseRepository {
        var starts = 0
        var cancels = 0
        var transcriptions = 0
        var trainingCalls = 0
        var evaluations = 0
        var saves = 0
        var saveResult: SceneResponseSaveResult = SceneResponseSaveResult.Saved
        var answerTranscription: suspend () -> SpeakingTranscriptionResult = { transcribed }
        var answerTraining: suspend () -> SceneTrainingFeedbackResult = { SceneTrainingFeedbackResult.Ready("场景训练已完成。") }
        override suspend fun generateTasks(chapter: FixedLearningChapter) = SceneTaskGenerationResult.Ready(listOf(task))
        override fun startCapture(): Boolean { starts++; return true }
        override fun cancelCapture() { cancels++ }
        override suspend fun transcribeResponse(taskId: String): SpeakingTranscriptionResult { transcriptions++; return answerTranscription() }
        override suspend fun trainingFeedback(taskId: String, roundIndex: Int, confirmedTranscript: String): SceneTrainingFeedbackResult { trainingCalls++; return answerTraining() }
        override suspend fun evaluateFinalRound(taskId: String, confirmedTranscript: String): SceneEvaluationResult {
            evaluations++
            return SceneEvaluationResult.Ready(SceneEvaluationCandidate(SceneEvaluationDecision.PASS, true, true, true, "完整且可理解。"))
        }
        override suspend fun saveFinalDecision(chapterId: String, sentenceId: String, decision: SceneEvaluationDecision): SceneResponseSaveResult { saves++; return saveResult }
    }
    private companion object {
        const val TEXT = "I wrote a diary entry."
        val chapter = FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", TEXT, "我写了一篇日记。", true)))
        val pattern = LockedSpeakingPattern("p1", "s1", "write + a diary entry", "写一篇日记")
        val example = PatternPracticeExample("e1", "聊晚上的活动。", "What did you do?", TEXT, "我写了一篇日记。")
        val patternFeedback = PatternPracticeFeedbackResult.Ready(PatternPracticeFeedback("已交付的句式反馈。", false, TEXT))
        val task = FixedSceneResponseTask("t1", "s1", "You are talking about your evening.", listOf(SceneDialogueLine("AI", "What did you do?")), TEXT)
        val transcribed = SpeakingTranscriptionResult.Ready(SpeakingTranscription(TEXT, emptyList(), 0.95))
    }
}
