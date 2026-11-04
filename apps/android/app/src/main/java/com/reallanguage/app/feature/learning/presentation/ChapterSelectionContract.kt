package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.RemainingChapterSummary

data class ChapterSelectionUiState(
    val sourceId: String = "",
    val chapters: List<RemainingChapterSummary> = emptyList(),
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
)

sealed interface ChapterSelectionAction {
    data class ChapterSelected(val chapterId: String) : ChapterSelectionAction
    data object BackRequested : ChapterSelectionAction
    data object LearnLaterRequested : ChapterSelectionAction
    data object RetryLoadRequested : ChapterSelectionAction
}

sealed interface ChapterSelectionEffect {
    data class OpenChapter(val chapterId: String) : ChapterSelectionEffect
    data object ReturnToPrevious : ChapterSelectionEffect
    data object ReturnHome : ChapterSelectionEffect
}
