package com.reallanguage.app.feature.recording.presentation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

class RecordingIntroViewModel : ViewModel() {
    private val mutableUiState = MutableStateFlow(RecordingIntroUiState())
    val uiState: StateFlow<RecordingIntroUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<RecordingIntroEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    fun onAction(action: RecordingIntroAction) {
        when (action) {
            RecordingIntroAction.AgreeRequested -> {
                if (!mutableUiState.value.requestingPermission) {
                    mutableUiState.value = RecordingIntroUiState(requestingPermission = true)
                    effectChannel.trySend(RecordingIntroEffect.RequestMicrophonePermission)
                }
            }
            is RecordingIntroAction.PermissionResult -> {
                mutableUiState.value = RecordingIntroUiState()
                effectChannel.trySend(
                    if (action.granted) RecordingIntroEffect.OpenRecording
                    else RecordingIntroEffect.OpenPermissionExplanation,
                )
            }
            RecordingIntroAction.ReturnHomeRequested -> effectChannel.trySend(RecordingIntroEffect.OpenHome)
        }
    }
}
