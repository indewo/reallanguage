package com.reallanguage.app.feature.recording.domain

enum class RecordingEndReason { USER_STOPPED, MAX_DURATION }

data class TranscriptionRequest(
    val recordingId: String,
    val durationSeconds: Int,
    val audioFormat: String,
    val languageHint: String,
    val endReason: RecordingEndReason,
)

sealed interface TranscriptionResult {
    data class Completed(val transcriptId: String) : TranscriptionResult
    data class Failed(val operationId: String?) : TranscriptionResult
}

fun interface RecordingTranscriptionRepository {
    suspend fun transcribe(request: TranscriptionRequest, commandId: String): TranscriptionResult
}
