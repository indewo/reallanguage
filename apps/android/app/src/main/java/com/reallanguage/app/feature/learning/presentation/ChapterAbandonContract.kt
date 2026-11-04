package com.reallanguage.app.feature.learning.presentation

data class ChapterAbandonUiState(
    val chapterId: String,
    val commandId: String,
    val deleting: Boolean = false,
    val deleteFailed: Boolean = false,
    val alreadyArchived: Boolean = false,
    val completed: Boolean = false,
)

sealed interface ChapterAbandonAction {
    data object ContinueLearningRequested : ChapterAbandonAction
    data object ConfirmAbandonRequested : ChapterAbandonAction
}

sealed interface ChapterAbandonEffect {
    data object ReturnToLearning : ChapterAbandonEffect
    data class OpenChapterSelection(val sourceId: String) : ChapterAbandonEffect
    data object ReturnHome : ChapterAbandonEffect
}
