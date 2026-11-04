package com.reallanguage.app.feature.scene.presentation

import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneStart

data class SceneTypeUiState(
    val start: SceneStart,
    val selectedType: SceneContentType? = null,
)

sealed interface SceneTypeAction {
    data class TypeSelected(val type: SceneContentType) : SceneTypeAction
    data object ContinueRequested : SceneTypeAction
    data object BackRequested : SceneTypeAction
}

sealed interface SceneTypeEffect {
    data class OpenQuestions(val start: SceneStart, val type: SceneContentType) : SceneTypeEffect
    data class OpenSceneHome(val preservedStart: SceneStart) : SceneTypeEffect
}
