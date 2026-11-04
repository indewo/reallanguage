package com.reallanguage.app.feature.formation.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.formation.domain.SemanticSplitRepository
import com.reallanguage.app.feature.formation.domain.SemanticSplitResult
import com.reallanguage.app.feature.formation.domain.SemanticUnit
import com.reallanguage.app.feature.formation.domain.toDeterministicSemanticUnits
import com.reallanguage.app.feature.formation.domain.isExactMinimumSplitOf
import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SemanticSplitUiState(val processing: Boolean = false, val failed: Boolean = false)
sealed interface SemanticSplitAction { data object RetryRequested : SemanticSplitAction; data object ReturnUpstreamRequested : SemanticSplitAction }
sealed interface SemanticSplitEffect { data class OpenFocusSelection(val units: List<SemanticUnit>) : SemanticSplitEffect; data object ReturnUpstream : SemanticSplitEffect }

class SemanticSplitViewModel(
    private val source: ConfirmedSource,
    private val repository: SemanticSplitRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SemanticSplitUiState())
    val uiState = mutableUiState.asStateFlow()
    private val effectChannel = Channel<SemanticSplitEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    init { split() }
    fun onAction(action: SemanticSplitAction) = when (action) {
        SemanticSplitAction.RetryRequested -> split()
        SemanticSplitAction.ReturnUpstreamRequested -> viewModelScope.launch { effectChannel.send(SemanticSplitEffect.ReturnUpstream) }
    }
    private fun split() {
        if (mutableUiState.value.processing) return
        mutableUiState.update { it.copy(processing = true, failed = false) }
        viewModelScope.launch {
            when (val result = repository.split(source)) {
                is SemanticSplitResult.Ready -> {
                    val dialogue = source.contentShape == SourceContentShape.DIALOGUE
                    val verified = result.units.takeIf { it.isExactMinimumSplitOf(source.text, dialogue) }
                        ?: source.text.toDeterministicSemanticUnits(dialogue)
                    if (verified.isNotEmpty()) effectChannel.send(SemanticSplitEffect.OpenFocusSelection(verified))
                    else effectChannel.send(SemanticSplitEffect.ReturnUpstream)
                    mutableUiState.update { it.copy(processing = false, failed = false) }
                }
                SemanticSplitResult.Failed -> {
                    val localUnits = source.text.toDeterministicSemanticUnits(source.contentShape == SourceContentShape.DIALOGUE)
                    if (localUnits.isNotEmpty()) {
                        effectChannel.send(SemanticSplitEffect.OpenFocusSelection(localUnits))
                    } else {
                        // Blank/invalid input belongs on the editable source page;
                        // it must never strand the user on a terminal error page.
                        effectChannel.send(SemanticSplitEffect.ReturnUpstream)
                    }
                    mutableUiState.update { it.copy(processing = false, failed = false) }
                }
            }
        }
    }
}
