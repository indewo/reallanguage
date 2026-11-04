package com.reallanguage.app.feature.learning.domain

data class CompletedStepSnapshot(
    val stepId: String,
    val titleZh: String,
    val contentEn: String,
    val contentZh: String,
    val explanationZh: String?,
    val contextSentences: List<FixedLearningSentence> = emptyList(),
    val activeSentenceId: String? = null,
    val chapterId: String? = null,
    val expressions: List<SelectableExpression> = emptyList(),
    val reviewedExpression: CompletedStepExpression? = null,
    val completedSceneTasks: List<FixedSceneResponseTask> = emptyList(),
)

data class CompletedStepExpression(
    val expression: SelectableExpression,
    val explanation: ExpressionExplanation,
)

/** Read previously delivered learning content only; never generate or change progress. */
fun CompletedStepSnapshot.withSavedExpression(values: Map<String, String>?): CompletedStepSnapshot {
    if (values == null) return this
    require(values["version"] == "1")
    val explanation = savedExpressionExplanation(values) ?: return this
    val id = explanation.expressionId
    val expression = expressions.single { it.expressionId == id }
    require(expression.startOffset >= 0 && expression.endOffset <= contentEn.length && expression.startOffset < expression.endOffset)
    require(contentEn.substring(expression.startOffset, expression.endOffset) == expression.display)
    if (values["activeExpressionId"]?.let { it != id } == true) return this
    return copy(reviewedExpression = CompletedStepExpression(expression, explanation))
}
