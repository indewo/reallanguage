package com.reallanguage.app.feature.review.presentation

import com.reallanguage.app.feature.review.domain.ReviewExitRepository
import com.reallanguage.app.feature.review.domain.ReviewExitResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
class ReviewExitViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun doubleExitAndContinueDuringDeletionCannotNavigateBackToDiscardedWork() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = ReviewExitViewModel("r2", 1, repository)
        val effects = mutableListOf<ReviewExitEffect>()
        val collector = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        vm.onAction(ReviewExitAction.ConfirmExitRequested)
        vm.onAction(ReviewExitAction.ConfirmExitRequested)
        vm.onAction(ReviewExitAction.ContinueReviewRequested)
        advanceUntilIdle()
        vm.onAction(ReviewExitAction.ConfirmExitRequested)
        vm.onAction(ReviewExitAction.ContinueReviewRequested)
        advanceUntilIdle()
        assertEquals(1, repository.calls)
        assertEquals(listOf(ReviewExitEffect.ReturnHome), effects)
        collector.cancel()
    }

    @Test
    fun storageExceptionKeepsExitRetryable() = runTest(dispatcher) {
        val repository = FakeRepository(throws = true)
        val vm = ReviewExitViewModel("r2", 1, repository)
        vm.onAction(ReviewExitAction.ConfirmExitRequested)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.discardFailed)
        assertFalse(vm.uiState.value.discarding)
        repository.throws = false
        vm.onAction(ReviewExitAction.ConfirmExitRequested)
        advanceUntilIdle()
        assertEquals(2, repository.calls)
    }

    @Test
    fun unreadableCompletedCountRemainsUnknownInsteadOfBecomingZero() = runTest(dispatcher) {
        val vm = ReviewExitViewModel("r2", -1, FakeRepository(countThrows = true))
        advanceUntilIdle()
        assertEquals(-1, vm.uiState.value.completedChapterCount)
        assertFalse(vm.uiState.value.discarding)
    }

    @Test
    fun continueReviewClosesDialogWithoutDeletingWorkingState() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = ReviewExitViewModel("r2", 1, repository)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(ReviewExitAction.ContinueReviewRequested)
        advanceUntilIdle()
        assertEquals(ReviewExitEffect.ContinueReview, effect.await())
        assertEquals(0, repository.calls)
    }

    @Test
    fun confirmedExitDiscardsOnlyCurrentWorkingStateAndReturnsHome() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = ReviewExitViewModel("r2", 1, repository)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(ReviewExitAction.ConfirmExitRequested)
        advanceUntilIdle()
        assertEquals(ReviewExitEffect.ReturnHome, effect.await())
        assertEquals(listOf("r2"), repository.chapterIds)
        assertEquals(1, viewModel.uiState.value.completedChapterCount)
        assertFalse(viewModel.uiState.value.currentChapterCountsTowardDailyLimit)
    }

    @Test
    fun alreadyDiscardedIsIdempotentAndReturnsHome() = runTest(dispatcher) {
        val viewModel = ReviewExitViewModel(
            "r2",
            2,
            FakeRepository(ReviewExitResult.AlreadyDiscarded),
        )
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(ReviewExitAction.ConfirmExitRequested)
        advanceUntilIdle()
        assertEquals(ReviewExitEffect.ReturnHome, effect.await())
    }

    @Test
    fun failedDiscardStaysOnDialogAndCanRetry() = runTest(dispatcher) {
        val repository = FakeRepository(ReviewExitResult.Failed)
        val viewModel = ReviewExitViewModel("r2", 1, repository)
        viewModel.onAction(ReviewExitAction.ConfirmExitRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.discardFailed)
        repository.result = ReviewExitResult.Discarded
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(ReviewExitAction.ConfirmExitRequested)
        advanceUntilIdle()
        assertEquals(ReviewExitEffect.ReturnHome, effect.await())
        assertEquals(2, repository.calls)
    }

    private class FakeRepository(
        var result: ReviewExitResult = ReviewExitResult.Discarded,
        var throws: Boolean = false,
        val countThrows: Boolean = false,
    ) : ReviewExitRepository {
        val chapterIds = mutableListOf<String>()
        val calls get() = chapterIds.size
        override suspend fun completedChapterCount(reviewChapterId: String): Int {
            if (countThrows) error("Isolated count failure")
            return 1
        }
        override suspend fun discardWorkingState(reviewChapterId: String): ReviewExitResult {
            chapterIds += reviewChapterId
            if (throws) error("Isolated storage failure")
            return result
        }
    }
}
