package com.reallanguage.app.feature.error.presentation

import com.reallanguage.app.feature.error.domain.ServiceFailureContext

data class ServiceFailureUiState(
    val context: ServiceFailureContext,
    val preservedItemsZh: List<String> = emptyList(),
    val retrying: Boolean = false,
    val retryFailed: Boolean = false,
    val fixedContentCreated: Boolean = false,
    val learningStateChanged: Boolean = false,
    val downstreamChargeCreated: Boolean = false,
)

sealed interface ServiceFailureAction {
    data object RetryRequested : ServiceFailureAction
    data object ReturnRequested : ServiceFailureAction
}

sealed interface ServiceFailureEffect {
    data class ReturnToStep(val stepId: String) : ServiceFailureEffect
    data class UseStepReturnPath(val stepId: String) : ServiceFailureEffect
    data object OpenRecordingTranscriptionFailure : ServiceFailureEffect
}
