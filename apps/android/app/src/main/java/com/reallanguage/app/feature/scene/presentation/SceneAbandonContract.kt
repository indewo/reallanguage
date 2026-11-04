package com.reallanguage.app.feature.scene.presentation

import com.reallanguage.app.feature.scene.domain.SceneQuestionSession

data class SceneAbandonUiState(
    val session: SceneQuestionSession,
    val deleting: Boolean = false,
    val error: SceneAbandonError? = null,
)

enum class SceneAbandonError { DELETE_FAILED }

sealed interface SceneAbandonAction {
    data object ContinueRequested : SceneAbandonAction
    data object ConfirmRequested : SceneAbandonAction
}

sealed interface SceneAbandonEffect {
    data class ReturnToQuestions(val session: SceneQuestionSession) : SceneAbandonEffect
    data object OpenHome : SceneAbandonEffect
}
