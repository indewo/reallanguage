package com.reallanguage.app.feature.recording.presentation

data class TranscriptionFailureUiState(
    val recordingId: String,
    val durationSeconds: Int? = null,
)

sealed interface TranscriptionFailureAction {
    data object RetryRequested : TranscriptionFailureAction
    data object ReturnRequested : TranscriptionFailureAction
}

sealed interface TranscriptionFailureEffect {
    data class OpenTranscription(val recordingId: String) : TranscriptionFailureEffect
    data class OpenRecordingConfirmation(val recordingId: String) : TranscriptionFailureEffect
}
