package com.reallanguage.app.feature.learning.domain

data class SelectableExpression(
    val expressionId: String,
    val display: String,
    val startOffset: Int,
    val endOffset: Int,
)

data class NaturalExpressionExample(val textEn: String, val textZh: String)

data class ExpressionExplanation(
    val expressionId: String,
    val explanationZh: String,
    val phonetic: String? = null,
    val naturalExample: NaturalExpressionExample? = null,
    val contextualMeaningZh: String? = null,
    val usageZh: String? = null,
    val expressionHintZh: String? = null,
) {
    init {
        val legacy = contextualMeaningZh == null && usageZh == null && expressionHintZh == null
        require(legacy || (!contextualMeaningZh.isNullOrBlank() && !usageZh.isNullOrBlank() &&
            (expressionHintZh == null || expressionHintZh.isNotBlank())))
    }
}

/** One decoder for LR-02 progress and its read-only completed-step view. No AI fallback. */
internal fun savedExpressionExplanation(values: Map<String, String>): ExpressionExplanation? {
    val groupKeys = setOf("expressionContextualMeaningZh", "expressionUsageZh", "expressionHintZh")
    val hasGroup = groupKeys.any(values::containsKey)
    val id = values["expressionId"] ?: run { require(!hasGroup); return null }
    val explanation = requireNotNull(values["expressionZh"]).also { require(it.isNotBlank()) }
    val english = values["exampleEn"]
    val chinese = values["exampleZh"]
    require((english == null) == (chinese == null))
    val example = english?.let {
        require(it.isNotBlank() && !chinese.isNullOrBlank())
        NaturalExpressionExample(it, requireNotNull(chinese))
    }
    if (hasGroup) require(groupKeys.all(values::containsKey))
    // Maps contain strings only: an explicit empty hint marks known null, unlike an absent group.
    val hint = values["expressionHintZh"]?.let { if (it.isEmpty()) null else it.also { value -> require(value.isNotBlank()) } }
    return ExpressionExplanation(id, explanation, values["phonetic"]?.takeIf { it.isNotBlank() }, example,
        values["expressionContextualMeaningZh"], values["expressionUsageZh"], hint)
}

sealed interface ExplanationContentResult {
    data class Ready(val explanationZh: String) : ExplanationContentResult
    data class ExpressionReady(val explanation: ExpressionExplanation) :
        ExplanationContentResult
    data object Failed : ExplanationContentResult
}

interface ExplanationReadingProvider {
    /** Read-only validation against the current locked sentence; never generates or charges. */
    suspend fun validateSavedExpression(sentenceId: String, explanation: ExpressionExplanation): Boolean = true

    suspend fun validateSavedExpressionOccurrence(sentenceId: String, expression: SelectableExpression,
        explanation: ExpressionExplanation): Boolean = expression.expressionId == explanation.expressionId &&
        validateSavedExpression(sentenceId, explanation)

    suspend fun incrementalHint(
        sentenceId: String,
        round: Int,
        baseExplanationZh: String,
    ): ExplanationContentResult

    suspend fun explainExpression(
        sentenceId: String,
        expressionId: String,
    ): ExplanationContentResult
}

sealed interface ExplanationPreparationResult {
    data class Ready(
        val baseExplanationZh: String,
        val expressions: List<SelectableExpression>,
    ) : ExplanationPreparationResult
    data object Failed : ExplanationPreparationResult
}
fun interface ExplanationPreparationRepository {
    suspend fun prepare(): ExplanationPreparationResult
}
