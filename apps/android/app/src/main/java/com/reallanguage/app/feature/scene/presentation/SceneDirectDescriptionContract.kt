package com.reallanguage.app.feature.scene.presentation

data class SceneDirectDescriptionUiState(
    val text: String = "",
    val selectionStart: Int = text.length,
    val selectionEnd: Int = selectionStart,
    val showDiscardConfirmation: Boolean = false,
) {
    val continueEnabled: Boolean get() = text.isNotBlank()
}

sealed interface SceneDirectDescriptionAction {
    data class TextChanged(
        val value: String,
        val selectionStart: Int = value.length,
        val selectionEnd: Int = selectionStart,
    ) : SceneDirectDescriptionAction
    data object ContinueRequested : SceneDirectDescriptionAction
    data object BackRequested : SceneDirectDescriptionAction
    data object ContinueEditingRequested : SceneDirectDescriptionAction
    data object DiscardConfirmed : SceneDirectDescriptionAction
}

sealed interface SceneDirectDescriptionEffect {
    data object OpenSceneHome : SceneDirectDescriptionEffect
    data class OpenTypeSelection(val description: String) : SceneDirectDescriptionEffect
}
