package com.reallanguage.app.feature.review.presentation

import com.reallanguage.app.feature.review.domain.ReviewChapterSummary

data class TodayReviewUiState(
    val dailyFocusSentenceLimit: Int = 3,
    val chapters: List<ReviewChapterSummary> = emptyList(),
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val deferredByDailyLimit: Boolean = false,
) {
    val currentChapter: ReviewChapterSummary?
        get() = chapters.firstOrNull()
}

sealed interface TodayReviewAction {
    data object StartCurrentChapterRequested : TodayReviewAction
    data object ReturnHomeRequested : TodayReviewAction
    data object RetryRequested : TodayReviewAction
}

sealed interface TodayReviewEffect {
    data class OpenReviewChapter(val reviewChapterId: String) : TodayReviewEffect
    data object ReturnHome : TodayReviewEffect
}
