package com.reallanguage.app.feature.formation.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.formation.domain.SemanticUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FocusSelectionViewModel(units: List<SemanticUnit>, initialSelectedUnitIds: Set<String> = emptySet()) : ViewModel() {
    private val orderedUnits = units.sortedBy { it.order }
    private val restoredSelection = initialSelectedUnitIds.takeIf { selected ->
        selected.size <= MAX_SELECTION && orderedUnits.map { it.unitId }.toSet().containsAll(selected)
    }.orEmpty()
    private val mutableUiState = MutableStateFlow(
        FocusSelectionUiState(
            units = orderedUnits,
            selectedUnitIds = restoredSelection,
            selectionLimitReached = restoredSelection.size == MAX_SELECTION,
            confirmEnabled = restoredSelection.isNotEmpty(),
        ),
    )
    val uiState: StateFlow<FocusSelectionUiState> = mutableUiState.asStateFlow()

    private val effectChannel = Channel<FocusSelectionEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    fun onAction(action: FocusSelectionAction) {
        if (mutableUiState.value.submitting) return
        when (action) {
            is FocusSelectionAction.UnitToggled -> toggle(action.unitId)
            FocusSelectionAction.ConfirmRequested -> confirm()
            FocusSelectionAction.BackRequested -> viewModelScope.launch {
                effectChannel.send(FocusSelectionEffect.ReturnUpstream)
            }
        }
    }

    private fun toggle(unitId: String) {
        val current = mutableUiState.value
        if (current.units.none { it.unitId == unitId }) return
        val selected = current.selectedUnitIds.toMutableSet()
        if (!selected.remove(unitId)) {
            if (selected.size >= MAX_SELECTION) return
            selected += unitId
        }
        mutableUiState.update {
            it.copy(
                selectedUnitIds = selected,
                selectionLimitReached = selected.size == MAX_SELECTION,
                confirmEnabled = selected.isNotEmpty(),
            )
        }
    }

    private fun confirm() {
        val current = mutableUiState.value
        if (!current.confirmEnabled) return
        mutableUiState.update { it.copy(submitting = true, confirmEnabled = false) }
        val orderedIds = current.units
            .filter { it.unitId in current.selectedUnitIds }
            .map { it.unitId }
        viewModelScope.launch {
            effectChannel.send(FocusSelectionEffect.OpenChapterFormation(orderedIds))
        }
    }

    private companion object {
        const val MAX_SELECTION = 3
    }
}
