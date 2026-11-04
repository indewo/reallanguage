package com.reallanguage.app.feature.learning.domain

data class RemainingChapterSummary(
    val chapterId: String,
    val chapterNumber: Int,
    val identityZh: String,
)

sealed interface ChapterSelectionLoadResult {
    data class Ready(
        val sourceId: String,
        val chapters: List<RemainingChapterSummary>,
    ) : ChapterSelectionLoadResult

    data object Failed : ChapterSelectionLoadResult
}

sealed interface ChapterSelectionBoundary {
    data class ArchivedChapter(val chapterId: String) : ChapterSelectionBoundary
    data class FixedSource(val sourceId: String) : ChapterSelectionBoundary
}

fun interface ChapterSelectionRepository {
    suspend fun loadRemaining(boundary: ChapterSelectionBoundary): ChapterSelectionLoadResult
}
