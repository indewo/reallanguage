package com.reallanguage.app.feature.safety.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.safety.domain.ManualReviewSubmissionResult
import com.reallanguage.app.feature.safety.domain.ManualReviewSubmissionStatus
import com.reallanguage.app.feature.safety.domain.SafetyCaseDetails
import com.reallanguage.app.feature.safety.domain.SafetyHandlingCommandResult
import com.reallanguage.app.feature.safety.domain.SafetyHandlingRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SafetyHandlingViewModel(
    details: SafetyCaseDetails,
    private val repository: SafetyHandlingRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SafetyHandlingUiState(details))
    val uiState: StateFlow<SafetyHandlingUiState> = mutableUiState.asStateFlow()

    private val effectChannel = Channel<SafetyHandlingEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    fun onAction(action: SafetyHandlingAction) {
        if (mutableUiState.value.submitting) return
        when (action) {
            SafetyHandlingAction.UseDeidentifiedRequested -> acceptDeidentified()
            SafetyHandlingAction.ReturnToModifyRequested -> returnToModify()
            SafetyHandlingAction.SubmitManualReviewRequested -> submitManualReview()
        }
    }

    private fun acceptDeidentified() = runCommand(
        command = { caseId -> repository.acceptDeidentified(caseId) },
        onCompleted = {
            effectChannel.send(
                SafetyHandlingEffect.OpenFullSentenceSplit(
                    mutableUiState.value.details.deidentifiedSource,
                ),
            )
        },
    )

    private fun returnToModify() = runCommand(
        command = { caseId -> repository.returnToEdit(caseId) },
        onCompleted = {
            effectChannel.send(
                SafetyHandlingEffect.OpenSourceEditor(
                    mutableUiState.value.details.originalSource,
                ),
            )
        },
    )

    private fun submitManualReview() {
        startSubmitting()
        viewModelScope.launch {
            when (
                val result = repository.submitManualReview(
                    mutableUiState.value.details.safetyCaseId,
                )
            ) {
                is ManualReviewSubmissionResult.Submitted -> effectChannel.send(
                    if (result.reviewStatus == ManualReviewSubmissionStatus.PENDING) SafetyHandlingEffect.OpenReviewPending(
                        reviewRequestId = result.reviewRequestId,
                        safetyCaseId = mutableUiState.value.details.safetyCaseId,
                    ) else SafetyHandlingEffect.OpenExistingReviewResult(result.reviewRequestId),
                )
                ManualReviewSubmissionResult.Failed -> finishWithFailure()
            }
        }
    }

    private fun runCommand(
        command: suspend (String) -> SafetyHandlingCommandResult,
        onCompleted: suspend () -> Unit,
    ) {
        startSubmitting()
        viewModelScope.launch {
            when (command(mutableUiState.value.details.safetyCaseId)) {
                SafetyHandlingCommandResult.Completed -> onCompleted()
                SafetyHandlingCommandResult.Failed -> finishWithFailure()
            }
        }
    }

    private fun startSubmitting() {
        mutableUiState.update { it.copy(submitting = true, commandFailed = false) }
    }

    private fun finishWithFailure() {
        mutableUiState.update { it.copy(submitting = false, commandFailed = true) }
    }
}
