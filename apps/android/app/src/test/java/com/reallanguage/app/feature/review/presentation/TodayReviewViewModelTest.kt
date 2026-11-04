package com.reallanguage.app.feature.review.presentation

import com.reallanguage.app.feature.review.domain.ReviewChapterSummary
import com.reallanguage.app.feature.review.domain.ReviewQueueResult
import com.reallanguage.app.feature.review.domain.ReviewSourceType
import com.reallanguage.app.feature.review.domain.TodayReviewRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TodayReviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loadsRealQueueWithDailyLimitAndPreservedSourceIdentity() = runTest(dispatcher) {
        val repository = FakeRepository(ReviewQueueResult.Ready(chapters))
        val viewModel = TodayReviewViewModel(repository)
        advanceUntilIdle()

        assertEquals(3, repository.requestedLimit)
        assertEquals(chapters, viewModel.uiState.value.chapters)
        assertEquals(3, viewModel.uiState.value.dailyFocusSentenceLimit)
        assertFalse(viewModel.uiState.value.loading)
        assertEquals(ReviewSourceType.DIARY, viewModel.uiState.value.chapters.first().sourceType)
    }

    @Test
    fun currentChapterIsTheFirstRepositoryOrderedChapter() = runTest(dispatcher) {
        val viewModel = TodayReviewViewModel(FakeRepository(ReviewQueueResult.Ready(chapters)))
        advanceUntilIdle()

        assertEquals("review-c1", viewModel.uiState.value.currentChapter?.reviewChapterId)
    }

    @Test fun dayLimitIsNotReportedAsAnEmptyQueueAndClearsOnReload() = runTest(dispatcher) {
        val repository = FakeRepository(ReviewQueueResult.Ready(emptyList(), deferredByDailyLimit = true))
        val viewModel = TodayReviewViewModel(repository)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.deferredByDailyLimit)
        repository.result = ReviewQueueResult.Ready(chapters)
        viewModel.onAction(TodayReviewAction.RetryRequested)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.deferredByDailyLimit)
    }

    @Test
    fun startOnlyOpensTheCurrentReviewChapter() = runTest(dispatcher) {
        val viewModel = TodayReviewViewModel(FakeRepository(ReviewQueueResult.Ready(chapters)))
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }

        viewModel.onAction(TodayReviewAction.StartCurrentChapterRequested)
        advanceUntilIdle()

        assertEquals(TodayReviewEffect.OpenReviewChapter("review-c1"), effect.await())
    }

    @Test
    fun emptyQueueCannotStartAndCanReturnHome() = runTest(dispatcher) {
        val viewModel = TodayReviewViewModel(FakeRepository(ReviewQueueResult.Ready(emptyList())))
        advanceUntilIdle()
        viewModel.onAction(TodayReviewAction.StartCurrentChapterRequested)
        advanceUntilIdle()
        assertNull(withTimeoutOrNull(1) { viewModel.effects.first() })

        val effect = async { viewModel.effects.first() }
        viewModel.onAction(TodayReviewAction.ReturnHomeRequested)
        advanceUntilIdle()
        assertEquals(TodayReviewEffect.ReturnHome, effect.await())
    }

    @Test
    fun failedQueueLoadShowsRetryAndRecoversWithoutInventedItems() = runTest(dispatcher) {
        val repository = FakeRepository(ReviewQueueResult.Failed)
        val viewModel = TodayReviewViewModel(repository)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.loadFailed)
        assertTrue(viewModel.uiState.value.chapters.isEmpty())

        repository.result = ReviewQueueResult.Ready(chapters)
        viewModel.onAction(TodayReviewAction.RetryRequested)
        advanceUntilIdle()

        assertEquals(chapters, viewModel.uiState.value.chapters)
        assertFalse(viewModel.uiState.value.loadFailed)
    }

    private class FakeRepository(
        var result: ReviewQueueResult,
    ) : TodayReviewRepository {
        var requestedLimit: Int? = null
        var calls = 0

        override suspend fun loadTodayQueue(maxFocusSentences: Int): ReviewQueueResult {
            calls++
            requestedLimit = maxFocusSentences
            return result
        }
    }

    @Test fun repeatedRetryCannotStartConcurrentQueueReads() = runTest(dispatcher) {
        val repository = FakeRepository(ReviewQueueResult.Ready(chapters))
        val viewModel = TodayReviewViewModel(repository)
        repeat(3) { viewModel.onAction(TodayReviewAction.RetryRequested) }
        advanceUntilIdle()
        assertEquals(1, repository.calls)
    }

    @Test fun thrownReadStopsLoadingAndCanRetry() = runTest(dispatcher) {
        var calls = 0
        val repository = object : TodayReviewRepository {
            override suspend fun loadTodayQueue(maxFocusSentences: Int): ReviewQueueResult {
                calls++
                if (calls == 1) error("isolated read failure")
                return ReviewQueueResult.Ready(chapters)
            }
        }
        val viewModel = TodayReviewViewModel(repository)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.loading)
        assertTrue(viewModel.uiState.value.loadFailed)
        viewModel.onAction(TodayReviewAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(chapters, viewModel.uiState.value.chapters)
    }

    companion object {
        private val chapters = listOf(
            ReviewChapterSummary(
                "review-c1",
                "fixed-c1",
                ReviewSourceType.DIARY,
                "昨晚的日记",
                "I finally spoke up.",
                1,
                2,
            ),
            ReviewChapterSummary(
                "review-c2",
                "fixed-c4",
                ReviewSourceType.QUICK_RECORDING,
                "通勤录音",
                "The train was delayed again.",
                2,
                3,
            ),
        )
    }
}
