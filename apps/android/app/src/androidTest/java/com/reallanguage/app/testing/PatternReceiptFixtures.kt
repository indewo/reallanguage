package com.reallanguage.app.testing

import com.reallanguage.app.feature.review.data.AndroidReviewChapterRepository
import com.reallanguage.app.feature.review.domain.*
import java.util.UUID

internal suspend fun ReviewNoJudgmentFixture.receiptContext(): Pair<ReviewPatternEntryLease, PatternIndependentReceiptIdentity> {
    val reviewId = queue().first().reviewChapterId
    val chapter = (AndroidReviewChapterRepository(context, runtime).loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter
    val ability = chapter.abilities.single { it.module == ReviewAbilityModule.PATTERN_SPEAKING }
    val lease = ReviewPatternEntryLease(account, runtime.accountEpoch, reviewId, UUID.randomUUID().toString())
    return lease to PatternIndependentReceiptIdentity(account, runtime.accountEpoch, reviewId, lease.entryId,
        chapter.originalChapterId, ability.sentenceId, ability.abilityId, ability.targetId)
}

/** Explicit isolated adjudication fixture; not a recording or AI quality assertion. */
internal fun PatternIndependentReceiptIdentity.receiptFixture(passed: Boolean = true) = PatternIndependentReceipt(
    1, this, "example", "isolated-evaluation-command", "a".repeat(64), "b".repeat(64),
    if (passed) PatternIndependentDecision.PASS else PatternIndependentDecision.FAIL,
    passed, true, true, .9,
)

internal fun PatternIndependentReceipt.asDraft() = ReviewAbilityResultDraft(identity.abilityId, evidenceId,
    decision == PatternIndependentDecision.PASS, patternReceipt = this)
