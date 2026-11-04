package com.reallanguage.app.feature.scene.domain

enum class SceneGenerationTask(val taskId: String) {
    DIALOGUE_TURN("SCENE_DIALOGUE_TURN_V1"),
    ARTICLE_DRAFT("SCENE_ARTICLE_DRAFT_V1"),
}

enum class SceneSpeaker { USER, OTHER }

data class SceneDraftUnit(
    val unitId: String,
    val speaker: SceneSpeaker?,
    val textZh: String,
    val factReferences: List<String>,
)

data class SceneGeneratedDraft(
    val draftId: String,
    val contentType: SceneContentType,
    val units: List<SceneDraftUnit>,
)

data class SceneGenerationRequest(
    val task: SceneGenerationTask,
    val session: SceneQuestionSession,
    val remainingContentBudget: Int,
    val previousUnits: List<SceneDraftUnit> = emptyList(),
)

sealed interface SceneGenerationResult {
    data class Generated(val draft: SceneGeneratedDraft) : SceneGenerationResult
    data object Failed : SceneGenerationResult
}

interface SceneGenerationRepository {
    suspend fun generate(request: SceneGenerationRequest, commandId: String): SceneGenerationResult
}
