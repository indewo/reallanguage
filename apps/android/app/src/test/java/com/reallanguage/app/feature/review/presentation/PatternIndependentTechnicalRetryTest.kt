package com.reallanguage.app.feature.review.presentation

import androidx.lifecycle.ViewModelStore
import com.reallanguage.app.feature.learning.domain.*
import com.reallanguage.app.feature.review.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real VM with an in-memory attempt boundary, not audio, provider or SQLite evidence. */
@OptIn(ExperimentalCoroutinesApi::class)
class PatternIndependentTechnicalRetryTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() { stores.forEach { it.clear() }; Dispatchers.resetMain() }

    @Test fun unknownResponseRetriesTheOriginalEvaluationWithoutNewCaptureAndKeepsItsReceipt() = runTest(dispatcher) {
        val repo = Repository()
        val vm = own(repo)
        advanceUntilIdle()
        vm.onAction(PatternIndependentAction.StartRequested)
        vm.onAction(PatternIndependentAction.FinishedRequested)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.failed)
        assertTrue(vm.uiState.value.technicalRetryPending)
        assertFalse(vm.uiState.value.technicalRetryBlocked)
        assertNull(vm.uiState.value.receipt)

        repo.next = PatternIndependentEvaluationResult.Ready(PatternIndependentEvaluation(
            PatternIndependentDecision.PASS, "隔离回执已保存", receipt))
        repeat(2) { vm.onAction(PatternIndependentAction.RetryRequested) }
        assertTrue(vm.uiState.value.evaluating)
        vm.onAction(PatternIndependentAction.StartRequested)
        vm.onAction(PatternIndependentAction.FinishedRequested)
        advanceUntilIdle()
        assertEquals(listOf("p1" to "e1", "p1" to "e1"), repo.evaluations)
        assertEquals(1, repo.starts)
        assertEquals(0, repo.cancels)
        assertEquals(1, repo.generations)
        assertEquals(receipt, vm.uiState.value.receipt)
        assertEquals(PatternIndependentDecision.PASS, vm.uiState.value.decision)
        assertFalse(vm.uiState.value.technicalRetryPending)
        assertFalse(vm.uiState.value.failed)
    }

    @Test fun pendingAttemptCannotBeReplacedOrFinishedAgainAndDoesNotEmitCompletion() = runTest(dispatcher) {
        val repo = Repository()
        val vm = own(repo)
        val emitted = mutableListOf<PatternIndependentEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { emitted += it } }
        advanceUntilIdle()
        vm.onAction(PatternIndependentAction.StartRequested)
        vm.onAction(PatternIndependentAction.FinishedRequested)
        advanceUntilIdle()
        val pending = vm.uiState.value
        vm.onAction(PatternIndependentAction.StartRequested)
        vm.onAction(PatternIndependentAction.FinishedRequested)
        vm.onAction(PatternIndependentAction.ContinueRequested)
        advanceUntilIdle()
        assertEquals(pending, vm.uiState.value)
        assertEquals(1, repo.starts)
        assertEquals(1, repo.evaluations.size)
        assertEquals(0, repo.cancels)
        assertNull(vm.uiState.value.decision)
        assertNull(vm.uiState.value.receipt)
        assertTrue(emitted.isEmpty())
    }

    @Test fun terminalAttemptDoesNotPretendToRetryAndOnlyExplicitStartRecordsAgain() = runTest(dispatcher) {
        val repo = Repository().apply { terminalFailure = true }
        val vm = own(repo)
        advanceUntilIdle()
        vm.onAction(PatternIndependentAction.StartRequested)
        vm.onAction(PatternIndependentAction.FinishedRequested)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.technicalRetryBlocked)
        assertFalse(vm.uiState.value.technicalRetryPending)
        val failed = vm.uiState.value
        vm.onAction(PatternIndependentAction.RetryRequested)
        vm.onAction(PatternIndependentAction.FinishedRequested)
        advanceUntilIdle()
        assertEquals(failed, vm.uiState.value)
        assertEquals(1, repo.evaluations.size)
        assertEquals(1, repo.starts)
        vm.onAction(PatternIndependentAction.StartRequested)
        assertEquals(2, repo.starts)
        assertTrue(vm.uiState.value.recording)
        assertFalse(vm.uiState.value.failed)
        assertFalse(vm.uiState.value.technicalRetryBlocked)
        assertEquals(1, repo.evaluations.size)
    }

    @Test fun deliveredInconclusiveIsANewPracticeNotATechnicalResubmission() = runTest(dispatcher) {
        val repo = Repository().apply { next = PatternIndependentEvaluationResult.Ready(
            PatternIndependentEvaluation(PatternIndependentDecision.INCONCLUSIVE, "隔离不确定判断")) }
        val vm = own(repo)
        advanceUntilIdle()
        vm.onAction(PatternIndependentAction.StartRequested)
        vm.onAction(PatternIndependentAction.FinishedRequested)
        advanceUntilIdle()
        assertEquals(PatternIndependentDecision.INCONCLUSIVE, vm.uiState.value.decision)
        assertFalse(vm.uiState.value.technicalRetryPending)
        assertFalse(vm.uiState.value.technicalRetryBlocked)
        vm.onAction(PatternIndependentAction.RetryRequested)
        advanceUntilIdle()
        assertNull(vm.uiState.value.decision)
        assertEquals(1, repo.evaluations.size)
        assertEquals(1, repo.starts)
        assertEquals(1, repo.generations)
        assertEquals(example, vm.uiState.value.example)
    }

    @Test fun microphoneStartFailureIsVisibleWithoutEvaluatingOrRegenerating() = runTest(dispatcher) {
        val repo = Repository().apply { captureStarts = false }
        val vm = own(repo)
        advanceUntilIdle()
        vm.onAction(PatternIndependentAction.StartRequested)
        assertFalse(vm.uiState.value.recording)
        assertTrue(vm.uiState.value.failed)
        assertEquals(example, vm.uiState.value.example)
        vm.onAction(PatternIndependentAction.FinishedRequested)
        advanceUntilIdle()
        assertEquals(1, repo.starts)
        assertEquals(1, repo.generations)
        assertTrue(repo.evaluations.isEmpty())
        assertNull(vm.uiState.value.receipt)
        assertFalse(vm.uiState.value.technicalRetryPending)
    }

    private fun own(repo: Repository) = PatternIndependentViewModel(pattern, repo).also {
        stores += ViewModelStore().apply { put("technical-retry", it) }
    }
    private class Repository : PatternIndependentRepository {
        var starts = 0
        var cancels = 0
        var generations = 0
        var terminalFailure = false
        var captureStarts = true
        var pending = false
        var blocked = false
        var next: PatternIndependentEvaluationResult = PatternIndependentEvaluationResult.Failed
        val evaluations = mutableListOf<Pair<String, String>>()
        override fun startCapture(): Boolean { starts++; pending = false; blocked = false; return captureStarts }
        override fun cancelCapture() { cancels++; pending = false; blocked = false }
        override fun hasPendingEvaluationRetry() = pending
        override fun technicalRetryBlocked() = blocked
        override suspend fun generate(pattern: LockedSpeakingPattern): PatternIndependentGenerationResult {
            generations++
            return PatternIndependentGenerationResult.Ready(PatternPracticeSet("p1", listOf(example)))
        }
        override suspend fun evaluate(patternId: String, exampleId: String): PatternIndependentEvaluationResult {
            evaluations += patternId to exampleId
            pending = next == PatternIndependentEvaluationResult.Failed && !terminalFailure
            blocked = next == PatternIndependentEvaluationResult.Failed && terminalFailure
            return next
        }
    }
    private companion object {
        val pattern = LockedSpeakingPattern("p1", "s1", "used to + verb", "过去习惯")
        val example = PatternPracticeExample("e1", "过去习惯", "What changed?", "I used to walk.", "我过去常走路。")
        val receipt = PatternIndependentReceipt(1,
            PatternIndependentReceiptIdentity("isolated-account", 1, "review", "entry", "chapter", "s1", "ability", "p1"),
            "e1", "original-command", "a".repeat(64), "b".repeat(64), PatternIndependentDecision.PASS, true, true, true, .9)
    }
}
