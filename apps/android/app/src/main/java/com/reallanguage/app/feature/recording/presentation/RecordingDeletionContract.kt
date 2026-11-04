package com.reallanguage.app.feature.recording.presentation

data class RecordingDeletionUiState(
    val recordingId: String,
    val deleting: Boolean = false,
    val error: RecordingDeletionError? = null,
)

enum class RecordingDeletionError { DELETE_FAILED }

sealed interface RecordingDeletionAction {
    data object CancelRequested : RecordingDeletionAction
    data object ConfirmRequested : RecordingDeletionAction
}

sealed interface RecordingDeletionEffect {
    data class ReturnToConfirmation(val recordingId: String) : RecordingDeletionEffect
    data object OpenHome : RecordingDeletionEffect
}
