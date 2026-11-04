package com.reallanguage.app.feature.safety.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.safety.domain.ManualReviewResult
import com.reallanguage.app.feature.safety.domain.ManualReviewResultRepository
import com.reallanguage.app.feature.safety.domain.ReviewedSafetySource
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManualReviewUnavailableViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun unavailableHasNoRetryOrLearningActionButASecondVisitMayReadANewlyAvailableResult() = runTest {
        var calls = 0
        var next: ManualReviewResult = ManualReviewResult.Unavailable
        val repository = ManualReviewResultRepository { calls++; next }
        val viewModel = ManualReviewResultViewModel("review-1", repository)
        val effects = mutableListOf<ManualReviewResultEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.unavailable)
        assertFalse(viewModel.uiState.value.loading)
        assertFalse(viewModel.uiState.value.loadFailed)
        repeat(3) {
            viewModel.onAction(ManualReviewResultAction.RetryRequested)
            viewModel.onAction(ManualReviewResultAction.OpenFullSentenceSplitRequested)
            viewModel.onAction(ManualReviewResultAction.ReturnToSafetyHandlingRequested)
        }
        advanceUntilIdle()
        assertEquals(1, calls)
        assertTrue(effects.isEmpty())
        next = ready
        val freshVisit = ManualReviewResultViewModel("review-1", repository)
        advanceUntilIdle()
        assertEquals(ManualReviewOutcome.APPROVED, freshVisit.uiState.value.outcome)
    }

    @Test fun returningToMessagesIsExplicitAndDispatchedOnlyOnce() = runTest {
        val viewModel = ManualReviewResultViewModel("review-1") { ManualReviewResult.Unavailable }
        val effects = mutableListOf<ManualReviewResultEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
        advanceUntilIdle()
        repeat(2) { viewModel.onAction(ManualReviewResultAction.ReturnMessagesRequested) }
        advanceUntilIdle()
        assertEquals(listOf(ManualReviewResultEffect.OpenMessages), effects)
    }

    @Test fun aRefreshCannotReuseAnOldSourceOrLaunchOverlappingRequests() = runTest {
        var calls = 0
        val refreshed = CompletableDeferred<ManualReviewResult>()
        val viewModel = ManualReviewResultViewModel("review-1") { if (++calls == 1) ready else refreshed.await() }
        val effects = mutableListOf<ManualReviewResultEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
        advanceUntilIdle()
        viewModel.onAction(ManualReviewResultAction.RetryRequested)
        runCurrent()
        viewModel.onAction(ManualReviewResultAction.RetryRequested)
        viewModel.onAction(ManualReviewResultAction.OpenFullSentenceSplitRequested)
        runCurrent()
        assertEquals(2, calls)
        assertTrue(effects.isEmpty())
        refreshed.complete(ManualReviewResult.Unavailable)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.unavailable)
        assertNull(viewModel.uiState.value.outcome)
    }

    @Test fun returningDuringALoadCannotPublishALateReadySourceOrNavigation() = runTest {
        val response = CompletableDeferred<ManualReviewResult>()
        val viewModel = ManualReviewResultViewModel("review-1") { withContext(NonCancellable) { response.await() } }
        val effects = mutableListOf<ManualReviewResultEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
        runCurrent()
        viewModel.onAction(ManualReviewResultAction.ReturnMessagesRequested)
        response.complete(ready)
        advanceUntilIdle()
        viewModel.onAction(ManualReviewResultAction.OpenFullSentenceSplitRequested)
        assertNull(viewModel.uiState.value.outcome)
        assertEquals(listOf(ManualReviewResultEffect.OpenMessages), effects)
    }

    private val ready = ManualReviewResult.Ready(ManualReviewOutcome.APPROVED,
        ReviewedSafetySource("case-1", "只在有效结果中显示的中性来源", SourceType.DIARY, SourceContentShape.ARTICLE, "通过"))
}
