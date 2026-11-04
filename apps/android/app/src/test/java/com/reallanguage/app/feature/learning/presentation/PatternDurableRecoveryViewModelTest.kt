package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModelStore
import com.reallanguage.app.feature.learning.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PatternDurableRecoveryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    @Before fun main() = Dispatchers.setMain(dispatcher)
    @After fun close() { owners.forEach(ViewModelStore::clear); Dispatchers.resetMain() }

    @Test fun restoredReadingPendingOnlyExplicitSameExampleRetriesOriginalFeedback() = runTest(dispatcher) {
        val repo = Repository(PatternFeedbackRecovery.Pending("e1", true)); val vm = vm(repo, Progress(state()))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.technicalRetryPending); assertEquals("e1", vm.uiState.value.feedbackExampleId)
        assertEquals(0, repo.starts); assertEquals(0, repo.calls); assertEquals(0, repo.generations)
        vm.onAction(PatternSpeakingAction.StartReadingRequested("e2")); advanceUntilIdle(); assertEquals(0, repo.starts)
        val gate = CompletableDeferred<Unit>(); repo.gate = gate
        repeat(3) { vm.onAction(PatternSpeakingAction.StartReadingRequested("e1")) }
        advanceUntilIdle(); assertEquals(1, repo.calls); assertTrue(vm.uiState.value.submitting)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(setOf("e1"), vm.uiState.value.completedReadingExampleIds); assertEquals(0, repo.starts)
    }

    @Test fun restoredResponsePendingPreservesCurrentSceneAndDoesNotAdvanceOrRecord() = runTest(dispatcher) {
        val repo = Repository(PatternFeedbackRecovery.Pending("e1", false)); val vm = vm(repo, Progress(state(false)))
        advanceUntilIdle(); assertTrue(vm.uiState.value.technicalRetryPending)
        vm.onAction(PatternSpeakingAction.TranscriptChanged("replacement"))
        assertEquals("", vm.uiState.value.transcript)
        vm.onAction(PatternSpeakingAction.RetryResponseRequested); advanceUntilIdle()
        assertEquals(1, repo.calls); assertEquals(0, repo.starts)
        assertTrue(vm.uiState.value.currentResponseCompleted); assertEquals(0, vm.uiState.value.responseIndex)
        assertEquals(ready.feedback.transcript, vm.uiState.value.transcript)
    }

    @Test fun deliveredCandidateRestoresAndRetriesFailedLocalSaveWithoutAnyFeedbackCall() = runTest(dispatcher) {
        val repo = Repository(PatternFeedbackRecovery.Delivered("e1", true, ready))
        val store = Progress(state()).apply { writable = false }; val vm = vm(repo, store)
        advanceUntilIdle()
        assertEquals(setOf("e1"), vm.uiState.value.completedReadingExampleIds)
        assertTrue(vm.uiState.value.progressFailed); assertEquals(0, repo.calls); assertEquals(0, repo.starts)
        store.writable = true; vm.onAction(PatternSpeakingAction.RetryProgressRequested); advanceUntilIdle()
        assertFalse(vm.uiState.value.progressFailed)
        val next = vm(repo, store); advanceUntilIdle()
        assertEquals(setOf("e1"), next.uiState.value.completedReadingExampleIds)
        assertEquals(0, repo.calls); assertEquals(0, repo.generations)
        assertEquals("true", store.saved["example.0.read"])
    }

    @Test fun oldDeliveredResponseCannotOverwriteNextSceneOrCreateCompletion() = runTest(dispatcher) {
        val repo = Repository(PatternFeedbackRecovery.Delivered("e1", false, ready))
        val vm = vm(repo, Progress(state(false).copy(responseIndex = 1, transcript = "second draft")))
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.responseIndex); assertEquals("second draft", vm.uiState.value.transcript)
        assertFalse(vm.uiState.value.currentResponseCompleted); assertEquals(0, repo.calls)
    }

    @Test fun unavailableCustodyPreventsCaptureUntilExplicitLocalRecoverySucceeds() = runTest(dispatcher) {
        val repo = Repository(PatternFeedbackRecovery.Unavailable); val vm = vm(repo, Progress(state()))
        advanceUntilIdle(); assertTrue(vm.uiState.value.progressFailed)
        vm.onAction(PatternSpeakingAction.StartReadingRequested("e1")); advanceUntilIdle(); assertEquals(0, repo.starts)
        repo.recovery = PatternFeedbackRecovery.Blocked("e1", true)
        vm.onAction(PatternSpeakingAction.RetryProgressRequested); advanceUntilIdle()
        assertTrue(vm.uiState.value.technicalRetryBlocked); assertFalse(vm.uiState.value.technicalRetryPending)
        vm.onAction(PatternSpeakingAction.StartReadingRequested("e1")); advanceUntilIdle()
        assertEquals(1, repo.starts); assertEquals(1, repo.preparations); assertEquals(0, repo.calls)
    }

    @Test fun capturePreparationIsSingleAndCannotBypassFailedProgressSave() = runTest(dispatcher) {
        val store = Progress(state()); val repo = Repository(PatternFeedbackRecovery.None)
        val vm = vm(repo, store); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>(); repo.prepareGate = gate
        repeat(3) { vm.onAction(PatternSpeakingAction.StartReadingRequested("e1")) }
        advanceUntilIdle(); assertEquals(1, repo.preparations); assertEquals(0, repo.starts)
        gate.complete(Unit); advanceUntilIdle(); assertEquals(1, repo.starts)
        vm.interruptUnsubmittedCapture(); store.writable = false
        // A real changed local state forces the next checkpoint write to fail before any microphone.
        vm.onAction(PatternSpeakingAction.AudioToggled); advanceUntilIdle()
        vm.onAction(PatternSpeakingAction.StartReadingRequested("e1")); advanceUntilIdle()
        assertEquals(1, repo.starts); assertEquals(1, repo.preparations)
    }

    @Test fun savedSkipCanExplicitlyStartNextSceneWithoutResurrectingOldUnknownFeedback() = runTest(dispatcher) {
        val repo = Repository(PatternFeedbackRecovery.Pending("e1", false))
        val store = Progress(state(false).copy(responseIndex = 1)); val vm = vm(repo, store)
        advanceUntilIdle(); assertFalse(vm.uiState.value.technicalRetryPending)
        vm.onAction(PatternSpeakingAction.StartResponseRequested); advanceUntilIdle()
        assertEquals(1, repo.releases); assertEquals("1", store.saved["responseIndex"])
        assertEquals(1, repo.starts); assertEquals(0, repo.calls)
    }

    private fun vm(repo: Repository, progress: Progress) = PatternSpeakingViewModel(chapter, "s", listOf(pattern), repo, progress).also {
        owners += ViewModelStore().apply { put("pattern", it) }
    }
    private class Progress(initial: PatternSpeakingUiState) : LearningProgressStore {
        var saved = encodePatternProgress(initial); var writable = true
        override suspend fun read() = saved.toMap()
        override suspend fun write(values: Map<String, String>): Boolean { if (!writable) return false; saved = values.toMap(); return true }
    }
    private class Repository(var recovery: PatternFeedbackRecovery) : PatternSpeakingRepository {
        override val requiresFeedbackRecovery = true
        var starts = 0; var calls = 0; var generations = 0; var preparations = 0; var releases = 0
        var gate: CompletableDeferred<Unit>? = null; var prepareGate: CompletableDeferred<Unit>? = null
        override fun startCapture(): Boolean { starts++; return true }
        override fun cancelCapture() = Unit
        override suspend fun restoreFeedback(pattern: LockedSpeakingPattern, practiceSet: PatternPracticeSet) = recovery
        override suspend fun releaseSupersededFeedback(): Boolean { releases++; recovery = PatternFeedbackRecovery.None; return true }
        override suspend fun prepareFeedbackCapture(patternId: String, exampleId: String, reading: Boolean): Boolean { preparations++; prepareGate?.await(); return true }
        override fun hasPendingFeedbackRetry() = recovery is PatternFeedbackRecovery.Pending
        override suspend fun generate(pattern: LockedSpeakingPattern): PatternPracticeGenerationResult { generations++; return PatternPracticeGenerationResult.Failed }
        override suspend fun readingFeedback(patternId: String, exampleId: String): PatternPracticeFeedbackResult { calls++; gate?.await(); return ready }
        override suspend fun responseFeedback(patternId: String, exampleId: String) = readingFeedback(patternId, exampleId)
    }
    private companion object {
        val chapter = FixedLearningChapter("c", 1, listOf(FixedLearningSentence("s", "I would rather have tea.", "我更想喝茶。", true)))
        val pattern = LockedSpeakingPattern("p", "s", "would rather", "表达偏好")
        val examples = listOf("e1", "e2").map { PatternPracticeExample(it, "点茶", "Tea or coffee?", "I would rather have tea.", "我更想喝茶。") }
        val ready = PatternPracticeFeedbackResult.Ready(PatternPracticeFeedback("完整反馈。", false, "I would rather have tea."))
        fun state(reading: Boolean = true) = PatternSpeakingUiState(chapter, "s", listOf(pattern),
            stage = if (reading) PatternSpeakingStage.READING else PatternSpeakingStage.RESPONDING,
            examples = examples, completedReadingExampleIds = if (reading) emptySet() else examples.map { it.exampleId }.toSet())
    }
}
