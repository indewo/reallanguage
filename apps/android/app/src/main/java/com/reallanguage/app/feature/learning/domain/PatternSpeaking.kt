package com.reallanguage.app.feature.learning.domain

data class LockedSpeakingPattern(
    val patternId: String,
    val sourceSentenceId: String,
    val structure: String,
    val explanationZh: String,
)

data class PatternPracticeExample(
    val exampleId: String,
    val sceneZh: String,
    val aiPromptEn: String,
    val targetResponseEn: String,
    val targetResponseZh: String,
)

data class PatternPracticeSet(
    val patternId: String,
    val examples: List<PatternPracticeExample>,
)

sealed interface PatternPracticeGenerationResult {
    data class Ready(val practiceSet: PatternPracticeSet) : PatternPracticeGenerationResult
    data object NotNaturallyVerifiable : PatternPracticeGenerationResult
    data object Failed : PatternPracticeGenerationResult
}

data class PatternPracticeFeedback(
    val feedbackZh: String,
    val repeatRecommended: Boolean,
    val transcript: String = "",
)

sealed interface PatternPracticeFeedbackResult {
    data class Ready(val feedback: PatternPracticeFeedback) : PatternPracticeFeedbackResult
    data object Inconclusive : PatternPracticeFeedbackResult
    data object Failed : PatternPracticeFeedbackResult
}

sealed interface PatternFeedbackRecovery {
    data object None : PatternFeedbackRecovery
    data object Unavailable : PatternFeedbackRecovery
    data class Pending(val exampleId: String, val reading: Boolean) : PatternFeedbackRecovery
    data class Delivered(val exampleId: String, val reading: Boolean, val result: PatternPracticeFeedbackResult) : PatternFeedbackRecovery
    data class Blocked(val exampleId: String, val reading: Boolean) : PatternFeedbackRecovery
}

interface PatternSpeakingRepository {
    val requiresFeedbackRecovery: Boolean get() = false
    fun startCapture(): Boolean
    fun cancelCapture()
    /** Only the original, not-yet-delivered audio attempt can be technically retried. */
    fun hasPendingFeedbackRetry(): Boolean = false
    fun technicalRetryBlocked(): Boolean = false
    suspend fun prepareFeedbackCapture(patternId: String, exampleId: String, reading: Boolean): Boolean = true
    /** Ordinary-learning request custody only; no network call or automatic replay. */
    suspend fun restoreFeedback(pattern: LockedSpeakingPattern, practiceSet: PatternPracticeSet): PatternFeedbackRecovery = PatternFeedbackRecovery.None
    /** Called only after saved progress proves that an explicit skip moved beyond this response. */
    suspend fun releaseSupersededFeedback(): Boolean = true

    /** Rehydrate the exact persisted examples before capturing further practice. No generation. */
    fun restorePractice(pattern: LockedSpeakingPattern, practiceSet: PatternPracticeSet) = Unit

    suspend fun generate(pattern: LockedSpeakingPattern): PatternPracticeGenerationResult

    suspend fun readingFeedback(
        patternId: String,
        exampleId: String,
    ): PatternPracticeFeedbackResult

    suspend fun responseFeedback(
        patternId: String,
        exampleId: String,
    ): PatternPracticeFeedbackResult
}
