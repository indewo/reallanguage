package com.reallanguage.app.feature.formation.domain

sealed interface EnglishGenerationResult {
    data class Generated(val chapters: List<EnglishChapterDraft>) : EnglishGenerationResult
    data object Failed : EnglishGenerationResult
}

fun interface EnglishGenerationRepository {
    suspend fun generate(
        units: List<SemanticUnit>,
        selectedUnitIds: List<String>,
        chapters: List<ChapterDraft>,
    ): EnglishGenerationResult
}
