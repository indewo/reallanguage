package com.reallanguage.app.feature.recording.presentation

import com.reallanguage.app.feature.recording.domain.LocalRecordingEntry
data class LocalRecordingsUiState(val recordings: List<LocalRecordingEntry> = emptyList(), val loading: Boolean = true, val loadFailed: Boolean = false)
sealed interface LocalRecordingsAction {
    data class RecordingRequested(val recordingId: String) : LocalRecordingsAction
    data object RetryRequested : LocalRecordingsAction
    data object ReturnAccountRequested : LocalRecordingsAction
}
sealed interface LocalRecordingsEffect {
    data class OpenRecording(val recordingId: String) : LocalRecordingsEffect
    data object ReturnAccount : LocalRecordingsEffect
}
