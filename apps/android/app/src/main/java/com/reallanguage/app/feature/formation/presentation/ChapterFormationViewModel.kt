package com.reallanguage.app.feature.formation.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.formation.domain.ChapterFormationRepository
import com.reallanguage.app.feature.formation.domain.ChapterFormationResult
import com.reallanguage.app.feature.formation.domain.SemanticUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChapterFormationViewModel(
    private val units: List<SemanticUnit>,
    selectedUnitIds: List<String>,
    private val repository: ChapterFormationRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        ChapterFormationUiState(
            selectedUnitIds = selectedUnitIds,
            selectedUnits = units.filter { it.unitId in selectedUnitIds }.sortedBy { it.order },
        ),
    )
    val uiState: StateFlow<ChapterFormationUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<ChapterFormationEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    init { formChapters() }

    private fun formChapters() {
        if (mutableUiState.value.processing) return
        mutableUiState.update { it.copy(processing = true, failed = false) }
        viewModelScope.launch {
            when (
                val result = repository.formChapters(
                    units,
                    mutableUiState.value.selectedUnitIds,
                )
            ) {
                is ChapterFormationResult.Formed -> effectChannel.send(
                    ChapterFormationEffect.OpenEnglishConfirmation(result.chapters),
                )
                ChapterFormationResult.Failed -> mutableUiState.update {
                    it.copy(processing = false, failed = true)
                }
            }
        }
    }

}
