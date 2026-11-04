package com.reallanguage.app.feature.formation.presentation

import com.reallanguage.app.feature.formation.domain.ChapterDraft
import com.reallanguage.app.feature.formation.domain.SemanticUnit

data class ChapterFormationUiState(
    val selectedUnitIds: List<String>,
    val processing: Boolean = false,
    val failed: Boolean = false,
    val selectedUnits: List<SemanticUnit> = emptyList(),
)

sealed interface ChapterFormationEffect {
    data class OpenEnglishConfirmation(val chapters: List<ChapterDraft>) :
        ChapterFormationEffect
}
