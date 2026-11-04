package com.reallanguage.app.feature.formation.presentation

import com.reallanguage.app.feature.formation.domain.SemanticUnit

data class FocusSelectionUiState(
    val units: List<SemanticUnit>,
    val selectedUnitIds: Set<String> = emptySet(),
    val selectionLimitReached: Boolean = false,
    val confirmEnabled: Boolean = false,
    val submitting: Boolean = false,
)

sealed interface FocusSelectionAction {
    data class UnitToggled(val unitId: String) : FocusSelectionAction
    data object ConfirmRequested : FocusSelectionAction
    data object BackRequested : FocusSelectionAction
}

sealed interface FocusSelectionEffect {
    data class OpenChapterFormation(val selectedUnitIds: List<String>) : FocusSelectionEffect
    data object ReturnUpstream : FocusSelectionEffect
}
