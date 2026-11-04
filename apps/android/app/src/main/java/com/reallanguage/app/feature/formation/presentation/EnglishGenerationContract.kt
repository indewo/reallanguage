package com.reallanguage.app.feature.formation.presentation

import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft

data class EnglishGenerationUiState(val processing: Boolean = false, val failed: Boolean = false)
sealed interface EnglishGenerationAction {
    data object RetryRequested : EnglishGenerationAction
    data object ReturnToSelectionRequested : EnglishGenerationAction
}
sealed interface EnglishGenerationEffect {
    data class OpenConfirmation(val chapters: List<EnglishChapterDraft>) : EnglishGenerationEffect
    data object ReturnToFocusSelection : EnglishGenerationEffect
}
