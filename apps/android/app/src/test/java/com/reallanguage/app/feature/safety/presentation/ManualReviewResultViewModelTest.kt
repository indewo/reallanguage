package com.reallanguage.app.feature.safety.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.safety.domain.ManualReviewResult
import com.reallanguage.app.feature.safety.domain.ManualReviewResultRepository
import com.reallanguage.app.feature.safety.domain.ReviewedSafetySource
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManualReviewResultViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    @Test fun approvedResultOpensSplitWithoutRepeatingSafetyCheck() = runTest {
        val viewModel = ManualReviewResultViewModel("review", FakeRepository(ManualReviewResult.Ready(ManualReviewOutcome.APPROVED, source)))
        advanceUntilIdle(); val effect = async { viewModel.effects.first() }
        viewModel.onAction(ManualReviewResultAction.OpenFullSentenceSplitRequested)
        assertEquals(ManualReviewResultEffect.OpenSemanticSplit(source), effect.await())
    }
    @Test fun notApprovedResultReturnsToTheExactSafetyCase() = runTest {
        val viewModel = ManualReviewResultViewModel("review", FakeRepository(ManualReviewResult.Ready(ManualReviewOutcome.NOT_APPROVED, source)))
        advanceUntilIdle(); val effect = async { viewModel.effects.first() }
        viewModel.onAction(ManualReviewResultAction.ReturnToSafetyHandlingRequested)
        assertEquals(ManualReviewResultEffect.OpenSafetyHandling("case", "内容"), effect.await())
    }
    @Test fun loadFailureStaysOnResultAndCanRetry() = runTest {
        val repository = FakeRepository(ManualReviewResult.Failed)
        val viewModel = ManualReviewResultViewModel("review", repository)
        advanceUntilIdle(); assertTrue(viewModel.uiState.value.loadFailed)
        repository.result = ManualReviewResult.Ready(ManualReviewOutcome.APPROVED, source)
        viewModel.onAction(ManualReviewResultAction.RetryRequested); advanceUntilIdle()
        assertEquals(ManualReviewOutcome.APPROVED, viewModel.uiState.value.outcome)
    }
    @Test fun expiredSessionRequestsLoginWithTheExactTarget() = runTest {
        val viewModel = ManualReviewResultViewModel("review", FakeRepository(ManualReviewResult.RequiresLogin))
        assertEquals(ManualReviewResultEffect.OpenLoginPreservingTarget("review"), viewModel.effects.first())
    }
    private class FakeRepository(var result: ManualReviewResult) : ManualReviewResultRepository { override suspend fun load(reviewRequestId: String) = result }
    companion object { private val source = ReviewedSafetySource("case", "内容", SourceType.DIARY, SourceContentShape.ARTICLE, "已完成复核") }
}
