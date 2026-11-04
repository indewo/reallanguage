package com.reallanguage.app.feature.scene.presentation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

class SceneDirectDescriptionViewModel(initialText: String = "") : ViewModel() {
    private val mutableUiState = MutableStateFlow(SceneDirectDescriptionUiState(initialText))
    val uiState: StateFlow<SceneDirectDescriptionUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<SceneDirectDescriptionEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    fun onAction(action: SceneDirectDescriptionAction) {
        when (action) {
            is SceneDirectDescriptionAction.TextChanged -> mutableUiState.update {
                it.copy(
                    text = action.value,
                    selectionStart = action.selectionStart.coerceIn(0, action.value.length),
                    selectionEnd = action.selectionEnd.coerceIn(0, action.value.length),
                    showDiscardConfirmation = false,
                )
            }
            SceneDirectDescriptionAction.ContinueRequested -> {
                val description = mutableUiState.value.text.trim()
                if (description.isNotEmpty()) effectChannel.trySend(
                    SceneDirectDescriptionEffect.OpenTypeSelection(description),
                )
            }
            SceneDirectDescriptionAction.BackRequested -> {
                if (mutableUiState.value.text.isBlank()) {
                    effectChannel.trySend(SceneDirectDescriptionEffect.OpenSceneHome)
                } else mutableUiState.update { it.copy(showDiscardConfirmation = true) }
            }
            SceneDirectDescriptionAction.ContinueEditingRequested -> mutableUiState.update {
                it.copy(showDiscardConfirmation = false)
            }
            SceneDirectDescriptionAction.DiscardConfirmed -> {
                mutableUiState.value = SceneDirectDescriptionUiState()
                effectChannel.trySend(SceneDirectDescriptionEffect.OpenSceneHome)
            }
        }
    }
}
