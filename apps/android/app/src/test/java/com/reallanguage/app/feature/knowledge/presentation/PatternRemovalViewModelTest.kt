package com.reallanguage.app.feature.knowledge.presentation

import com.reallanguage.app.feature.knowledge.domain.PatternRemovalRepository
import com.reallanguage.app.feature.knowledge.domain.PatternRemovalResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*

@OptIn(ExperimentalCoroutinesApi::class)
class PatternRemovalViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun cancelReturnsToDetailWithoutRepositoryMutation() = runTest(dispatcher) {
        val repository = Fake()
        val viewModel = PatternRemovalViewModel("p1", "cmd-1", repository)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(PatternRemovalAction.CancelRequested)
        advanceUntilIdle()
        Assert.assertEquals(PatternRemovalEffect.ReturnToDetail, effect.await())
        Assert.assertTrue(repository.calls.isEmpty())
    }

    @Test fun cancelledConfirmationCannotBeRevivedByALateConfirmOrSecondCancel() = runTest(dispatcher) {
        val repository = Fake()
        val viewModel = PatternRemovalViewModel("p1", "cmd-1", repository)
        viewModel.onAction(PatternRemovalAction.CancelRequested)
        // A queued click may arrive before the collector disposes this dialog owner.
        viewModel.onAction(PatternRemovalAction.ConfirmRequested)
        viewModel.onAction(PatternRemovalAction.CancelRequested)
        advanceUntilIdle()
        Assert.assertTrue("Cancel must terminate this confirmation, not only navigate", repository.calls.isEmpty())
        Assert.assertEquals(PatternRemovalEffect.ReturnToDetail, viewModel.effects.first())
        Assert.assertNull(kotlinx.coroutines.withTimeoutOrNull(1) { viewModel.effects.first() })
        Assert.assertFalse(viewModel.uiState.value.removing)
        Assert.assertFalse(viewModel.uiState.value.removeFailed)
    }

    @Test fun confirmUsesScopedAtomicRemovalAndReturnsToList() = runTest(dispatcher) {
        val repository = Fake()
        val viewModel = PatternRemovalViewModel("p1", "cmd-1", repository)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(PatternRemovalAction.ConfirmRequested)
        advanceUntilIdle()
        Assert.assertEquals(PatternRemovalEffect.PatternRemoved("p1"), effect.await())
        Assert.assertEquals(listOf("p1" to "cmd-1"), repository.calls)
    }

    @Test fun alreadyRemovedIsIdempotent() = runTest(dispatcher) {
        val viewModel = PatternRemovalViewModel("p1", "cmd-1", Fake(PatternRemovalResult.AlreadyRemoved))
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(PatternRemovalAction.ConfirmRequested)
        advanceUntilIdle()
        Assert.assertEquals(PatternRemovalEffect.PatternRemoved("p1"), effect.await())
    }

    @Test fun failureKeepsConfirmationAndRetries() = runTest(dispatcher) {
        val repository = Fake(PatternRemovalResult.Failed)
        val viewModel = PatternRemovalViewModel("p1", "cmd-1", repository)
        viewModel.onAction(PatternRemovalAction.ConfirmRequested)
        advanceUntilIdle()
        Assert.assertTrue(viewModel.uiState.value.removeFailed)
        repository.result = PatternRemovalResult.Removed
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(PatternRemovalAction.ConfirmRequested)
        advanceUntilIdle()
        Assert.assertEquals(PatternRemovalEffect.PatternRemoved("p1"), effect.await())
    }

    private class Fake(var result: PatternRemovalResult = PatternRemovalResult.Removed) : PatternRemovalRepository {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun removeFromPatternBook(patternId: String, commandId: String): PatternRemovalResult {
            calls += patternId to commandId
            return result
        }
    }

    @Test fun doubleConfirmCannotRemoveTwiceOrCancelDuringRemoval() = runTest(dispatcher) {
        val repository = Fake()
        val viewModel = PatternRemovalViewModel("p1", "cmd-1", repository)
        viewModel.onAction(PatternRemovalAction.ConfirmRequested)
        viewModel.onAction(PatternRemovalAction.ConfirmRequested)
        viewModel.onAction(PatternRemovalAction.CancelRequested)
        advanceUntilIdle()
        Assert.assertEquals(1, repository.calls.size)
        Assert.assertEquals(PatternRemovalEffect.PatternRemoved("p1"), viewModel.effects.first())
        Assert.assertNull(kotlinx.coroutines.withTimeoutOrNull(1) { viewModel.effects.first() })
        viewModel.onAction(PatternRemovalAction.ConfirmRequested)
        advanceUntilIdle()
        Assert.assertEquals(1, repository.calls.size)
    }

    @Test fun thrownRemovalCanRetryWithoutLeavingAnEndlessSpinner() = runTest(dispatcher) {
        var calls = 0
        val viewModel = PatternRemovalViewModel("p1", "cmd-1", object : PatternRemovalRepository {
            override suspend fun removeFromPatternBook(patternId: String, commandId: String): PatternRemovalResult {
                calls++
                if (calls == 1) error("isolated database failure")
                return PatternRemovalResult.Removed
            }
        })
        viewModel.onAction(PatternRemovalAction.ConfirmRequested)
        advanceUntilIdle()
        Assert.assertFalse(viewModel.uiState.value.removing)
        Assert.assertTrue(viewModel.uiState.value.removeFailed)
        viewModel.onAction(PatternRemovalAction.ConfirmRequested)
        advanceUntilIdle()
        Assert.assertEquals(PatternRemovalEffect.PatternRemoved("p1"), viewModel.effects.first())
    }
}
