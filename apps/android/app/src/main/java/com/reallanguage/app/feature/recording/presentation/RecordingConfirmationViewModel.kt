package com.reallanguage.app.feature.recording.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.recording.domain.RecordingConfirmationRepository
import com.reallanguage.app.feature.recording.domain.RecordingLoadResult
import com.reallanguage.app.feature.recording.domain.RecordingPlaybackResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RecordingConfirmationViewModel(
    private val recordingId: String,
    private val repository: RecordingConfirmationRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(RecordingConfirmationUiState())
    val uiState: StateFlow<RecordingConfirmationUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<RecordingConfirmationEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var playbackJob: Job? = null

    fun stopPlayback() {
        playbackJob?.cancel()
        repository.stopPlayback()
        mutableUiState.update { it.copy(playing = false, playbackBusy = false) }
    }

    override fun onCleared() { stopPlayback(); super.onCleared() }

    init {
        repository.playbackState?.let { events -> viewModelScope.launch { events.collect { playbackChanged(it) } } }
        load()
    }

    fun onAction(action: RecordingConfirmationAction) {
        when (action) {
            RecordingConfirmationAction.PreviewRequested -> preview()
            RecordingConfirmationAction.DeleteRequested -> withLoadedRecording {
                stopPlayback()
                effectChannel.trySend(RecordingConfirmationEffect.OpenDeletionConfirmation(it))
            }
            RecordingConfirmationAction.TranscribeRequested -> withLoadedRecording {
                stopPlayback()
                if (mutableUiState.value.recording?.endReason != null) effectChannel.trySend(RecordingConfirmationEffect.OpenTranscription(it))
            }
            RecordingConfirmationAction.BackRequested -> {
                stopPlayback()
                effectChannel.trySend(RecordingConfirmationEffect.ReturnToPrevious)
            }
            RecordingConfirmationAction.ReturnHomeRequested -> {
                stopPlayback()
                effectChannel.trySend(RecordingConfirmationEffect.OpenHome)
            }
            RecordingConfirmationAction.RetryRequested -> load()
        }
    }

    private fun withLoadedRecording(block: (String) -> Unit) {
        mutableUiState.value.recording?.recordingId?.let(block)
    }

    private fun load() {
        mutableUiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = try { repository.load(recordingId) }
            catch (cancellation: CancellationException) { throw cancellation }
            catch (_: Exception) { RecordingLoadResult.Failed }
            mutableUiState.value = when (result) {
                is RecordingLoadResult.Ready -> RecordingConfirmationUiState(
                    recording = result.recording, loading = false,
                )
                RecordingLoadResult.Failed -> RecordingConfirmationUiState(
                    loading = false, error = RecordingConfirmationError.LOAD_FAILED,
                )
            }
        }
    }

    private fun preview() {
        val id = mutableUiState.value.recording?.recordingId ?: return
        if (mutableUiState.value.playbackBusy) return
        mutableUiState.update { it.copy(playbackBusy = true, error = null) }
        playbackJob = viewModelScope.launch {
            val result = try { repository.togglePlayback(id) }
            catch (cancellation: CancellationException) { throw cancellation }
            catch (_: Exception) { RecordingPlaybackResult.Failed }
            playbackChanged(
                if (result == RecordingPlaybackResult.Failed) result else repository.playbackState?.value ?: result,
                requestFinished = true,
            )
        }
    }

    private fun playbackChanged(result: RecordingPlaybackResult, requestFinished: Boolean = false) {
            mutableUiState.update {
                val busy = it.playbackBusy && !requestFinished
                when (result) {
                    RecordingPlaybackResult.Playing -> it.copy(playing = true, playbackBusy = busy)
                    RecordingPlaybackResult.Stopped -> it.copy(playing = false, playbackBusy = busy)
                    RecordingPlaybackResult.Failed -> it.copy(
                        playing = false,
                        playbackBusy = busy,
                        error = RecordingConfirmationError.PLAYBACK_FAILED,
                    )
                }
            }
    }
}
