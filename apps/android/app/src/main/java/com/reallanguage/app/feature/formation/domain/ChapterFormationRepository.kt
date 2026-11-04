package com.reallanguage.app.feature.formation.domain

data class ChapterDraft(
    val chapterId: String,
    val unitIds: List<String>,
)

sealed interface ChapterFormationResult {
    data class Formed(val chapters: List<ChapterDraft>) : ChapterFormationResult
    data object Failed : ChapterFormationResult
}

fun interface ChapterFormationRepository {
    suspend fun formChapters(
        units: List<SemanticUnit>,
        selectedUnitIds: List<String>,
    ): ChapterFormationResult
}
