package com.reallanguage.app.feature.recording.presentation

import com.reallanguage.app.feature.recording.domain.TranscriptionRequest

data class RecordingTranscriptionUiState(
    val request: TranscriptionRequest,
    val processing: Boolean = true,
)

sealed interface RecordingTranscriptionEffect {
    data class OpenResult(val recordingId: String, val transcriptId: String) : RecordingTranscriptionEffect
    data class OpenFailure(val recordingId: String, val operationId: String?) : RecordingTranscriptionEffect
    data object OpenHome : RecordingTranscriptionEffect
}

sealed interface RecordingTranscriptionAction {
    data object ReturnHomeRequested : RecordingTranscriptionAction
}
