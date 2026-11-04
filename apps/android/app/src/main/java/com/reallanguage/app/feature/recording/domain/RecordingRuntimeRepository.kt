package com.reallanguage.app.feature.recording.domain

import kotlinx.coroutines.flow.StateFlow

data class ActiveRecording(val recordingId: String, val elapsedSeconds: Int)

sealed interface RecordingRuntimeState {
    data object Starting : RecordingRuntimeState
    data class Recording(val active: ActiveRecording) : RecordingRuntimeState
    data class Completed(val recordingId: String, val durationSeconds: Int) : RecordingRuntimeState
    data object Failed : RecordingRuntimeState
}

interface RecordingRuntimeRepository {
    val state: StateFlow<RecordingRuntimeState>
    suspend fun start()
    suspend fun stop(recordingId: String)
}
