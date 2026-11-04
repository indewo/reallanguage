package com.reallanguage.app.feature.recording.domain

data class LocalRecordingSummary(
    val recordingId: String,
    val durationSeconds: Int,
    val endReason: RecordingEndReason? = RecordingEndReason.USER_STOPPED,
)

sealed interface RecordingLoadResult {
    data class Ready(val recording: LocalRecordingSummary) : RecordingLoadResult
    data object Failed : RecordingLoadResult
}

sealed interface RecordingPlaybackResult {
    data object Playing : RecordingPlaybackResult
    data object Stopped : RecordingPlaybackResult
    data object Failed : RecordingPlaybackResult
}

interface RecordingConfirmationRepository {
    val playbackState: kotlinx.coroutines.flow.StateFlow<RecordingPlaybackResult>? get() = null
    fun stopPlayback() {}
    suspend fun load(recordingId: String): RecordingLoadResult
    suspend fun togglePlayback(recordingId: String): RecordingPlaybackResult
}
