package com.reallanguage.app.feature.formation.presentation

import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft

data class EnglishConfirmationUiState(
    val chapters: List<EnglishChapterDraft>,
    val selectedCandidateIds: Map<String, String> = emptyMap(),
    val continueEnabled: Boolean = false,
    val submitting: Boolean = false,
)

sealed interface EnglishConfirmationAction {
    data class CandidateSelected(
        val unitId: String,
        val candidateId: String,
    ) : EnglishConfirmationAction
    data object ContinueRequested : EnglishConfirmationAction
    data object BackRequested : EnglishConfirmationAction
}

sealed interface EnglishConfirmationEffect {
    data class OpenLearningTargets(
        val chapters: List<EnglishChapterDraft>,
        val selectedCandidateIds: Map<String, String>,
    ) : EnglishConfirmationEffect
    data object ReturnToFocusSelection : EnglishConfirmationEffect
}
