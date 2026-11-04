package com.reallanguage.app.feature.scene.presentation

import androidx.lifecycle.ViewModel
import com.reallanguage.app.feature.scene.domain.SceneStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

class SceneTypeViewModel(start: SceneStart) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SceneTypeUiState(start = start))
    val uiState: StateFlow<SceneTypeUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<SceneTypeEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    fun onAction(action: SceneTypeAction) {
        when (action) {
            is SceneTypeAction.TypeSelected -> mutableUiState.update {
                it.copy(selectedType = action.type)
            }
            SceneTypeAction.ContinueRequested -> mutableUiState.value.selectedType?.let {
                effectChannel.trySend(SceneTypeEffect.OpenQuestions(mutableUiState.value.start, it))
            }
            SceneTypeAction.BackRequested -> effectChannel.trySend(
                SceneTypeEffect.OpenSceneHome(mutableUiState.value.start),
            )
        }
    }
}
