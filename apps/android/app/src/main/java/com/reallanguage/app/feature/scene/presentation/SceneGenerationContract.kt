package com.reallanguage.app.feature.scene.presentation

import com.reallanguage.app.feature.scene.domain.SceneGeneratedDraft
import com.reallanguage.app.feature.scene.domain.SceneQuestionSession

data class SceneGenerationUiState(
    val session: SceneQuestionSession,
    val generating: Boolean = false,
    val error: SceneGenerationError? = null,
)

enum class SceneGenerationError { GENERATION_FAILED }

sealed interface SceneGenerationAction {
    data object RetryRequested : SceneGenerationAction
    data object ReturnRequested : SceneGenerationAction
}

sealed interface SceneGenerationEffect {
    data class OpenChineseReview(
        val session: SceneQuestionSession,
        val draft: SceneGeneratedDraft,
    ) : SceneGenerationEffect

    data class ReturnToQuestions(val session: SceneQuestionSession) : SceneGenerationEffect
}
