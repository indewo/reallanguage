package com.reallanguage.app.feature.recording.presentation

data class MicrophonePermissionUiState(
    val canRequestDirectly: Boolean,
    val requestingPermission: Boolean = false,
)

sealed interface MicrophonePermissionAction {
    data object AuthorizeRequested : MicrophonePermissionAction
    data class PermissionResult(val granted: Boolean) : MicrophonePermissionAction
    data object ReturnHomeRequested : MicrophonePermissionAction
}

sealed interface MicrophonePermissionEffect {
    data object RequestMicrophonePermission : MicrophonePermissionEffect
    data object OpenAppSettings : MicrophonePermissionEffect
    data object OpenRecording : MicrophonePermissionEffect
    data object OpenHome : MicrophonePermissionEffect
}
