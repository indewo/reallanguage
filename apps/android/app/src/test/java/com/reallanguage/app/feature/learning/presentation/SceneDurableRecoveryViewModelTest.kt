package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModelStore
import com.reallanguage.app.feature.learning.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SceneDurableRecoveryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    @Before fun prepare() = Dispatchers.setMain(dispatcher)
    @After fun finish() { owners.forEach(ViewModelStore::clear); Dispatchers.resetMain() }

    @Test fun completedReentryIsReadOnlyAndRetainsTheSavedRecordingIdentity() = runTest(dispatcher) {
        val repository = Repository(SceneResponseRecovery.Unavailable)
        val saved = encodeSceneProgress(SceneResponseUiState(chapter, listOf(task), loading = false,
            roundIndex = 2, completed = true, finalDecision = SceneEvaluationDecision.PASS), PASS, "recording")
        val writes = mutableListOf<Map<String, String>>()
        val vm = model(repository, round = 2, extra = saved, onWrite = { writes += it })
        runCurrent()
        assertTrue(vm.uiState.value.completed)
        assertFalse(vm.uiState.value.progressFailed)
        assertTrue(repository.restoredRounds.isEmpty())
        assertTrue("Read-only completed reentry must not rewrite its saved identity", writes.isEmpty())
        assertEquals(0, repository.saves)
    }

    @Test fun pendingTranscriptionReopensWithoutNewCaptureOrAutomaticRequest() = runTest(dispatcher) {
        val repository = Repository(SceneResponseRecovery.TranscriptionPending)
        val vm = model(repository)
        runCurrent()
        assertTrue(vm.uiState.value.technicalRetryPending)
        assertFalse(vm.uiState.value.needsNewRecording)
        assertEquals(0, repository.starts)
        assertEquals(0, repository.transcriptions)
        vm.onAction(SceneResponseAction.RetryRequested)
        runCurrent()
        assertEquals(1, repository.transcriptions)
        assertEquals(0, repository.starts)
        assertTrue(vm.uiState.value.transcriptReady)
    }

    @Test fun unknownFinalEvaluationLocksItsConfirmedTextAndRetriesOnlyThatRequest() = runTest(dispatcher) {
        val repository = Repository(SceneResponseRecovery.FeedbackPending("already confirmed"))
        val vm = model(repository, round = 2)
        runCurrent()
        assertTrue(vm.uiState.value.technicalRetryPending)
        assertEquals("already confirmed", vm.uiState.value.transcript)
        vm.onAction(SceneResponseAction.TranscriptChanged("wrong replacement"))
        assertEquals("already confirmed", vm.uiState.value.transcript)
        assertEquals(0, repository.evaluations)
        vm.onAction(SceneResponseAction.RetryRequested)
        runCurrent()
        assertEquals(1, repository.evaluations)
        assertEquals(listOf("already confirmed"), repository.evaluatedTexts)
        assertEquals(0, repository.transcriptions)
        assertEquals(0, repository.starts)
    }

    @Test fun deliveredTrainingRestoresWithoutAnotherPaidRequestAndBindsNextRoundAfterSaving() = runTest(dispatcher) {
        val repository = Repository(SceneResponseRecovery.TrainingReady(SceneTrainingFeedbackResult.Ready("已保存反馈"), "confirmed"))
        val vm = model(repository)
        runCurrent()
        assertTrue(vm.uiState.value.trainingRoundCompleted)
        assertEquals("已保存反馈", vm.uiState.value.feedbackZh)
        repository.recovery = SceneResponseRecovery.TranscriptionPending
        vm.onAction(SceneResponseAction.ContinueRoundRequested)
        runCurrent()
        assertEquals(1, vm.uiState.value.roundIndex)
        assertEquals(listOf(0, 1), repository.restoredRounds)
        assertTrue(vm.uiState.value.technicalRetryPending)
        assertEquals(0, repository.trainingCalls)
    }

    @Test fun unavailableJournalCannotBeTreatedAsAnEmptyNewRecording() = runTest(dispatcher) {
        val repository = Repository(SceneResponseRecovery.Unavailable)
        val vm = model(repository)
        runCurrent()
        assertTrue(vm.uiState.value.progressFailed)
        vm.onAction(SceneResponseAction.StartRequested)
        vm.onAction(SceneResponseAction.ContinueRoundRequested)
        runCurrent()
        assertEquals(0, repository.starts)
        assertEquals(0, repository.transcriptions)
    }

    @Test fun deliveredFinalCandidateRestoresAndOnlyRetriesItsLocalSave() = runTest(dispatcher) {
        val repository = Repository(SceneResponseRecovery.EvaluationReady(PASS, "confirmed"))
        val vm = model(repository, 2)
        runCurrent()
        assertEquals(SceneEvaluationDecision.PASS, vm.uiState.value.finalDecision)
        assertTrue(vm.uiState.value.saveFailed)
        assertEquals(0, repository.saves)
        vm.onAction(SceneResponseAction.RetryRequested)
        runCurrent()
        assertEquals(1, repository.saves)
        assertEquals(0, repository.evaluations)
        assertEquals(0, repository.starts)
    }

    @Test fun recoveredTranscriptRetainsOnlyAnEditBoundToTheSameOriginalRecording() = runTest(dispatcher) {
        for ((identity, expected) in listOf("recording" to "saved manual edit", "another" to "original words")) {
            val repository = Repository(SceneResponseRecovery.TranscriptReady(SpeakingTranscription("original words", emptyList(), null), "recording"))
            val vm = model(repository, extra = mapOf("recordingId" to identity, "transcript" to "saved manual edit"))
            runCurrent()
            assertTrue(vm.uiState.value.transcriptReady)
            assertEquals(expected, vm.uiState.value.transcript)
            assertEquals(0, repository.transcriptions)
        }
    }

    private fun model(repository: Repository, round: Int = 0, extra: Map<String, String> = emptyMap(), onWrite: (Map<String, String>) -> Unit = {}): SceneResponseViewModel {
        var saved = encodeSceneProgress(SceneResponseUiState(chapter, listOf(task), loading = false, roundIndex = round), null) + extra
        val progress = object : LearningProgressStore {
            override suspend fun read() = saved
            override suspend fun write(values: Map<String, String>): Boolean { saved = values.toMap(); onWrite(saved); return true }
        }
        return SceneResponseViewModel(chapter, listOf(task), repository, progress).also { vm ->
            owners += ViewModelStore().also { it.put("scene", vm) }
        }
    }

    private class Repository(var recovery: SceneResponseRecovery) : SceneResponseRepository {
        override val requiresAttemptRecovery = true
        var starts = 0; var transcriptions = 0; var evaluations = 0; var saves = 0; var trainingCalls = 0
        val evaluatedTexts = mutableListOf<String>(); val restoredRounds = mutableListOf<Int>()
        override suspend fun restoreAttempt(chapter: FixedLearningChapter, task: FixedSceneResponseTask, roundIndex: Int): SceneResponseRecovery {
            restoredRounds += roundIndex; return recovery
        }
        override fun currentRecordingId() = "recording"
        override fun hasPendingTranscriptionRetry() = recovery == SceneResponseRecovery.TranscriptionPending
        override fun hasPendingEvaluationRetry() = recovery is SceneResponseRecovery.FeedbackPending
        override suspend fun generateTasks(chapter: FixedLearningChapter) = SceneTaskGenerationResult.Ready(listOf(task))
        override fun startCapture(): Boolean { starts++; return true }
        override fun cancelCapture() = Unit
        override suspend fun transcribeResponse(taskId: String): SpeakingTranscriptionResult {
            transcriptions++; return SpeakingTranscriptionResult.Ready(SpeakingTranscription("original words", emptyList(), null))
        }
        override suspend fun trainingFeedback(taskId: String, roundIndex: Int, confirmedTranscript: String): SceneTrainingFeedbackResult {
            trainingCalls++; return SceneTrainingFeedbackResult.Ready("feedback")
        }
        override suspend fun evaluateFinalRound(taskId: String, confirmedTranscript: String): SceneEvaluationResult {
            evaluations++; evaluatedTexts += confirmedTranscript; return SceneEvaluationResult.Ready(PASS)
        }
        override suspend fun saveFinalDecision(chapterId: String, sentenceId: String, decision: SceneEvaluationDecision): SceneResponseSaveResult {
            saves++; return SceneResponseSaveResult.Saved
        }
    }

    private companion object {
        val chapter = FixedLearningChapter("chapter", 1, listOf(FixedLearningSentence("sentence", "We can meet today.", "我们可以今天见面。", true)))
        val task = FixedSceneResponseTask("task", "sentence", "A meeting", listOf(SceneDialogueLine("AI", "When can we meet?")), "We can meet today.")
        val PASS = SceneEvaluationCandidate(SceneEvaluationDecision.PASS, true, true, true, "Clear response")
    }
}
