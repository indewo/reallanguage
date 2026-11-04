package com.reallanguage.app.feature.review.presentation

import androidx.lifecycle.ViewModelStore
import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.feature.learning.domain.PatternPracticeExample
import com.reallanguage.app.feature.learning.domain.PatternPracticeSet
import com.reallanguage.app.feature.review.domain.PatternIndependentDecision
import com.reallanguage.app.feature.review.domain.PatternIndependentEvaluation
import com.reallanguage.app.feature.review.domain.PatternIndependentEvaluationResult
import com.reallanguage.app.feature.review.domain.PatternIndependentGenerationResult
import com.reallanguage.app.feature.review.domain.PatternIndependentReceipt
import com.reallanguage.app.feature.review.domain.PatternIndependentReceiptIdentity
import com.reallanguage.app.feature.review.domain.PatternIndependentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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

/** Real VM only; in-memory repository/receipt, no microphone, transport, database or AI. */
@OptIn(ExperimentalCoroutinesApi::class)
class PatternIndependentCaptureInterruptionTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() { stores.forEach { it.clear() }; Dispatchers.resetMain() }

    @Test fun liveInterruptionCancelsOnceAndOnlyExplicitStartCanRecordAgain() = runTest(dispatcher) {
        val repository = Repository()
        val vm = own(repository)
        advanceUntilIdle()
        vm.onAction(PatternIndependentAction.StartRequested)
        assertTrue(vm.uiState.value.recording)
        assertEquals(1, repository.starts)

        vm.interruptUnsubmittedCapture()
        vm.interruptUnsubmittedCapture()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.recording)
        assertFalse(vm.uiState.value.evaluating)
        assertFalse(vm.uiState.value.failed)
        assertEquals(example, vm.uiState.value.example)
        assertNull(vm.uiState.value.decision)
        assertNull(vm.uiState.value.receipt)
        assertEquals(1, repository.cancels)
        assertEquals(1, repository.starts)
        assertEquals(1, repository.generations)

        vm.onAction(PatternIndependentAction.FinishedRequested)
        advanceUntilIdle()
        assertTrue(repository.evaluations.isEmpty())
        vm.onAction(PatternIndependentAction.StartRequested)
        assertTrue(vm.uiState.value.recording)
        assertEquals(2, repository.starts)
        assertEquals(1, repository.cancels)
        assertEquals(1, repository.generations)
    }

    @Test fun submittedEvaluationSurvivesInterruptionBeforeDispatchAndWhileAwaitingItsReceipt() = runTest(dispatcher) {
        val repository = Repository()
        val vm = own(repository)
        advanceUntilIdle()
        vm.onAction(PatternIndependentAction.StartRequested)
        vm.onAction(PatternIndependentAction.FinishedRequested)
        assertFalse(vm.uiState.value.recording)
        assertTrue(vm.uiState.value.evaluating)
        val submitted = vm.uiState.value

        vm.interruptUnsubmittedCapture() // Submitted synchronously, before evaluate is dispatched.
        assertEquals(submitted, vm.uiState.value)
        runCurrent()
        repeat(2) { vm.interruptUnsubmittedCapture() }
        repeat(2) { vm.onAction(PatternIndependentAction.FinishedRequested) }
        runCurrent()
        assertEquals(submitted, vm.uiState.value)
        assertEquals(listOf(pattern.patternId to example.exampleId), repository.evaluations)
        assertEquals(0, repository.cancels)
        assertEquals(0, repository.cancelledEvaluations)
        assertEquals(1, repository.starts)

        repository.response.complete(PatternIndependentEvaluationResult.Ready(
            PatternIndependentEvaluation(PatternIndependentDecision.PASS, FEEDBACK, receipt)))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.evaluating)
        assertFalse(vm.uiState.value.failed)
        assertEquals(PatternIndependentDecision.PASS, vm.uiState.value.decision)
        assertEquals(FEEDBACK, vm.uiState.value.feedbackZh)
        assertEquals(receipt, vm.uiState.value.receipt)
        val delivered = vm.uiState.value
        vm.interruptUnsubmittedCapture()
        advanceUntilIdle()
        assertEquals(delivered, vm.uiState.value)
        assertEquals(0, repository.cancels)
        assertEquals(0, repository.cancelledEvaluations)
        assertEquals(1, repository.evaluations.size)
        assertEquals(1, repository.starts)
        assertEquals(1, repository.generations)
    }

    @Test fun idleInterruptionNeitherCancelsNorChangesTheReadyPractice() = runTest(dispatcher) {
        val repository = Repository()
        val vm = own(repository)
        advanceUntilIdle()
        val idle = vm.uiState.value
        assertEquals(example, idle.example)
        assertFalse(idle.recording)
        assertFalse(idle.evaluating)
        repeat(2) { vm.interruptUnsubmittedCapture() }
        advanceUntilIdle()
        assertEquals(idle, vm.uiState.value)
        assertEquals(0, repository.cancels)
        assertEquals(0, repository.starts)
        assertTrue(repository.evaluations.isEmpty())
        assertEquals(1, repository.generations)
    }

    private fun own(repository: Repository) = PatternIndependentViewModel(pattern, repository).also { vm ->
        stores += ViewModelStore().apply { put("isolated-pattern-independent", vm) }
    }

    private class Repository : PatternIndependentRepository {
        var starts = 0
        var cancels = 0
        var generations = 0
        var cancelledEvaluations = 0
        val evaluations = mutableListOf<Pair<String, String>>()
        val response = CompletableDeferred<PatternIndependentEvaluationResult>()
        override fun startCapture(): Boolean { starts++; return true }
        override fun cancelCapture() { cancels++ }
        override suspend fun generate(pattern: LockedSpeakingPattern): PatternIndependentGenerationResult {
            generations++
            return PatternIndependentGenerationResult.Ready(PatternPracticeSet(pattern.patternId, listOf(example)))
        }
        override suspend fun evaluate(patternId: String, exampleId: String): PatternIndependentEvaluationResult {
            evaluations += patternId to exampleId
            return try { response.await() } catch (cancelled: CancellationException) {
                cancelledEvaluations++
                throw cancelled
            }
        }
    }

    private companion object {
        const val FEEDBACK = "隔离已交付结果，不代表原音质量。"
        val pattern = LockedSpeakingPattern("p1", "s1", "used to + verb", "过去习惯")
        val example = PatternPracticeExample("e1", "谈过去的习惯", "What changed?", "I used to walk.", "我过去常走路。")
        val receipt = PatternIndependentReceipt(1,
            PatternIndependentReceiptIdentity("isolated-account", 1, "review", "entry", "chapter", "s1", "ability", "p1"),
            "e1", "isolated-command", "a".repeat(64), "b".repeat(64), PatternIndependentDecision.PASS, true, true, true, .9)
    }
}
