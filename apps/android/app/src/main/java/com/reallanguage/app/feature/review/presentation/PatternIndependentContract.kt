package com.reallanguage.app.feature.review.presentation

import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.feature.learning.domain.PatternPracticeExample
import com.reallanguage.app.feature.review.domain.PatternIndependentDecision
import com.reallanguage.app.feature.review.domain.PatternIndependentReceipt

data class PatternIndependentUiState(
    val pattern: LockedSpeakingPattern,
    val example: PatternPracticeExample? = null,
    val loading: Boolean = true,
    val recording: Boolean = false,
    val evaluating: Boolean = false,
    val decision: PatternIndependentDecision? = null,
    val feedbackZh: String? = null,
    val failed: Boolean = false,
    val notNaturallyVerifiable: Boolean = false,
    val receipt: PatternIndependentReceipt? = null,
    val technicalRetryPending: Boolean = false,
    val technicalRetryBlocked: Boolean = false,
)

sealed interface PatternIndependentAction {
    data object BackRequested : PatternIndependentAction
    data object StartRequested : PatternIndependentAction
    data object FinishedRequested : PatternIndependentAction
    data object RetryRequested : PatternIndependentAction
    data object ContinueRequested : PatternIndependentAction
}

sealed interface PatternIndependentEffect {
    data object ReturnToChapter : PatternIndependentEffect
    data class Completed(val passed: Boolean, val receipt: PatternIndependentReceipt? = null) : PatternIndependentEffect
    data object NotNaturallyVerifiable : PatternIndependentEffect
}
