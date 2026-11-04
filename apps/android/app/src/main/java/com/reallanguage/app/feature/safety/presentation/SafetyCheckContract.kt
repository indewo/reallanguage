package com.reallanguage.app.feature.safety.presentation

import com.reallanguage.app.feature.safety.domain.ConfirmedSource

data class SafetyCheckUiState(
    val source: ConfirmedSource,
    val processing: Boolean = false,
    val failed: Boolean = false,
)

sealed interface SafetyCheckAction {
    data object RetryRequested : SafetyCheckAction
}

sealed interface SafetyCheckEffect {
    data class OpenFullSentenceSplit(val source: ConfirmedSource) : SafetyCheckEffect
    data class OpenSafetyHandling(
        val safetyCaseId: String,
        val source: ConfirmedSource,
    ) : SafetyCheckEffect
}
