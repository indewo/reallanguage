package com.reallanguage.app.feature.recording.presentation

import com.reallanguage.app.feature.recording.domain.LocalRecordingSummary

data class RecordingConfirmationUiState(
    val recording: LocalRecordingSummary? = null,
    val loading: Boolean = true,
    val playing: Boolean = false,
    val playbackBusy: Boolean = false,
    val error: RecordingConfirmationError? = null,
)

enum class RecordingConfirmationError { LOAD_FAILED, PLAYBACK_FAILED }

sealed interface RecordingConfirmationAction {
    data object PreviewRequested : RecordingConfirmationAction
    data object DeleteRequested : RecordingConfirmationAction
    data object TranscribeRequested : RecordingConfirmationAction
    data object BackRequested : RecordingConfirmationAction
    data object ReturnHomeRequested : RecordingConfirmationAction
    data object RetryRequested : RecordingConfirmationAction
}

sealed interface RecordingConfirmationEffect {
    data class OpenDeletionConfirmation(val recordingId: String) : RecordingConfirmationEffect
    data class OpenTranscription(val recordingId: String) : RecordingConfirmationEffect
    data object ReturnToPrevious : RecordingConfirmationEffect
    data object OpenHome : RecordingConfirmationEffect
}
