package com.reallanguage.app.feature.learning.domain

sealed interface ChapterAbandonResult {
    data class Abandoned(
        val hasRemainingChapters: Boolean,
        val sourceId: String? = null,
    ) : ChapterAbandonResult
    data object AlreadyArchived : ChapterAbandonResult
    data object Failed : ChapterAbandonResult
}

fun interface ChapterAbandonRepository {
    suspend fun abandonUnarchivedChapter(
        chapterId: String,
        commandId: String,
    ): ChapterAbandonResult
}
