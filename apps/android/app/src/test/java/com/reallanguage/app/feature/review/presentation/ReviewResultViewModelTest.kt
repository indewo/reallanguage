package com.reallanguage.app.feature.review.presentation

import com.reallanguage.app.feature.review.domain.ReviewResultRepository
import com.reallanguage.app.feature.review.domain.ReviewResultSummary
import com.reallanguage.app.feature.review.domain.ReviewSettlementResult
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewResultViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun completedQueueShowsFactsOnlyAfterCycleSettlement() = runTest(dispatcher) {
        val repository = FakeRepository(ReviewSettlementResult.Settled(completed))
        val viewModel = ReviewResultViewModel("session-1", "cmd-1", repository)
        advanceUntilIdle()
        assertEquals(completed, viewModel.uiState.value.summary)
        assertEquals(listOf("session-1" to "cmd-1"), repository.calls)
        assertTrue(viewModel.uiState.value.summary!!.normalLearningDaysReset)
    }

    @Test fun remainingQueueDoesNotClaimCycleReset() = runTest(dispatcher) {
        val summary = completed.copy(remainingFocusSentenceCount = 4, cycleCompleted = false, normalLearningDaysReset = false)
        val viewModel = ReviewResultViewModel("session-1", "cmd-1", FakeRepository(ReviewSettlementResult.Settled(summary)))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.summary!!.cycleCompleted)
        assertFalse(viewModel.uiState.value.summary!!.normalLearningDaysReset)
    }

    @Test fun idempotentSettlementShowsOriginalSummary() = runTest(dispatcher) {
        val viewModel = ReviewResultViewModel("session-1", "cmd-1", FakeRepository(ReviewSettlementResult.AlreadySettled(completed)))
        advanceUntilIdle()
        assertEquals(completed, viewModel.uiState.value.summary)
    }

    @Test fun failedSettlementShowsRetryAndRecovers() = runTest(dispatcher) {
        val repository = FakeRepository(ReviewSettlementResult.Failed)
        val viewModel = ReviewResultViewModel("session-1", "cmd-1", repository)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.settlementFailed)
        assertNull(viewModel.uiState.value.summary)
        repository.result = ReviewSettlementResult.Settled(completed)
        viewModel.onAction(ReviewResultAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(completed, viewModel.uiState.value.summary)
    }

    @Test fun returnHomeRequiresSettledSummary() = runTest(dispatcher) {
        val viewModel = ReviewResultViewModel("session-1", "cmd-1", FakeRepository(ReviewSettlementResult.Settled(completed)))
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(ReviewResultAction.ReturnHomeRequested)
        advanceUntilIdle()
        assertEquals(ReviewResultEffect.ReturnHome, effect.await())
    }

    @Test fun repeatedRetryWhileLoadingAndAfterSuccessDoesNotSettleAgain() = runTest(dispatcher) {
        val repository = FakeRepository(ReviewSettlementResult.Settled(completed))
        val viewModel = ReviewResultViewModel("session-1", "cmd-1", repository)
        repeat(3) { viewModel.onAction(ReviewResultAction.RetryRequested) }
        advanceUntilIdle()
        assertEquals(1, repository.calls.size)
        viewModel.onAction(ReviewResultAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(1, repository.calls.size)
    }

    @Test fun thrownReadStopsLoadingAndAllowsSameCommandRetry() = runTest(dispatcher) {
        var calls = 0
        val repository = object : ReviewResultRepository {
            override suspend fun settleSession(reviewSessionId: String, commandId: String): ReviewSettlementResult {
                calls++
                if (calls == 1) error("isolated storage failure")
                assertEquals("cmd-1", commandId)
                return ReviewSettlementResult.Settled(completed)
            }
        }
        val viewModel = ReviewResultViewModel("session-1", "cmd-1", repository)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.settling)
        assertTrue(viewModel.uiState.value.settlementFailed)
        viewModel.onAction(ReviewResultAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(completed, viewModel.uiState.value.summary)
    }

    private class FakeRepository(var result: ReviewSettlementResult) : ReviewResultRepository {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun settleSession(reviewSessionId: String, commandId: String): ReviewSettlementResult {
            calls += reviewSessionId to commandId
            return result
        }
    }

    companion object {
        private val completed = ReviewResultSummary(3, 0, cycleCompleted = true, normalLearningDaysReset = true)
    }
}
