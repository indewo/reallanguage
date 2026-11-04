package com.reallanguage.app.feature.safety.presentation

import com.reallanguage.app.feature.safety.domain.SourceType

data class ManualReviewPendingUiState(
    val sourceType: SourceType,
)

sealed interface ManualReviewPendingAction {
    data object ReturnHomeRequested : ManualReviewPendingAction
}
