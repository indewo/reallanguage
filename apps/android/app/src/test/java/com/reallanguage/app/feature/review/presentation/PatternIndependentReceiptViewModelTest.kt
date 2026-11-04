package com.reallanguage.app.feature.review.presentation

import com.reallanguage.app.feature.learning.domain.*
import com.reallanguage.app.feature.review.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PatternIndependentReceiptViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val pattern = LockedSpeakingPattern("target", "sentence", "used to + verb", "过去习惯")
    private val receipt = PatternIndependentReceipt(1,
        PatternIndependentReceiptIdentity("account", 1, "review", "entry", "chapter", "sentence", "ability", "target"),
        "example", "command", "a".repeat(64), "b".repeat(64), PatternIndependentDecision.PASS, true, true, true, .9)
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun aPassedFlagWithoutReceiptCannotLeaveTheModuleAsIndependentSuccess() = runTest(dispatcher) {
        val vm = PatternIndependentViewModel(pattern, Repository(null))
        val effects = mutableListOf<PatternIndependentEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        vm.onAction(PatternIndependentAction.StartRequested); vm.onAction(PatternIndependentAction.FinishedRequested)
        advanceUntilIdle(); vm.onAction(PatternIndependentAction.ContinueRequested); advanceUntilIdle()
        assertTrue(effects.isEmpty())
        assertTrue(vm.uiState.value.failed)
    }

    @Test fun registeredReceiptReachesTheEffectUnchangedAndOnlyOnce() = runTest(dispatcher) {
        val vm = PatternIndependentViewModel(pattern, Repository(receipt))
        val effects = mutableListOf<PatternIndependentEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        vm.onAction(PatternIndependentAction.StartRequested); vm.onAction(PatternIndependentAction.FinishedRequested)
        advanceUntilIdle(); repeat(2) { vm.onAction(PatternIndependentAction.ContinueRequested) }; advanceUntilIdle()
        assertEquals(receipt, vm.uiState.value.receipt)
        assertEquals(listOf(PatternIndependentEffect.Completed(true, receipt)), effects)
    }

    @Test fun aNewCaptureCannotCompleteWithThePreviousRecordingsReceipt() = runTest(dispatcher) {
        val vm = PatternIndependentViewModel(pattern, Repository(receipt))
        val effects = mutableListOf<PatternIndependentEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        vm.onAction(PatternIndependentAction.StartRequested); vm.onAction(PatternIndependentAction.FinishedRequested)
        advanceUntilIdle()
        assertEquals(receipt, vm.uiState.value.receipt)
        vm.onAction(PatternIndependentAction.StartRequested)
        vm.onAction(PatternIndependentAction.ContinueRequested)
        advanceUntilIdle()
        assertTrue(effects.isEmpty())
        assertNull(vm.uiState.value.receipt)
        assertTrue(vm.uiState.value.recording)
    }

    private class Repository(private val receipt: PatternIndependentReceipt?) : PatternIndependentRepository {
        override fun startCapture() = true
        override fun cancelCapture() = Unit
        override suspend fun generate(pattern: LockedSpeakingPattern) = PatternIndependentGenerationResult.Ready(
            PatternPracticeSet(pattern.patternId, listOf(PatternPracticeExample("example", "过去的习惯", "What changed?", "I used to walk.", "我以前常走路。"))))
        override suspend fun evaluate(patternId: String, exampleId: String) = PatternIndependentEvaluationResult.Ready(
            PatternIndependentEvaluation(PatternIndependentDecision.PASS, "隔离测试判断", receipt))
    }
}
