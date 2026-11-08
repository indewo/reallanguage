package com.reallanguage.app.feature.recording.presentation

data class ActiveRecordingUiState(
    val recordingId: String? = null,
    val elapsedSeconds: Int = 0,
    val recording: Boolean = false,
    val stopping: Boolean = false,
    val error: ActiveRecordingError? = null,
    val paused: Boolean = false,
    val levels: List<Float> = emptyList(),
)

enum class ActiveRecordingError { RECORDING_FAILED }

sealed interface ActiveRecordingAction {
    data object TogglePauseRequested : ActiveRecordingAction
    data object StopRequested : ActiveRecordingAction
    data object RetryRequested : ActiveRecordingAction
}

sealed interface ActiveRecordingEffect {
    data class OpenCompletion(val recordingId: String) : ActiveRecordingEffect
}
