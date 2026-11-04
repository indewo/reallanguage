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
class PatternIndependentRequestGateTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun finishedDoubleTapBeforeCoroutineDispatchEvaluatesTheRecordingOnlyOnce() = runTest(dispatcher) {
        val repository = Repository()
        val vm = PatternIndependentViewModel(LockedSpeakingPattern("p", "s", "used to", "过去习惯"), repository)
        advanceUntilIdle()
        vm.onAction(PatternIndependentAction.StartRequested)
        repeat(2) { vm.onAction(PatternIndependentAction.FinishedRequested) }
        advanceUntilIdle()
        assertEquals(1, repository.evaluations)
    }

    @Test fun failedGenerationRetryDoubleTapStartsOnlyOneReplacementRequest() = runTest(dispatcher) {
        val repository = Repository().apply { generationFails = true }
        val vm = PatternIndependentViewModel(LockedSpeakingPattern("p", "s", "used to", "过去习惯"), repository)
        advanceUntilIdle()
        assertEquals(1, repository.generations)
        repeat(2) { vm.onAction(PatternIndependentAction.RetryRequested) }
        advanceUntilIdle()
        assertEquals(2, repository.generations)
    }

    private class Repository : PatternIndependentRepository {
        var generationFails = false
        var generations = 0
        var evaluations = 0
        override fun startCapture() = true
        override fun cancelCapture() = Unit
        override suspend fun generate(pattern: LockedSpeakingPattern): PatternIndependentGenerationResult {
            generations++
            return if (generationFails) PatternIndependentGenerationResult.Failed else PatternIndependentGenerationResult.Ready(
                PatternPracticeSet("p", listOf(PatternPracticeExample("e", "过去习惯", "What changed?", "I used to walk.", "我以前常走路。"))))
        }
        override suspend fun evaluate(patternId: String, exampleId: String): PatternIndependentEvaluationResult {
            evaluations++
            return PatternIndependentEvaluationResult.Ready(PatternIndependentEvaluation(PatternIndependentDecision.INCONCLUSIVE, "请重试"))
        }
    }
}
