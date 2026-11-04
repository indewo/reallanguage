package com.reallanguage.app.feature.review.domain

data class ReviewResultSummary(
    val completedFocusSentenceCount: Int,
    val remainingFocusSentenceCount: Int,
    val cycleCompleted: Boolean,
    val normalLearningDaysReset: Boolean,
)

sealed interface ReviewSettlementResult {
    data class Settled(val summary: ReviewResultSummary) : ReviewSettlementResult
    data class AlreadySettled(val summary: ReviewResultSummary) : ReviewSettlementResult
    data object Failed : ReviewSettlementResult
}

interface ReviewResultRepository {
    suspend fun settleSession(reviewSessionId: String, commandId: String): ReviewSettlementResult
}
