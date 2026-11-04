package com.reallanguage.app.feature.formation.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.LearningTargetExtractionRepository
import com.reallanguage.app.feature.formation.domain.LearningTargetExtractionResult
import com.reallanguage.app.feature.formation.domain.QueryableExpression
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LearningTargetLoadingUiState(val processing: Boolean = false, val failed: Boolean = false)
sealed interface LearningTargetLoadingAction { data object RetryRequested : LearningTargetLoadingAction; data object BackRequested : LearningTargetLoadingAction }
sealed interface LearningTargetLoadingEffect {
    data class OpenSelection(val targets: List<LearningTarget>, val queryableExpressions: List<QueryableExpression>) : LearningTargetLoadingEffect
    data object ReturnToEnglishConfirmation : LearningTargetLoadingEffect
}
class LearningTargetLoadingViewModel(private val repository: LearningTargetExtractionRepository) : ViewModel() {
    private val mutableUiState = MutableStateFlow(LearningTargetLoadingUiState())
    val uiState = mutableUiState.asStateFlow()
    private val effectChannel = Channel<LearningTargetLoadingEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var loadJob: Job? = null
    private var departed = false
    private var version = 0L
    init { load() }

    fun onAction(action: LearningTargetLoadingAction) {
        if (departed) return
        when (action) {
            LearningTargetLoadingAction.RetryRequested -> load()
            LearningTargetLoadingAction.BackRequested -> leave(LearningTargetLoadingEffect.ReturnToEnglishConfirmation)
        }
    }
    private fun leave(effect: LearningTargetLoadingEffect) {
        if (departed) return
        departed = true
        version++
        loadJob?.cancel()
        mutableUiState.update { it.copy(processing = false) }
        effectChannel.trySend(effect)
    }
    override fun onCleared() {
        departed = true
        version++
        loadJob?.cancel()
        super.onCleared()
    }
    private fun load() {
        if (departed || mutableUiState.value.processing) return
        val currentVersion = ++version
        mutableUiState.update { LearningTargetLoadingUiState(processing = true) }
        loadJob = viewModelScope.launch {
            try {
                val result = try { repository.extract() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LearningTargetExtractionResult.Failed }
                currentCoroutineContext().ensureActive()
                if (departed || version != currentVersion) return@launch
                when (result) {
                    is LearningTargetExtractionResult.Extracted -> leave(LearningTargetLoadingEffect.OpenSelection(result.targets, result.queryableExpressions))
                    LearningTargetExtractionResult.Failed -> mutableUiState.update { LearningTargetLoadingUiState(failed = true) }
                }
            } finally {
                if (!departed && version == currentVersion) mutableUiState.update { it.copy(processing = false) }
            }
        }
    }
}
