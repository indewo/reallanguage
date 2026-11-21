package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.ExplanationPreparationRepository
import com.reallanguage.app.feature.learning.domain.ExplanationPreparationResult
import com.reallanguage.app.feature.learning.domain.SelectableExpression
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
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
class ExplanationPreparationViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun leavingDuringPreparationIgnoresLateNavigationWithoutStartingAnotherRequest() = runTest(dispatcher) {
        val gate = CompletableDeferred<ExplanationPreparationResult>()
        var calls = 0
        val model = ExplanationPreparationViewModel { calls++; gate.await() }
        val events = mutableListOf<ExplanationPreparationEffect>()
        val observer = launch { model.effects.collect { events += it } }
        advanceUntilIdle()
        model.onAction(ExplanationPreparationAction.BackRequested)
        model.onAction(ExplanationPreparationAction.BackRequested)
        advanceUntilIdle()
        gate.complete(ExplanationPreparationResult.Ready("迟到的讲解", emptyList()))
        advanceUntilIdle()
        model.onAction(ExplanationPreparationAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(listOf(ExplanationPreparationEffect.ReturnToChapter), events)
        assertEquals(1, calls)
        observer.cancel()
    }

    @Test fun thrownPreparationReleasesLoadingAndOnlyExplicitRetryRequestsAgain() = runTest(dispatcher) {
        var calls = 0
        val model = ExplanationPreparationViewModel {
            if (++calls == 1) throw java.io.IOException("interrupted")
            ExplanationPreparationResult.Ready("原章节讲解", emptyList())
        }
        advanceUntilIdle()
        assertTrue(model.uiState.value.failed)
        assertFalse(model.uiState.value.loading)
        assertEquals(1, calls)
        val effect = async { model.effects.first() }
        model.onAction(ExplanationPreparationAction.RetryRequested)
        model.onAction(ExplanationPreparationAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(ExplanationPreparationEffect.OpenExplanation("原章节讲解", emptyList()), effect.await())
        assertEquals(2, calls)
    }

    @Test fun `loads the base explanation immediately and opens fixed reading`() =
        runTest(dispatcher) {
            val expression = SelectableExpression("word-0", "Today", 0, 5)
            val viewModel = ExplanationPreparationViewModel {
                ExplanationPreparationResult.Ready("基础讲解", listOf(expression))
            }
            val effect = async { viewModel.effects.first() }

            advanceUntilIdle()

            assertEquals(
                ExplanationPreparationEffect.OpenExplanation("基础讲解", listOf(expression)),
                effect.await(),
            )
        }

    @Test fun `failed preparation stays in place and retry can recover`() =
        runTest(dispatcher) {
            var attempts = 0
            val repository = ExplanationPreparationRepository {
                attempts += 1
                if (attempts == 1) ExplanationPreparationResult.Failed
                else ExplanationPreparationResult.Ready("恢复后的讲解", emptyList())
            }
            val viewModel = ExplanationPreparationViewModel(repository)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.failed)
            assertFalse(viewModel.uiState.value.loading)
            val effect = async { viewModel.effects.first() }

            viewModel.onAction(ExplanationPreparationAction.RetryRequested)
            advanceUntilIdle()

            assertEquals(2, attempts)
            assertEquals(
                ExplanationPreparationEffect.OpenExplanation("恢复后的讲解", emptyList()),
                effect.await(),
            )
        }

    @Test fun `back returns to the immutable chapter`() = runTest(dispatcher) {
        val viewModel = ExplanationPreparationViewModel {
            ExplanationPreparationResult.Failed
        }
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }

        viewModel.onAction(ExplanationPreparationAction.BackRequested)
        advanceUntilIdle()

        assertEquals(ExplanationPreparationEffect.ReturnToChapter, effect.await())
    }
}
