package com.reallanguage.app.feature.recording.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.recording.domain.RecordingDeletionRepository
import com.reallanguage.app.feature.recording.domain.RecordingDeletionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RecordingDeletionViewModel(
    recordingId: String,
    private val commandId: String,
    private val repository: RecordingDeletionRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(RecordingDeletionUiState(recordingId))
    val uiState = mutableUiState.asStateFlow()
    private val effectChannel = Channel<RecordingDeletionEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    fun onAction(action: RecordingDeletionAction) {
        when (action) {
            RecordingDeletionAction.CancelRequested -> effectChannel.trySend(
                RecordingDeletionEffect.ReturnToConfirmation(mutableUiState.value.recordingId),
            )
            RecordingDeletionAction.ConfirmRequested -> delete()
        }
    }

    private fun delete() {
        if (mutableUiState.value.deleting) return
        mutableUiState.update { it.copy(deleting = true, error = null) }
        viewModelScope.launch {
            val result = try { repository.delete(mutableUiState.value.recordingId, commandId) }
            catch (cancellation: CancellationException) { throw cancellation }
            catch (_: Exception) { RecordingDeletionResult.Failed }
            when (result) {
                RecordingDeletionResult.Deleted, RecordingDeletionResult.AlreadyDeleted -> {
                    mutableUiState.update { it.copy(deleting = false) }
                    effectChannel.send(RecordingDeletionEffect.OpenHome)
                }
                RecordingDeletionResult.Failed -> mutableUiState.update {
                    it.copy(deleting = false, error = RecordingDeletionError.DELETE_FAILED)
                }
            }
        }
    }
}
