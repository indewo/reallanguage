package com.reallanguage.app.feature.formation.presentation

import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.LearningTarget

data class FinalFixUiState(
    val chapters: List<EnglishChapterDraft>,
    val selectedCandidateIds: Map<String, String>,
    val selectedTargets: List<LearningTarget>,
    val submitting: Boolean = false,
    val fixFailed: Boolean = false,
    val confirmEnabled: Boolean = true,
)

sealed interface FinalFixAction {
    data object ConfirmRequested : FinalFixAction
    data object BackRequested : FinalFixAction
}

sealed interface FinalFixEffect {
    data class OpenFirstChapter(val chapterId: String) : FinalFixEffect
    data object ReturnToLearningTargets : FinalFixEffect
}
