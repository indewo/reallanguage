package com.reallanguage.app.feature.review.presentation

import com.reallanguage.app.feature.review.domain.ReviewAbility
import com.reallanguage.app.feature.review.domain.ReviewAbilityModule
import com.reallanguage.app.feature.review.domain.ReviewAbilityResultDraft
import com.reallanguage.app.feature.review.domain.ReviewChapterSnapshot

data class ReviewChapterUiState(
    val reviewChapterId: String,
    val commandId: String,
    val chapter: ReviewChapterSnapshot? = null,
    val resultDrafts: List<ReviewAbilityResultDraft> = emptyList(),
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val committing: Boolean = false,
    val commitFailed: Boolean = false,
    val completed: Boolean = false,
) {
    val pendingAbilities: List<ReviewAbility>
        get() = chapter?.abilities.orEmpty().filter { ability ->
            ability.requiresReview && resultDrafts.none { it.abilityId == ability.abilityId }
        }

    val currentAbility: ReviewAbility?
        get() = pendingAbilities.firstOrNull()
}

sealed interface ReviewChapterAction {
    data object OpenCurrentModuleRequested : ReviewChapterAction
    data class ModuleCompleted(
        val abilityId: String,
        val evidenceId: String,
        val passed: Boolean = true,
        val qualification: com.reallanguage.app.feature.review.domain.ReviewResultQualification =
            com.reallanguage.app.feature.review.domain.ReviewResultQualification.INDEPENDENT,
        val patternReceipt: com.reallanguage.app.feature.review.domain.PatternIndependentReceipt? = null,
    ) : ReviewChapterAction
    data object RetryLoadRequested : ReviewChapterAction
    data object RetryCommitRequested : ReviewChapterAction
    data object ExitRequested : ReviewChapterAction
}

sealed interface ReviewChapterEffect {
    data class OpenAbilityModule(
        val abilityId: String,
        val module: ReviewAbilityModule,
    ) : ReviewChapterEffect
    data class OpenNextReviewChapter(val reviewChapterId: String) : ReviewChapterEffect
    data object OpenReviewResult : ReviewChapterEffect
    data class OpenReviewResultForSession(val reviewSessionId: String) : ReviewChapterEffect
    data object OpenExitConfirmation : ReviewChapterEffect
}
