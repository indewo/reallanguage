package com.reallanguage.app.feature.formation.domain

data class ContentFixRequest(
    val formationFlowId: String,
    val commandId: String,
    val chapters: List<EnglishChapterDraft>,
    val selectedCandidateIds: Map<String, String>,
    val targets: List<LearningTarget>,
    val selectedTargetIds: Set<String>,
    val sourceType: String = "DIARY",
    val sourceTitle: String = "日记",
    val sourceProjection: FixedSourceProjection? = null,
)

sealed interface ContentFixResult {
    data class Fixed(val firstChapterId: String) : ContentFixResult
    data object Failed : ContentFixResult
}

/**
 * Atomically locks the source, confirmed sentences, focus roles, and selected targets.
 * Implementations must treat [ContentFixRequest.commandId] as an idempotency key.
 */
fun interface ContentFixRepository {
    suspend fun fix(request: ContentFixRequest): ContentFixResult
}
