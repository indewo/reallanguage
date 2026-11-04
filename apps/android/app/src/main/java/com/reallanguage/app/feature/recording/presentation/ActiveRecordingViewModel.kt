package com.reallanguage.app.feature.recording.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.recording.domain.RecordingRuntimeRepository
import com.reallanguage.app.feature.recording.domain.RecordingRuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class ActiveRecordingViewModel(private val repository: RecordingRuntimeRepository) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ActiveRecordingUiState())
    val uiState: StateFlow<ActiveRecordingUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<ActiveRecordingEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var stopRequestedFor: String? = null
    private val previousCompletedId = (repository.state.value as? RecordingRuntimeState.Completed)?.recordingId
    private var deliveredCompletionId: String? = null

    init {
        observeRuntime()
        start()
    }

    fun onAction(action: ActiveRecordingAction) {
        when (action) {
            ActiveRecordingAction.StopRequested -> mutableUiState.value.recordingId?.let(::stop)
            ActiveRecordingAction.RetryRequested -> start()
        }
    }

    private fun observeRuntime() {
        viewModelScope.launch {
            repository.state.collectLatest { runtime ->
                when (runtime) {
                    RecordingRuntimeState.Starting -> mutableUiState.value = ActiveRecordingUiState()
                    is RecordingRuntimeState.Recording -> {
                        mutableUiState.value = ActiveRecordingUiState(
                            recordingId = runtime.active.recordingId,
                            elapsedSeconds = runtime.active.elapsedSeconds,
                            recording = true,
                            stopping = stopRequestedFor == runtime.active.recordingId,
                        )
                        if (runtime.active.elapsedSeconds >= MAX_DURATION_SECONDS) stop(runtime.active.recordingId)
                    }
                    is RecordingRuntimeState.Completed -> {
                        if (runtime.recordingId == previousCompletedId || runtime.recordingId == deliveredCompletionId) return@collectLatest
                        deliveredCompletionId = runtime.recordingId
                        mutableUiState.value = ActiveRecordingUiState(elapsedSeconds = runtime.durationSeconds)
                        effectChannel.send(ActiveRecordingEffect.OpenCompletion(runtime.recordingId))
                    }
                    RecordingRuntimeState.Failed -> mutableUiState.value = ActiveRecordingUiState(
                        error = ActiveRecordingError.RECORDING_FAILED,
                    )
                }
            }
        }
    }

    private fun start() {
        stopRequestedFor = null
        mutableUiState.value = ActiveRecordingUiState()
        viewModelScope.launch {
            try { repository.start() }
            catch (cancellation: CancellationException) { throw cancellation }
            catch (_: Exception) {
                mutableUiState.value = ActiveRecordingUiState(error = ActiveRecordingError.RECORDING_FAILED)
            }
        }
    }

    private fun stop(recordingId: String) {
        if (stopRequestedFor == recordingId) return
        stopRequestedFor = recordingId
        mutableUiState.value = mutableUiState.value.copy(stopping = true)
        viewModelScope.launch {
            try { repository.stop(recordingId) }
            catch (cancellation: CancellationException) { throw cancellation }
            catch (_: Exception) {
                stopRequestedFor = null
                mutableUiState.value = mutableUiState.value.copy(
                    stopping = false,
                    error = ActiveRecordingError.RECORDING_FAILED,
                )
            }
        }
    }

    companion object { const val MAX_DURATION_SECONDS = 120 }
}
