package com.reallanguage.app.core.app

import com.reallanguage.app.feature.learning.domain.LockedWritingTarget
import com.reallanguage.app.feature.learning.domain.WritingTargetType
import com.reallanguage.app.feature.review.domain.ReviewAbilityModule
import com.reallanguage.app.feature.review.domain.ReviewAbility
import com.reallanguage.app.feature.review.domain.ReviewChapterLoadResult

internal suspend fun loadReviewWritingTargets(
    reviewChapterId: String,
    originalChapterId: String,
    ability: ReviewAbility,
    loadChapter: suspend (String) -> ReviewChapterLoadResult,
    readTarget: suspend (String, String, String) -> List<LockedWritingTarget>?,
): List<LockedWritingTarget>? {
    if (reviewChapterId.isBlank() || originalChapterId.isBlank() || ability.abilityId.isBlank() ||
        ability.sentenceId.isBlank() || !ability.requiresReview || !ability.module.supportsWritingPractice) return null
    if (ability.module == ReviewAbilityModule.COMPLETE_WRITING && ability.targetId.isNotEmpty()) return null
    val current = (loadChapter(reviewChapterId) as? ReviewChapterLoadResult.Ready)?.chapter ?: return null
    if (current.reviewChapterId != reviewChapterId || current.originalChapterId != originalChapterId) return null
    val queued = current.abilities.singleOrNull { it.abilityId == ability.abilityId } ?: return null
    if (!queued.requiresReview || queued.module != ability.module || queued.sentenceId != ability.sentenceId ||
        queued.targetId != ability.targetId) return null
    // State labels may change while the same ability remains eligible (e.g. a pattern streak < 3).
    return reviewWritingTargetSelection(
        ability.module, ability.sentenceId, ability.targetId,
        if (ability.module == ReviewAbilityModule.COMPLETE_WRITING) null
        else readTarget(originalChapterId, ability.sentenceId, ability.targetId),
    )
}

internal fun reviewWritingTargetSelection(
    module: ReviewAbilityModule,
    sentenceId: String,
    targetId: String,
    locked: List<LockedWritingTarget>?,
): List<LockedWritingTarget>? {
    if (module == ReviewAbilityModule.COMPLETE_WRITING) return emptyList()
    val type = when (module) {
        ReviewAbilityModule.WORD_MEANING -> WritingTargetType.MEANING
        ReviewAbilityModule.PATTERN_WRITING -> WritingTargetType.PATTERN
        else -> return null
    }
    if (targetId.isBlank() || locked == null) return null
    val target = locked.singleOrNull { it.targetId == targetId } ?: return null
    if (target.sentenceId != sentenceId || target.type != type ||
        target.expectedForms.isEmpty() || target.expectedForms.any { it.isBlank() }) return null
    return listOf(target)
}
