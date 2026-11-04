package com.reallanguage.app.feature.learning.domain

enum class ChapterResultCategory {
    SENTENCE,
    MEANING,
    PATTERN,
    CORRECTION,
    WRONG,
    INCOMPLETE,
}

data class ChapterResultSummaryItem(
    val category: ChapterResultCategory,
    val labelZh: String,
    val completedCount: Int,
    val totalCount: Int,
)

data class ChapterArchiveSummary(
    val chapterId: String,
    val items: List<ChapterResultSummaryItem>,
    val hasNextChapter: Boolean,
    val chapterNumber: Int? = null,
    val reinforcementItems: List<ChapterReinforcementItem> = emptyList(),
)

data class ChapterReinforcementItem(
    val sentenceId: String,
    val capabilityType: String,
    val targetId: String,
    val display: String,
    val explanationZh: String? = null,
)

sealed interface ChapterArchiveResult {
    data class Archived(val summary: ChapterArchiveSummary) : ChapterArchiveResult
    data class AlreadyArchived(val summary: ChapterArchiveSummary) : ChapterArchiveResult
    data object Failed : ChapterArchiveResult
}

fun interface ChapterArchiveRepository {
    suspend fun archive(chapterId: String, commandId: String): ChapterArchiveResult
}
