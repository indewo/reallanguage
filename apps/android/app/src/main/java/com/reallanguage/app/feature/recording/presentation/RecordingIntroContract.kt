package com.reallanguage.app.feature.recording.presentation

data class RecordingIntroUiState(val requestingPermission: Boolean = false)

sealed interface RecordingIntroAction {
    data object AgreeRequested : RecordingIntroAction
    data class PermissionResult(val granted: Boolean) : RecordingIntroAction
    data object ReturnHomeRequested : RecordingIntroAction
}

sealed interface RecordingIntroEffect {
    data object RequestMicrophonePermission : RecordingIntroEffect
    data object OpenRecording : RecordingIntroEffect
    data object OpenPermissionExplanation : RecordingIntroEffect
    data object OpenHome : RecordingIntroEffect
}
