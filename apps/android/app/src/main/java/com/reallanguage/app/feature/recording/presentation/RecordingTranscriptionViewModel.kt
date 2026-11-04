package com.reallanguage.app.feature.recording.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.recording.domain.RecordingTranscriptionRepository
import com.reallanguage.app.feature.recording.domain.TranscriptionRequest
import com.reallanguage.app.feature.recording.domain.TranscriptionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class RecordingTranscriptionViewModel(
    request: TranscriptionRequest,
    private val commandId: String,
    private val repository: RecordingTranscriptionRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(RecordingTranscriptionUiState(request))
    val uiState = mutableUiState.asStateFlow()
    private val effectChannel = Channel<RecordingTranscriptionEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var leftPage = false

    init { transcribe() }

    fun onAction(action: RecordingTranscriptionAction) {
        when (action) {
            RecordingTranscriptionAction.ReturnHomeRequested -> if (!leftPage) {
                leftPage = true
                effectChannel.trySend(RecordingTranscriptionEffect.OpenHome)
            }
        }
    }

    private fun transcribe() {
        viewModelScope.launch {
            val request = mutableUiState.value.request
            val result = try { repository.transcribe(request, commandId) }
            catch (cancellation: CancellationException) { throw cancellation }
            catch (_: Exception) { TranscriptionResult.Failed(null) }
            mutableUiState.value = mutableUiState.value.copy(processing = false)
            // Keep an already submitted result in the repository, but never navigate over the user's Home choice.
            if (leftPage) return@launch
            effectChannel.send(
                when (result) {
                    is TranscriptionResult.Completed -> RecordingTranscriptionEffect.OpenResult(
                        request.recordingId, result.transcriptId,
                    )
                    is TranscriptionResult.Failed -> RecordingTranscriptionEffect.OpenFailure(
                        request.recordingId, result.operationId,
                    )
                },
            )
        }
    }
}
