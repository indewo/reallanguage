package com.reallanguage.app.feature.safety.presentation

import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.SafetyCaseDetails

data class SafetyHandlingUiState(
    val details: SafetyCaseDetails,
    val submitting: Boolean = false,
    val commandFailed: Boolean = false,
    val sourceEditorUnavailable: Boolean = false,
)

sealed interface SafetyHandlingAction {
    data object UseDeidentifiedRequested : SafetyHandlingAction
    data object ReturnToModifyRequested : SafetyHandlingAction
    data object SubmitManualReviewRequested : SafetyHandlingAction
}

sealed interface SafetyHandlingEffect {
    data class OpenExistingReviewResult(val reviewRequestId: String) : SafetyHandlingEffect
    data class OpenFullSentenceSplit(val source: ConfirmedSource) : SafetyHandlingEffect
    data class OpenSourceEditor(val source: ConfirmedSource) : SafetyHandlingEffect
    data class OpenReviewPending(
        val reviewRequestId: String,
        val safetyCaseId: String,
    ) : SafetyHandlingEffect
}
