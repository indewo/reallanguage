package com.reallanguage.app.feature.safety.domain

enum class ManualReviewOutcome { APPROVED, NOT_APPROVED }

data class ReviewedSafetySource(
    val safetyCaseId: String,
    val text: String,
    val sourceType: SourceType,
    val contentShape: SourceContentShape,
    val userReasonZh: String,
)
sealed interface ManualReviewResult {
    data class Ready(val outcome: ManualReviewOutcome, val source: ReviewedSafetySource) : ManualReviewResult
    data object Unavailable : ManualReviewResult
    data object Failed : ManualReviewResult
    data object RequiresLogin : ManualReviewResult
}
fun interface ManualReviewResultRepository { suspend fun load(reviewRequestId: String): ManualReviewResult }
