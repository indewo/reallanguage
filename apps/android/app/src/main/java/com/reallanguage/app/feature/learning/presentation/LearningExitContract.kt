package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint

data class LearningExitUiState(
    val checkpoint: OrdinaryLearningCheckpoint,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
)

sealed interface LearningExitAction {
    data object ContinueLearningRequested : LearningExitAction
    data object PauseLearningRequested : LearningExitAction
    data object AbandonChapterRequested : LearningExitAction
}

sealed interface LearningExitEffect {
    data object CloseDialog : LearningExitEffect
    data object ReturnHome : LearningExitEffect
    data object OpenAbandonConfirmation : LearningExitEffect
}
