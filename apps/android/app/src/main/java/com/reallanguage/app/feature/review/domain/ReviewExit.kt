package com.reallanguage.app.feature.review.domain

sealed interface ReviewExitResult {
    data object Discarded : ReviewExitResult
    data object AlreadyDiscarded : ReviewExitResult
    data object Failed : ReviewExitResult
}

interface ReviewExitRepository {
    /** A negative count means unavailable, never a fabricated zero completed chapters. */
    suspend fun completedChapterCount(reviewChapterId: String): Int = -1
    suspend fun discardWorkingState(reviewChapterId: String): ReviewExitResult
}
