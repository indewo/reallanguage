package com.reallanguage.app.feature.scene.domain

enum class SceneEditIssueReason {
    REQUIRED_INFORMATION_MISSING,
    CONTRADICTION,
    UNINTELLIGIBLE,
    SEVERELY_UNNATURAL,
}

data class SceneEditIssue(
    val unitId: String,
    val startOffset: Int,
    val endOffset: Int,
    val reason: SceneEditIssueReason,
    val explanationZh: String,
)

data class SceneEditReviewRequest(
    val originalDraft: SceneGeneratedDraft,
    val editedUnits: List<SceneDraftUnit>,
    val changedUnitIds: Set<String>,
    val contentType: SceneContentType,
)

sealed interface SceneEditReviewResult {
    data object Pass : SceneEditReviewResult
    data class NeedsUserEdit(val issues: List<SceneEditIssue>) : SceneEditReviewResult
    data object Failed : SceneEditReviewResult
}

interface SceneEditReviewRepository {
    suspend fun review(request: SceneEditReviewRequest, commandId: String): SceneEditReviewResult
}
