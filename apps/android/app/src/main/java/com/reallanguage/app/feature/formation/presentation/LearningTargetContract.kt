package com.reallanguage.app.feature.formation.presentation

import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.QueryableExpression

data class LearningTargetUiState(
    val chapters: List<EnglishChapterDraft>,
    val selectedCandidateIds: Map<String, String>,
    val targets: List<LearningTarget>,
    val selectedTargetIds: Set<String>,
    val queryableExpressions: List<QueryableExpression>,
    val generating: Boolean = false,
    val generationFailed: Boolean = false,
    val continueEnabled: Boolean = true,
    val submitting: Boolean = false,
    val focusSentenceIndex: Int = 0,
    val recoveryUnavailable: Boolean = false,
) {
    val focusSentences get() = chapters.flatMap { it.sentences }.filter { it.isFocus }
    val currentSentence get() = focusSentences.getOrNull(focusSentenceIndex)
    val currentTargets get() = targets.filter { it.unitId == currentSentence?.unitId }
    /**
     * The cards above are the AI-curated choices. The manual section below is a
     * fallback for expressions the AI did not curate, so the same expression must
     * never be offered in both places (even after the upper card is deselected).
     */
    val currentExpressions get() = queryableExpressions.filter { expression ->
        expression.unitId == currentSentence?.unitId && currentTargets.none { target ->
            target.represents(expression)
        }
    }
}

private fun LearningTarget.represents(expression: QueryableExpression): Boolean {
    if (unitId != expression.unitId) return false
    val requested = expression.display.choiceKey()
    if (requested.isEmpty()) return false
    return buildList {
        add(display.substringBefore(" + "))
        sourceEvidence?.let { evidence ->
            add(evidence.surfaceForm)
            add(evidence.normalizedForm)
            addAll(evidence.allowedForms)
        }
    }.any { it.choiceKey() == requested }
}

private fun String.choiceKey(): String = trim().lowercase()
    .replace(Regex("[\\s\\u00a0]+"), " ")
    .trim('.', ',', '!', '?', ';', ':', '，', '。', '！', '？', '；', '：')

sealed interface LearningTargetAction {
    data class TargetToggled(val targetId: String) : LearningTargetAction
    data class ExpressionRequested(
        val unitId: String,
        val expressionId: String,
    ) : LearningTargetAction
    data object ContinueRequested : LearningTargetAction
    data object ClearAllRequested : LearningTargetAction
    data object BackRequested : LearningTargetAction
}

sealed interface LearningTargetEffect {
    data class OpenFinalConfirmation(val selectedTargetIds: Set<String>, val targets: List<LearningTarget> = emptyList()) :
        LearningTargetEffect
    data object ReturnToEnglishConfirmation : LearningTargetEffect
}
