package com.reallanguage.app.feature.review.domain

import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.feature.learning.domain.PatternPracticeSet

enum class PatternIndependentDecision { PASS, FAIL, INCONCLUSIVE }

data class PatternIndependentEvaluation(
    val decision: PatternIndependentDecision,
    val feedbackZh: String,
    val receipt: PatternIndependentReceipt? = null,
)

sealed interface PatternIndependentGenerationResult {
    data class Ready(val practiceSet: PatternPracticeSet) : PatternIndependentGenerationResult
    data object NotNaturallyVerifiable : PatternIndependentGenerationResult
    data object Failed : PatternIndependentGenerationResult
}

sealed interface PatternIndependentEvaluationResult {
    data class Ready(val evaluation: PatternIndependentEvaluation) : PatternIndependentEvaluationResult
    data object Failed : PatternIndependentEvaluationResult
}

interface PatternIndependentRepository {
    fun startCapture(): Boolean
    fun cancelCapture()
    fun hasPendingEvaluationRetry(): Boolean = false
    fun technicalRetryBlocked(): Boolean = false
    suspend fun generate(pattern: LockedSpeakingPattern): PatternIndependentGenerationResult
    suspend fun evaluate(patternId: String, exampleId: String): PatternIndependentEvaluationResult
}
