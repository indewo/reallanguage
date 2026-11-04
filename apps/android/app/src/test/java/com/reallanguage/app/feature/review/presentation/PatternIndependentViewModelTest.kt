package com.reallanguage.app.feature.review.presentation

import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.feature.learning.domain.PatternPracticeExample
import com.reallanguage.app.feature.learning.domain.PatternPracticeSet
import com.reallanguage.app.feature.review.domain.PatternIndependentDecision
import com.reallanguage.app.feature.review.domain.PatternIndependentEvaluation
import com.reallanguage.app.feature.review.domain.PatternIndependentEvaluationResult
import com.reallanguage.app.feature.review.domain.PatternIndependentGenerationResult
import com.reallanguage.app.feature.review.domain.PatternIndependentRepository
import com.reallanguage.app.feature.review.domain.PatternIndependentReceipt
import com.reallanguage.app.feature.review.domain.PatternIndependentReceiptIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PatternIndependentViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun hidesTargetAndOnlyEvaluatesAfterRealCapture() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = PatternIndependentViewModel(pattern, repository)
        advanceUntilIdle()
        assertEquals("New situation", viewModel.uiState.value.example?.sceneZh)
        assertFalse(viewModel.uiState.value.recording)

        viewModel.onAction(PatternIndependentAction.StartRequested)
        viewModel.onAction(PatternIndependentAction.FinishedRequested)
        advanceUntilIdle()

        assertTrue(repository.started)
        assertEquals(PatternIndependentDecision.PASS, viewModel.uiState.value.decision)
    }

    @Test fun validDecisionEmitsEvidenceOutcome() = runTest(dispatcher) {
        val viewModel = PatternIndependentViewModel(pattern, FakeRepository())
        advanceUntilIdle()
        viewModel.onAction(PatternIndependentAction.StartRequested)
        viewModel.onAction(PatternIndependentAction.FinishedRequested)
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(PatternIndependentAction.ContinueRequested)
        advanceUntilIdle()
        assertEquals(PatternIndependentEffect.Completed(true, receipt), effect.await())
    }

    private class FakeRepository : PatternIndependentRepository {
        var started = false
        override fun startCapture() = true.also { started = true }
        override fun cancelCapture() = Unit
        override suspend fun generate(pattern: LockedSpeakingPattern) = PatternIndependentGenerationResult.Ready(set)
        override suspend fun evaluate(patternId: String, exampleId: String) = PatternIndependentEvaluationResult.Ready(
            PatternIndependentEvaluation(PatternIndependentDecision.PASS, "通过", receipt),
        )
    }

    companion object {
        private val pattern = LockedSpeakingPattern("p1", "s1", "used to + verb", "过去习惯")
        private val receipt = PatternIndependentReceipt(1,
            PatternIndependentReceiptIdentity("isolated-unit", 1, "review", "entry", "chapter", "s1", "ability", "p1"),
            "e1", "isolated-command", "a".repeat(64), "b".repeat(64), PatternIndependentDecision.PASS, true, true, true, .9)
        private val set = PatternPracticeSet("p1", listOf(PatternPracticeExample("e1", "New situation", "What changed?", "I used to walk.", "我过去常走路。")))
    }
}
