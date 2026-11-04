package com.reallanguage.app.feature.review.domain

enum class ReviewAbilityModule {
    COMPLETE_WRITING,
    FULL_SENTENCE_SPEAKING,
    SCENE_RESPONSE,
    WORD_MEANING,
    PATTERN_WRITING,
    PATTERN_SPEAKING;

    val supportsWritingPractice: Boolean
        get() = this == COMPLETE_WRITING || this == WORD_MEANING || this == PATTERN_WRITING
}

enum class ReviewAbilityStatus {
    NOT_STARTED,
    LEARNING,
    TENTATIVE,
    MASTERED,
}

data class ReviewAbility(
    val abilityId: String,
    val module: ReviewAbilityModule,
    val status: ReviewAbilityStatus,
    val sentenceId: String = "",
    val targetId: String = "",
    val requiresReview: Boolean = status != ReviewAbilityStatus.MASTERED,
)

data class ReviewChapterSnapshot(
    val reviewChapterId: String,
    val originalChapterId: String,
    val sourceType: ReviewSourceType,
    val sourceTitle: String,
    val originalScene: List<String>,
    val focusSentence: String,
    val abilities: List<ReviewAbility>,
    val originalChapterNumber: Int? = null,
    val originalSentences: List<ReviewContextSentence>? = null,
)

/** Read-only fixed-source identity; null metadata on older snapshots is not inferred from text. */
data class ReviewContextSentence(
    val sentenceId: String,
    val textEn: String,
    val textZh: String,
    val isFocus: Boolean,
    val sentenceOrder: Int,
)

enum class ReviewResultQualification { INDEPENDENT, SUPPORTED, NOT_NATURALLY_VERIFIABLE }

data class ReviewAbilityResultDraft(
    val abilityId: String,
    val evidenceId: String,
    val passed: Boolean = true,
    val qualification: ReviewResultQualification = ReviewResultQualification.INDEPENDENT,
    val patternReceipt: PatternIndependentReceipt? = null,
)

/** A handled module is not necessarily an independent judgment or a mastered ability. */
fun ReviewAbilityResultDraft.isValidFor(module: ReviewAbilityModule): Boolean = when (qualification) {
    ReviewResultQualification.INDEPENDENT -> if (module == ReviewAbilityModule.PATTERN_SPEAKING) {
        patternReceipt?.let { receipt -> receipt.hasValidIndependentJudgment() && receipt.identity.abilityId == abilityId &&
            receipt.evidenceId == evidenceId && passed == (receipt.decision == PatternIndependentDecision.PASS) } == true
    } else evidenceId.isNotBlank() && patternReceipt == null
    ReviewResultQualification.SUPPORTED -> patternReceipt == null && evidenceId.isNotBlank() && !passed && module.supportsWritingPractice
    ReviewResultQualification.NOT_NATURALLY_VERIFIABLE ->
        patternReceipt == null && evidenceId.isEmpty() && !passed && module == ReviewAbilityModule.PATTERN_SPEAKING
}

sealed interface ReviewChapterLoadResult {
    data class Ready(val chapter: ReviewChapterSnapshot) : ReviewChapterLoadResult
    data object Failed : ReviewChapterLoadResult
}

sealed interface ReviewChapterCommitResult {
    data class Committed(
        val nextReviewChapterId: String?,
        val reviewSessionId: String = "",
    ) : ReviewChapterCommitResult
    data class AlreadyCommitted(
        val nextReviewChapterId: String?,
        val reviewSessionId: String = "",
    ) : ReviewChapterCommitResult
    data object Failed : ReviewChapterCommitResult
}

interface ReviewChapterRepository {
    suspend fun loadChapter(reviewChapterId: String): ReviewChapterLoadResult

    suspend fun commitChapter(
        reviewChapterId: String,
        commandId: String,
        results: List<ReviewAbilityResultDraft>,
    ): ReviewChapterCommitResult
}
