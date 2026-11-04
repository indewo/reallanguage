package com.reallanguage.app.feature.safety.presentation

import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.ManualReviewSubmissionResult
import com.reallanguage.app.feature.safety.domain.SafetyCaseDetails
import com.reallanguage.app.feature.safety.domain.SafetyEdit
import com.reallanguage.app.feature.safety.domain.SafetyHandlingCommandResult
import com.reallanguage.app.feature.safety.domain.SafetyHandlingRepository
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
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
class SafetyHandlingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val details = testDetails()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun doesNotSubmitManualReviewWithoutAnExplicitUserAction() {
        val repository = RecordingRepository()
        SafetyHandlingViewModel(details, repository)
        assertEquals(0, repository.reviewSubmissions)
    }

    @Test
    fun acceptingDeidentifiedVersionOpensSplitWithoutAnotherSafetyCheck() = runTest(dispatcher) {
        val repository = RecordingRepository()
        val viewModel = SafetyHandlingViewModel(details, repository)
        val effect = async { viewModel.effects.first() }

        viewModel.onAction(SafetyHandlingAction.UseDeidentifiedRequested)
        viewModel.onAction(SafetyHandlingAction.UseDeidentifiedRequested)
        advanceUntilIdle()

        assertEquals(1, repository.acceptances)
        assertEquals(
            SafetyHandlingEffect.OpenFullSentenceSplit(details.deidentifiedSource),
            effect.await(),
        )
    }

    @Test
    fun returningToModifyReleasesCaseAndPreservesOriginalSource() = runTest(dispatcher) {
        val repository = RecordingRepository()
        val viewModel = SafetyHandlingViewModel(details, repository)
        val effect = async { viewModel.effects.first() }

        viewModel.onAction(SafetyHandlingAction.ReturnToModifyRequested)
        advanceUntilIdle()

        assertEquals(1, repository.returnsToEdit)
        assertEquals(
            SafetyHandlingEffect.OpenSourceEditor(details.originalSource),
            effect.await(),
        )
    }

    @Test
    fun manualReviewIsCreatedOnlyAfterExplicitSubmission() = runTest(dispatcher) {
        val repository = RecordingRepository()
        val viewModel = SafetyHandlingViewModel(details, repository)
        val effect = async { viewModel.effects.first() }

        viewModel.onAction(SafetyHandlingAction.SubmitManualReviewRequested)
        advanceUntilIdle()

        assertEquals(1, repository.reviewSubmissions)
        assertEquals(
            SafetyHandlingEffect.OpenReviewPending("review-1", "case-1"),
            effect.await(),
        )
    }

    @Test
    fun failedCommandKeepsCaseAndShowsRetryableFailure() = runTest(dispatcher) {
        val repository = RecordingRepository(
            commandResult = SafetyHandlingCommandResult.Failed,
        )
        val viewModel = SafetyHandlingViewModel(details, repository)

        viewModel.onAction(SafetyHandlingAction.UseDeidentifiedRequested)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.submitting)
        assertTrue(viewModel.uiState.value.commandFailed)
        assertEquals(details, viewModel.uiState.value.details)
    }

    private class RecordingRepository(
        private val commandResult: SafetyHandlingCommandResult =
            SafetyHandlingCommandResult.Completed,
    ) : SafetyHandlingRepository {
        var acceptances = 0
        var returnsToEdit = 0
        var reviewSubmissions = 0

        override suspend fun acceptDeidentified(
            safetyCaseId: String,
        ): SafetyHandlingCommandResult {
            acceptances += 1
            return commandResult
        }

        override suspend fun returnToEdit(
            safetyCaseId: String,
        ): SafetyHandlingCommandResult {
            returnsToEdit += 1
            return commandResult
        }

        override suspend fun submitManualReview(
            safetyCaseId: String,
        ): ManualReviewSubmissionResult {
            reviewSubmissions += 1
            return if (commandResult == SafetyHandlingCommandResult.Completed) {
                ManualReviewSubmissionResult.Submitted("review-1")
            } else {
                ManualReviewSubmissionResult.Failed
            }
        }
    }

    companion object {
        fun testDetails() = SafetyCaseDetails(
            safetyCaseId = "case-1",
            originalSource = ConfirmedSource(
                text = "原始内容包含具体姓名张三。",
                sourceType = SourceType.DIARY,
                contentShape = SourceContentShape.ARTICLE,
            ),
            userReasonZh = "内容包含需要保护的具体身份信息。",
            deidentifiedSource = ConfirmedSource(
                text = "原始内容包含某位同事。",
                sourceType = SourceType.DIARY,
                contentShape = SourceContentShape.ARTICLE,
            ),
            edits = listOf(
                SafetyEdit(
                    originalText = "张三",
                    replacementText = "某位同事",
                    explanationZh = "隐藏具体姓名，同时保留人物关系。",
                ),
            ),
        )
    }
}
