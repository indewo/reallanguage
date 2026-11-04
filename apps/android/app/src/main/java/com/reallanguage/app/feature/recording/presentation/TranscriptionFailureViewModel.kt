package com.reallanguage.app.feature.recording.presentation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

class TranscriptionFailureViewModel(recordingId: String, durationSeconds: Int? = null) : ViewModel() {
    private val mutableUiState = MutableStateFlow(TranscriptionFailureUiState(recordingId, durationSeconds))
    val uiState: StateFlow<TranscriptionFailureUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<TranscriptionFailureEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    fun onAction(action: TranscriptionFailureAction) {
        val id = mutableUiState.value.recordingId
        when (action) {
            TranscriptionFailureAction.RetryRequested ->
                effectChannel.trySend(TranscriptionFailureEffect.OpenTranscription(id))
            TranscriptionFailureAction.ReturnRequested ->
                effectChannel.trySend(TranscriptionFailureEffect.OpenRecordingConfirmation(id))
        }
    }
}
