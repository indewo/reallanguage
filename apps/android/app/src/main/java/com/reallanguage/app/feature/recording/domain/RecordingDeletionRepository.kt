package com.reallanguage.app.feature.recording.domain

sealed interface RecordingDeletionResult {
    data object Deleted : RecordingDeletionResult
    data object AlreadyDeleted : RecordingDeletionResult
    data object Failed : RecordingDeletionResult
}

fun interface RecordingDeletionRepository {
    suspend fun delete(recordingId: String, commandId: String): RecordingDeletionResult
}
