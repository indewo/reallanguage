package com.reallanguage.app.feature.review.domain

enum class ReviewSourceType {
    DIARY,
    QUICK_RECORDING,
    AI_SCENE,
}

data class ReviewChapterSummary(
    val reviewChapterId: String,
    val originalChapterId: String,
    val sourceType: ReviewSourceType,
    val sourceTitle: String,
    val focusSentence: String,
    val focusSentenceCount: Int,
    val pendingAbilityCount: Int,
)

sealed interface ReviewQueueResult {
    data class Ready(
        val chapters: List<ReviewChapterSummary>,
        val deferredByDailyLimit: Boolean = false,
    ) : ReviewQueueResult
    data object Failed : ReviewQueueResult
}

interface TodayReviewRepository {
    suspend fun loadTodayQueue(maxFocusSentences: Int): ReviewQueueResult
}
