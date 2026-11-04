package com.reallanguage.app.feature.formation.presentation

import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import com.reallanguage.app.feature.formation.domain.QueryableExpression
import com.reallanguage.app.feature.formation.domain.isValidForTarget
import com.reallanguage.app.feature.formation.domain.uniqueLiteralSourceEvidence

/** A word click identifies a source position, not every occurrence of its spelling. */
internal fun LearningTarget.matchesRequestedExpression(expression: QueryableExpression, textEn: String): Boolean {
    if (unitId != expression.unitId || !display.trim().equals(expression.display.trim(), ignoreCase = true)) return false
    if (!expression.expressionId.startsWith("word:")) return true
    if (type == LearningTargetType.PATTERN) return false
    val start = expression.expressionId.removePrefix("word:").toIntOrNull() ?: return false
    val evidence = sourceEvidence ?: uniqueLiteralSourceEvidence(textEn, display) ?: return false
    return evidence.isValidForTarget(textEn, display, type) && evidence.startUtf16 == start &&
        evidence.endUtf16 == start + expression.display.length &&
        evidence.surfaceForm.equals(expression.display, ignoreCase = true)
}

internal fun LearningTargetUiState.existingTargetForExpression(expression: QueryableExpression): LearningTarget? {
    val sentence = currentSentence ?: return null
    val english = sentence.candidates.singleOrNull { it.candidateId == selectedCandidateIds[sentence.unitId] }?.textEn ?: return null
    // Ambiguous historical candidates remain on their cards, never arbitrarily bound to a word.
    return currentTargets.singleOrNull { it.matchesRequestedExpression(expression, english) }
}
