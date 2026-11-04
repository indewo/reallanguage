package com.reallanguage.app.feature.review.presentation

import com.reallanguage.app.feature.review.domain.ReviewResultSummary

data class ReviewResultUiState(
    val reviewSessionId: String,
    val commandId: String,
    val summary: ReviewResultSummary? = null,
    val settling: Boolean = true,
    val settlementFailed: Boolean = false,
)

sealed interface ReviewResultAction {
    data object RetryRequested : ReviewResultAction
    data object ReturnHomeRequested : ReviewResultAction
}

sealed interface ReviewResultEffect {
    data object ReturnHome : ReviewResultEffect
}
