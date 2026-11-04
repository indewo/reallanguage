package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.ChapterArchiveSummary

data class ChapterResultUiState(
    val chapterId: String,
    val commandId: String,
    val chapterNumber: Int? = null,
    val archiving: Boolean = false,
    val archiveFailed: Boolean = false,
    val summary: ChapterArchiveSummary? = null,
)

sealed interface ChapterResultAction {
    data object RetryArchiveRequested : ChapterResultAction
    data object EnterNextChapterRequested : ChapterResultAction
    data object LearnLaterRequested : ChapterResultAction
    data object ReturnHomeRequested : ChapterResultAction
}

sealed interface ChapterResultEffect {
    data object OpenChapterSelection : ChapterResultEffect
    data object ReturnHome : ChapterResultEffect
}
