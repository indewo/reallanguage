package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.learning.domain.ExplanationPreparationRepository
import com.reallanguage.app.feature.learning.domain.ExplanationPreparationResult
import com.reallanguage.app.feature.learning.domain.SelectableExpression
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExplanationPreparationUiState(
    val loading: Boolean = false,
    val failed: Boolean = false,
    val loadingMessage: String = "正在准备本句讲解…",
    val failureMessage: String = "讲解准备失败",
)
sealed interface ExplanationPreparationAction { data object RetryRequested : ExplanationPreparationAction; data object BackRequested : ExplanationPreparationAction }
sealed interface ExplanationPreparationEffect { data class OpenExplanation(val baseExplanationZh: String, val expressions: List<SelectableExpression>) : ExplanationPreparationEffect; data object ReturnToChapter : ExplanationPreparationEffect }
class ExplanationPreparationViewModel(private val repository: ExplanationPreparationRepository) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ExplanationPreparationUiState())
    val uiState = mutableUiState.asStateFlow()
    private val channel = Channel<ExplanationPreparationEffect>(Channel.BUFFERED)
    val effects = channel.receiveAsFlow()
    init { load() }
    fun onAction(action: ExplanationPreparationAction) = when (action) {
        ExplanationPreparationAction.RetryRequested -> load()
        ExplanationPreparationAction.BackRequested -> viewModelScope.launch { channel.send(ExplanationPreparationEffect.ReturnToChapter) }
    }
    private fun load() { if (mutableUiState.value.loading) return; mutableUiState.update { ExplanationPreparationUiState(loading = true) }; viewModelScope.launch { when (val result = repository.prepare()) {
        is ExplanationPreparationResult.Ready -> channel.send(ExplanationPreparationEffect.OpenExplanation(result.baseExplanationZh, result.expressions))
        ExplanationPreparationResult.Failed -> mutableUiState.update { ExplanationPreparationUiState(failed = true) }
    } } }
}
