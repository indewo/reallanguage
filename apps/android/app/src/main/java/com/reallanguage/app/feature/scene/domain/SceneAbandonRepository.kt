package com.reallanguage.app.feature.scene.domain

interface SceneAbandonRepository {
    suspend fun abandon(session: SceneQuestionSession, commandId: String): SceneAbandonResult
}

sealed interface SceneAbandonResult {
    data object Abandoned : SceneAbandonResult
    data object Failed : SceneAbandonResult
}
