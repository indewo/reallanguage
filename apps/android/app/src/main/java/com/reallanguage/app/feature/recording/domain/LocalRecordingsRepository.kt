package com.reallanguage.app.feature.recording.domain

data class LocalRecordingEntry(val recordingId: String, val createdAtEpochMillis: Long)
sealed interface LocalRecordingsLoadResult {
    data class Ready(val recordings: List<LocalRecordingEntry>) : LocalRecordingsLoadResult
    data object Failed : LocalRecordingsLoadResult
}
fun interface LocalRecordingsRepository { suspend fun load(): LocalRecordingsLoadResult }
