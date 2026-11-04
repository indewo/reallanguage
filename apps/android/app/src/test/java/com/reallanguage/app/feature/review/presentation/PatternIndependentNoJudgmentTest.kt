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
class PatternIndependentNoJudgmentTest {
    private val dispatcher = StandardTestDispatcher()
    private val pattern = LockedSpeakingPattern("pattern", "s1", "used to + verb", "过去习惯")
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun unavailableNaturalSceneEndsOnlyWithAnExplicitNoJudgmentAndNoAudioEvidence() = runTest(dispatcher) {
        val repository = Repository().apply { unavailable = true }
        val vm = PatternIndependentViewModel(pattern, repository)
        val effects = mutableListOf<PatternIndependentEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        vm.onAction(PatternIndependentAction.ContinueRequested)
        vm.onAction(PatternIndependentAction.ContinueRequested)
        advanceUntilIdle()
        assertEquals(listOf(PatternIndependentEffect.NotNaturallyVerifiable), effects)
        assertEquals(0, repository.starts); assertEquals(0, repository.evaluations)
    }

    @Test fun inconclusiveAudioCannotEmitFailureOrCompletionButCanBeRerecorded() = runTest(dispatcher) {
        val repository = Repository().apply { decision = PatternIndependentDecision.INCONCLUSIVE }
        val vm = PatternIndependentViewModel(pattern, repository)
        val effects = mutableListOf<PatternIndependentEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        vm.onAction(PatternIndependentAction.StartRequested); vm.onAction(PatternIndependentAction.FinishedRequested)
        advanceUntilIdle()
        vm.onAction(PatternIndependentAction.ContinueRequested)
        advanceUntilIdle()
        assertTrue(effects.isEmpty())
        vm.onAction(PatternIndependentAction.RetryRequested)
        repository.decision = PatternIndependentDecision.PASS
        vm.onAction(PatternIndependentAction.StartRequested); vm.onAction(PatternIndependentAction.FinishedRequested)
        advanceUntilIdle()
        vm.onAction(PatternIndependentAction.ContinueRequested)
        advanceUntilIdle()
        assertEquals(listOf(PatternIndependentEffect.Completed(true, repository.receipt)), effects)
        assertEquals(2, repository.starts); assertEquals(2, repository.evaluations)
    }

    @Test fun realPassAndFailStillDeliverTheirOwnIndependentOutcomeOnlyOnce() = runTest(dispatcher) {
        for (decision in listOf(PatternIndependentDecision.PASS, PatternIndependentDecision.FAIL)) {
            val repository = Repository().apply { this.decision = decision }
            val vm = PatternIndependentViewModel(pattern, repository)
            val effects = mutableListOf<PatternIndependentEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
            advanceUntilIdle()
            vm.onAction(PatternIndependentAction.StartRequested); vm.onAction(PatternIndependentAction.FinishedRequested)
            advanceUntilIdle()
            vm.onAction(PatternIndependentAction.ContinueRequested); vm.onAction(PatternIndependentAction.ContinueRequested)
            advanceUntilIdle()
            assertEquals(listOf(PatternIndependentEffect.Completed(decision == PatternIndependentDecision.PASS, repository.receipt)), effects)
            assertEquals(1, repository.evaluations)
        }
    }

    @Test fun aGenerationFailureDoesNotGrantTheUnavailableNaturalSceneExit() = runTest(dispatcher) {
        val vm = PatternIndependentViewModel(pattern, Repository().apply { failed = true })
        val effects = mutableListOf<PatternIndependentEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        vm.onAction(PatternIndependentAction.ContinueRequested)
        advanceUntilIdle()
        assertTrue(effects.isEmpty())
        assertTrue(vm.uiState.value.failed)
    }

    private class Repository : PatternIndependentRepository {
        var unavailable = false
        var failed = false
        var decision = PatternIndependentDecision.PASS
        var starts = 0
        var evaluations = 0
        var receipt: PatternIndependentReceipt? = null
        override fun startCapture() = true.also { starts++ }
        override fun cancelCapture() = Unit
        override suspend fun generate(pattern: LockedSpeakingPattern): PatternIndependentGenerationResult = when {
            failed -> PatternIndependentGenerationResult.Failed
            unavailable -> PatternIndependentGenerationResult.NotNaturallyVerifiable
            else -> PatternIndependentGenerationResult.Ready(PatternPracticeSet(pattern.patternId,
                listOf(PatternPracticeExample("example", "过去的习惯", "What changed?", "I used to walk.", "我以前常走路。"))))
        }
        override suspend fun evaluate(patternId: String, exampleId: String): PatternIndependentEvaluationResult {
            receipt = if (decision == PatternIndependentDecision.INCONCLUSIVE) null else PatternIndependentReceipt(1,
                PatternIndependentReceiptIdentity("isolated-unit", 1, "review", "entry", "chapter", "s1", "ability", patternId),
                exampleId, "isolated-command", "a".repeat(64), "b".repeat(64), decision,
                decision == PatternIndependentDecision.PASS, true, true, .9)
            evaluations++
            return PatternIndependentEvaluationResult.Ready(PatternIndependentEvaluation(decision, "本次测试反馈", receipt))
        }
    }
}
