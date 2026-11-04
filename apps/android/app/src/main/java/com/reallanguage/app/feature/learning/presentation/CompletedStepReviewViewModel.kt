package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.learning.domain.CompletedStepSnapshot
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

class CompletedStepReviewViewModel(
    snapshot: CompletedStepSnapshot,
    private val loadSnapshot: (suspend () -> CompletedStepSnapshot)? = null,
    private val playAudio: suspend (String) -> Boolean,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(CompletedStepReviewUiState(snapshot))
    val uiState: StateFlow<CompletedStepReviewUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<CompletedStepReviewEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var audioJob: Job? = null
    private var loadJob: Job? = null

    init { loadSavedSnapshot() }

    fun onAction(action: CompletedStepReviewAction) {
        if (mutableUiState.value.returning) return
        when (action) {
            CompletedStepReviewAction.RetryLoadRequested -> if (mutableUiState.value.loadingFailed) loadSavedSnapshot()
            CompletedStepReviewAction.AudioToggled -> toggleAudio()
            CompletedStepReviewAction.ExpressionAudioToggled -> toggleAudio(expression = true)
            CompletedStepReviewAction.ExplanationToggled -> mutableUiState.update {
                it.copy(explanationVisible = !it.explanationVisible)
            }
            CompletedStepReviewAction.ReturnToCurrentTaskRequested -> {
                audioJob?.cancel()
                loadJob?.cancel()
                mutableUiState.update { it.copy(audioPlaying = false, loading = false, returning = true) }
                viewModelScope.launch {
                    effectChannel.send(CompletedStepReviewEffect.ReturnToCurrentTask)
                }
            }
        }
    }

    private fun loadSavedSnapshot() {
        val load = loadSnapshot ?: run {
            if (mutableUiState.value.snapshot.let { it.stepId == "LR-09" && it.completedSceneTasks.isEmpty() }) {
                mutableUiState.update { it.copy(loadingFailed = true) }
            }
            return
        }
        if (loadJob?.isActive == true) return
        mutableUiState.update { it.copy(loading = true, loadingFailed = false) }
        loadJob = viewModelScope.launch {
            try {
                val snapshot = load()
                coroutineContext.ensureActive()
                mutableUiState.update { if (it.returning) it else it.copy(snapshot = snapshot, loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableUiState.update { if (it.returning) it else it.copy(loading = false, loadingFailed = true) }
            }
        }
    }

    private fun toggleAudio(expression: Boolean = false) {
        if (mutableUiState.value.loading || mutableUiState.value.loadingFailed) return
        if (mutableUiState.value.snapshot.let { it.stepId == "LR-09" && it.completedSceneTasks.isEmpty() }) return
        audioJob?.cancel()
        if (mutableUiState.value.audioPlaying) {
            mutableUiState.update { it.copy(audioPlaying = false) }
            return
        }
        mutableUiState.update { it.copy(audioPlaying = true, audioFailed = false) }
        audioJob = viewModelScope.launch {
            val snapshot = mutableUiState.value.snapshot
            val text = if (expression) snapshot.reviewedExpression?.expression?.display ?: snapshot.contentEn else snapshot.contentEn
            val success = try { playAudio(text) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            coroutineContext.ensureActive()
            mutableUiState.update { it.copy(audioPlaying = false, audioFailed = !success) }
        }
    }
}
