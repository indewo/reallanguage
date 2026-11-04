package com.reallanguage.app.feature.recording.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.recording.domain.LocalRecordingsLoadResult
import com.reallanguage.app.feature.recording.domain.LocalRecordingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class LocalRecordingsViewModel(private val repository: LocalRecordingsRepository) : ViewModel() {
    private val mutableUiState = MutableStateFlow(LocalRecordingsUiState()); val uiState = mutableUiState.asStateFlow()
    private val effectChannel = Channel<LocalRecordingsEffect>(Channel.BUFFERED); val effects = effectChannel.receiveAsFlow()
    init { load() }
    fun onAction(action: LocalRecordingsAction) = when (action) {
        is LocalRecordingsAction.RecordingRequested -> mutableUiState.value.recordings.firstOrNull { it.recordingId == action.recordingId }?.let { effectChannel.trySend(LocalRecordingsEffect.OpenRecording(it.recordingId)) } ?: false
        LocalRecordingsAction.RetryRequested -> { load(); true }
        LocalRecordingsAction.ReturnAccountRequested -> effectChannel.trySend(LocalRecordingsEffect.ReturnAccount)
    }
    private fun load() {
        mutableUiState.value = LocalRecordingsUiState()
        viewModelScope.launch {
            val result = try { repository.load() } catch (c: CancellationException) { throw c } catch (_: Exception) { LocalRecordingsLoadResult.Failed }
            mutableUiState.value = when (result) {
                is LocalRecordingsLoadResult.Ready -> LocalRecordingsUiState(result.recordings.sortedByDescending { it.createdAtEpochMillis }, loading = false)
                LocalRecordingsLoadResult.Failed -> LocalRecordingsUiState(loading = false, loadFailed = true)
            }
        }
    }
}
