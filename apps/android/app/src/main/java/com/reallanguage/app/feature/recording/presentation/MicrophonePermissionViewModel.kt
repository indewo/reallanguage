package com.reallanguage.app.feature.recording.presentation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

class MicrophonePermissionViewModel(canRequestDirectly: Boolean) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        MicrophonePermissionUiState(canRequestDirectly = canRequestDirectly),
    )
    val uiState: StateFlow<MicrophonePermissionUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<MicrophonePermissionEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    fun onAction(action: MicrophonePermissionAction) {
        when (action) {
            MicrophonePermissionAction.AuthorizeRequested -> authorize()
            is MicrophonePermissionAction.PermissionResult -> {
                mutableUiState.update { it.copy(requestingPermission = false) }
                if (action.granted) {
                    effectChannel.trySend(MicrophonePermissionEffect.OpenRecording)
                }
            }
            MicrophonePermissionAction.ReturnHomeRequested ->
                effectChannel.trySend(MicrophonePermissionEffect.OpenHome)
        }
    }

    private fun authorize() {
        val state = mutableUiState.value
        if (state.requestingPermission) return
        if (state.canRequestDirectly) {
            mutableUiState.update { it.copy(requestingPermission = true) }
            effectChannel.trySend(MicrophonePermissionEffect.RequestMicrophonePermission)
        } else {
            effectChannel.trySend(MicrophonePermissionEffect.OpenAppSettings)
        }
    }
}
