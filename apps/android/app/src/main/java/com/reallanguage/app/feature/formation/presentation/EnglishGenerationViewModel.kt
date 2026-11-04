package com.reallanguage.app.feature.formation.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.formation.domain.ChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishGenerationRepository
import com.reallanguage.app.feature.formation.domain.EnglishGenerationResult
import com.reallanguage.app.feature.formation.domain.SemanticUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class EnglishGenerationViewModel(
    private val units: List<SemanticUnit>,
    private val selectedUnitIds: List<String>,
    private val chapters: List<ChapterDraft>,
    private val repository: EnglishGenerationRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(EnglishGenerationUiState())
    val uiState = mutableUiState.asStateFlow()
    private val effectChannel = Channel<EnglishGenerationEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    init { generate() }
    fun onAction(action: EnglishGenerationAction) = when (action) {
        EnglishGenerationAction.RetryRequested -> generate()
        EnglishGenerationAction.ReturnToSelectionRequested -> viewModelScope.launch { effectChannel.send(EnglishGenerationEffect.ReturnToFocusSelection) }
    }
    private fun generate() {
        if (mutableUiState.value.processing) return
        mutableUiState.update { EnglishGenerationUiState(processing = true) }
        viewModelScope.launch {
            when (val result = repository.generate(units, selectedUnitIds, chapters)) {
                is EnglishGenerationResult.Generated -> effectChannel.send(EnglishGenerationEffect.OpenConfirmation(result.chapters))
                EnglishGenerationResult.Failed -> mutableUiState.update { EnglishGenerationUiState(processing = false, failed = true) }
            }
        }
    }
}
