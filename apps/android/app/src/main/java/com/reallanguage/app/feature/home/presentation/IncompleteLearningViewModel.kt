package com.reallanguage.app.feature.home.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.home.domain.IncompleteLearningLoadResult
import com.reallanguage.app.feature.home.domain.IncompleteLearningRepository
import com.reallanguage.app.feature.home.domain.ResumeLearningKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class IncompleteLearningViewModel(
    private val repository: IncompleteLearningRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(IncompleteLearningUiState())
    val uiState: StateFlow<IncompleteLearningUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<IncompleteLearningEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var loadJob: Job? = null
    private var departed = false

    init { load() }

    fun onAction(action: IncompleteLearningAction) {
        if (departed) return
        when (action) {
            is IncompleteLearningAction.ResumeRequested -> mutableUiState.value.entries.takeUnless { mutableUiState.value.loading }
                .orEmpty()
                .firstOrNull { it.learningId == action.learningId }
                ?.let { leave(IncompleteLearningEffect.Resume(it.learningId, it.savedStepId, it.checkpointStepId)) }
            IncompleteLearningAction.RetryRequested -> load()
            IncompleteLearningAction.ReturnHomeRequested -> leave(IncompleteLearningEffect.OpenHome)
        }
    }

    private fun leave(effect: IncompleteLearningEffect) {
        if (departed) return
        departed = true
        loadJob?.cancel()
        mutableUiState.value = IncompleteLearningUiState(loading = false)
        effectChannel.trySend(effect)
    }

    private fun load() {
        if (departed || loadJob?.isActive == true) return
        mutableUiState.update { it.copy(loading = true, loadFailed = false) }
        loadJob = viewModelScope.launch {
            val result = try {
                repository.loadOrdinaryLearning()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                IncompleteLearningLoadResult.Failed
            }
            if (departed) return@launch
            mutableUiState.value = when (result) {
                is IncompleteLearningLoadResult.Ready -> IncompleteLearningUiState(
                    entries = result.entries.filter { it.kind == ResumeLearningKind.ORDINARY || it.kind == ResumeLearningKind.FORMATION },
                    loading = false,
                )
                IncompleteLearningLoadResult.Failed -> IncompleteLearningUiState(
                    loading = false,
                    loadFailed = true,
                )
            }
        }
    }
}
