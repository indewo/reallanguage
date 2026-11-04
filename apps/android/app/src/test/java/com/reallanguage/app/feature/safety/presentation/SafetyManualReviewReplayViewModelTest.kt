package com.reallanguage.app.feature.safety.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.safety.domain.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SafetyManualReviewReplayViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    @Test fun approvedAndRejectedAcknowledgementsOpenTheExistingResultInsteadOfPending() = runTest {
        for (status in listOf(ManualReviewSubmissionStatus.APPROVED, ManualReviewSubmissionStatus.NOT_APPROVED)) {
            val repository = Repository(mutableListOf(ManualReviewSubmissionResult.Submitted("review-1", status)))
            val viewModel = SafetyHandlingViewModel(SafetyHandlingViewModelTest.testDetails(), repository)
            val effects = mutableListOf<SafetyHandlingEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
            assertEquals(0, repository.calls)
            repeat(2) { viewModel.onAction(SafetyHandlingAction.SubmitManualReviewRequested) }
            advanceUntilIdle()
            assertEquals(1, repository.calls)
            assertEquals(listOf(SafetyHandlingEffect.OpenExistingReviewResult("review-1")), effects)
        }
    }
    @Test fun failedAcknowledgementKeepsTheCaseAndRetryCanResumeTheAdjudicatedReview() = runTest {
        val repository = Repository(mutableListOf(ManualReviewSubmissionResult.Failed, ManualReviewSubmissionResult.Submitted("review-1", ManualReviewSubmissionStatus.NOT_APPROVED)))
        val details = SafetyHandlingViewModelTest.testDetails()
        val viewModel = SafetyHandlingViewModel(details, repository)
        val effects = mutableListOf<SafetyHandlingEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
        viewModel.onAction(SafetyHandlingAction.SubmitManualReviewRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.commandFailed)
        assertEquals(details, viewModel.uiState.value.details)
        viewModel.onAction(SafetyHandlingAction.SubmitManualReviewRequested)
        advanceUntilIdle()
        assertEquals(2, repository.calls)
        assertEquals(listOf(SafetyHandlingEffect.OpenExistingReviewResult("review-1")), effects)
    }
    @Test fun aGenuinelyPendingAcknowledgementKeepsTheExistingWaitingRoute() = runTest {
        val repository = Repository(mutableListOf(ManualReviewSubmissionResult.Submitted("review-1", ManualReviewSubmissionStatus.PENDING)))
        val viewModel = SafetyHandlingViewModel(SafetyHandlingViewModelTest.testDetails(), repository)
        val effects = mutableListOf<SafetyHandlingEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
        viewModel.onAction(SafetyHandlingAction.SubmitManualReviewRequested)
        advanceUntilIdle()
        assertEquals(listOf(SafetyHandlingEffect.OpenReviewPending("review-1", "case-1")), effects)
    }
    private class Repository(val responses: MutableList<ManualReviewSubmissionResult>) : SafetyHandlingRepository {
        var calls = 0
        override suspend fun submitManualReview(safetyCaseId: String): ManualReviewSubmissionResult { calls++; return responses.removeAt(0) }
        override suspend fun acceptDeidentified(safetyCaseId: String) = SafetyHandlingCommandResult.Failed
        override suspend fun returnToEdit(safetyCaseId: String) = SafetyHandlingCommandResult.Failed
    }
}
