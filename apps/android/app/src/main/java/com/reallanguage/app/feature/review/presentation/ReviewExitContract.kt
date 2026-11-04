package com.reallanguage.app.feature.review.presentation

data class ReviewExitUiState(
    val reviewChapterId: String,
    val completedChapterCount: Int,
    val discarding: Boolean = false,
    val discardFailed: Boolean = false,
    val currentChapterCountsTowardDailyLimit: Boolean = false,
    val completed: Boolean = false,
)

sealed interface ReviewExitAction {
    data object ContinueReviewRequested : ReviewExitAction
    data object ConfirmExitRequested : ReviewExitAction
}

sealed interface ReviewExitEffect {
    data object ContinueReview : ReviewExitEffect
    data object ReturnHome : ReviewExitEffect
}
