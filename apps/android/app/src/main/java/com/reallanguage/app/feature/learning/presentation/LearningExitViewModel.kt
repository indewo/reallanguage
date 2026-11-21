package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.learning.domain.CheckpointSaveResult
import com.reallanguage.app.feature.learning.domain.LearningCheckpointRepository
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class LearningExitViewModel(
    checkpoint: OrdinaryLearningCheckpoint,
    private val repository: LearningCheckpointRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(LearningExitUiState(checkpoint))
    val uiState: StateFlow<LearningExitUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<LearningExitEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    fun onAction(action: LearningExitAction) {
        if (mutableUiState.value.saving) return
        when (action) {
            LearningExitAction.ContinueLearningRequested -> send(
                LearningExitEffect.CloseDialog,
            )
            LearningExitAction.PauseLearningRequested -> saveAndExit()
            LearningExitAction.AbandonChapterRequested -> send(
                LearningExitEffect.OpenAbandonConfirmation,
            )
        }
    }

    private fun saveAndExit() {
        if (mutableUiState.value.saving) return
        mutableUiState.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            val result = try { repository.save(mutableUiState.value.checkpoint) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { CheckpointSaveResult.Failed }
            when (result) {
                CheckpointSaveResult.Saved -> {
                    mutableUiState.update { it.copy(saving = false) }
                    send(LearningExitEffect.ReturnHome)
                }
                CheckpointSaveResult.Failed -> mutableUiState.update {
                    it.copy(saving = false, saveFailed = true)
                }
            }
        }
    }

    private fun send(effect: LearningExitEffect) {
        viewModelScope.launch { effectChannel.send(effect) }
    }
}
