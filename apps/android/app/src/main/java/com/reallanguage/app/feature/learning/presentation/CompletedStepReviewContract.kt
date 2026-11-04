package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.CompletedStepSnapshot

data class CompletedStepReviewUiState(
    val snapshot: CompletedStepSnapshot,
    val audioPlaying: Boolean = false,
    val explanationVisible: Boolean = false,
    val audioFailed: Boolean = false,
    val returning: Boolean = false,
    val loading: Boolean = false,
    val loadingFailed: Boolean = false,
)

sealed interface CompletedStepReviewAction {
    data object AudioToggled : CompletedStepReviewAction
    data object ExpressionAudioToggled : CompletedStepReviewAction
    data object ExplanationToggled : CompletedStepReviewAction
    data object RetryLoadRequested : CompletedStepReviewAction
    data object ReturnToCurrentTaskRequested : CompletedStepReviewAction
}

sealed interface CompletedStepReviewEffect {
    data object ReturnToCurrentTask : CompletedStepReviewEffect
}
