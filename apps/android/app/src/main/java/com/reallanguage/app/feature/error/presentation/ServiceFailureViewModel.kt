package com.reallanguage.app.feature.error.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.error.domain.FailedStepRetryRepository
import com.reallanguage.app.feature.error.domain.FailedStepRetryResult
import com.reallanguage.app.feature.error.domain.ServiceFailureCategory
import com.reallanguage.app.feature.error.domain.ServiceFailureContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ServiceFailureViewModel(
    context: ServiceFailureContext,
    private val commandId: String,
    private val repository: FailedStepRetryRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ServiceFailureUiState(context))
    val uiState: StateFlow<ServiceFailureUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<ServiceFailureEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    fun onAction(action: ServiceFailureAction) {
        when (action) {
            ServiceFailureAction.RetryRequested -> retry()
            ServiceFailureAction.ReturnRequested -> send(
                ServiceFailureEffect.UseStepReturnPath(mutableUiState.value.context.stepId),
            )
        }
    }

    private fun retry() {
        val context = mutableUiState.value.context
        if (context.category == ServiceFailureCategory.RECORDING_TRANSCRIPTION) {
            send(ServiceFailureEffect.OpenRecordingTranscriptionFailure)
            return
        }
        if (mutableUiState.value.retrying) return
        viewModelScope.launch {
            mutableUiState.update { it.copy(retrying = true, retryFailed = false) }
            when (repository.retryStep(context.stepId, commandId)) {
                FailedStepRetryResult.Started -> {
                    mutableUiState.update { it.copy(retrying = false) }
                    send(ServiceFailureEffect.ReturnToStep(context.stepId))
                }
                FailedStepRetryResult.Failed -> mutableUiState.update {
                    it.copy(retrying = false, retryFailed = true)
                }
            }
        }
    }

    private fun send(effect: ServiceFailureEffect) { viewModelScope.launch { effectChannel.send(effect) } }
}
