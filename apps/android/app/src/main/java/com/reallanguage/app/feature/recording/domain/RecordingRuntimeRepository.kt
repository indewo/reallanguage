package com.reallanguage.app.feature.recording.domain

import kotlinx.coroutines.flow.StateFlow

const val RECORDING_LIMIT_SECONDS = 60 * 60

data class ActiveRecording(
    val recordingId: String,
    val elapsedSeconds: Int,
    val paused: Boolean = false,
    val levels: List<Float> = emptyList(),
)

sealed interface RecordingRuntimeState {
    data object Starting : RecordingRuntimeState
    data class Recording(val active: ActiveRecording) : RecordingRuntimeState
    data class Completed(val recordingId: String, val durationSeconds: Int) : RecordingRuntimeState
    data object Failed : RecordingRuntimeState
}

interface RecordingRuntimeRepository {
    val state: StateFlow<RecordingRuntimeState>
    suspend fun start()
    suspend fun setPaused(recordingId: String, paused: Boolean)
    suspend fun stop(recordingId: String)
}
