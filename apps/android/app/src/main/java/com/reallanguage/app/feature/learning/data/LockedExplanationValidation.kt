package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.feature.formation.domain.LearningTargetType
import com.reallanguage.app.feature.formation.domain.SourceEvidence
import com.reallanguage.app.feature.formation.domain.isValidFor
import com.reallanguage.app.feature.formation.domain.isValidForTarget
import com.reallanguage.app.feature.learning.domain.*

private val oldExplanationWords = Regex("[A-Za-z]+(?:'[A-Za-z]+)?")
private val wholeExplanationWords = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['‘’ʼ＇\\-][\\p{L}\\p{M}\\p{N}]+)*")

/** Preserve all existing complete-word IDs; never reinterpret a paid old partial-token ID. */
fun authoritativeExplanationWords(textEn: String): List<SelectableExpression> {
    val old = oldExplanationWords.findAll(textEn).mapIndexed { index, match ->
        (match.range.first to match.range.last + 1) to "word-$index"
    }.toMap()
    return wholeExplanationWords.findAll(textEn).map { match ->
        val start = match.range.first
        val end = match.range.last + 1
        SelectableExpression(old[start to end] ?: "word-utf16:$start:$end", match.value, start, end)
    }.toList()
}

fun validLockedExplanationContext(chapter: FixedLearningChapter, focusSentenceId: String, context: LockedExplanationContext): Boolean {
    val sentence = chapter.sentences.singleOrNull { it.sentenceId == focusSentenceId && it.isFocus } ?: return false
    if (context.chapterId != chapter.chapterId || context.focusSentenceId != focusSentenceId ||
        context.textEn != sentence.textEn || context.textEn.isBlank()) return false
    val ids = mutableSetOf<String>()
    val actualSpans = mutableSetOf<Pair<Int, Int>>()
    val meanings = mutableMapOf<Pair<Int, Int>, Triple<String, String, LearningTargetType>>()
    fun accept(evidence: SourceEvidence, display: String, meaning: String, type: LearningTargetType): Boolean {
        val span = evidence.startUtf16 to evidence.endUtf16
        val value = Triple(display, meaning, type)
        val previous = meanings.putIfAbsent(span, value)
        return previous == null || previous == value
    }
    for (target in context.targets) {
        if (target.targetId.isBlank() || target.targetId.length > 128 || !ids.add(target.targetId) || target.display.isBlank() ||
            target.explanationZh.isBlank() || target.structureParts.any(String::isBlank) ||
            (target.type == LearningTargetType.PATTERN && target.sourceEvidence != null && target.structureParts.isEmpty())) return false
        target.sourceEvidence?.let { evidence ->
            if (!evidence.isValidForTarget(context.textEn, target.display, target.type)) return false
            if (target.type != LearningTargetType.PATTERN &&
                (!actualSpans.add(evidence.startUtf16 to evidence.endUtf16) ||
                    !accept(evidence, target.display, target.explanationZh, target.type))) return false
        }
        val related = target.relatedVocabulary ?: continue
        if (target.type != LearningTargetType.PATTERN && related.isNotEmpty()) return false
        val relatedSpans = mutableSetOf<Pair<Int, Int>>()
        for (item in related) {
            val evidence = item.sourceEvidence
            if (item.display.isBlank() || item.senseZh.isBlank() || item.unitType == LearningTargetType.PATTERN ||
                !evidence.isValidForTarget(context.textEn, item.display, item.unitType) ||
                !relatedSpans.add(evidence.startUtf16 to evidence.endUtf16) ||
                !accept(evidence, item.display, item.senseZh, item.unitType)) return false
        }
    }
    return true
}

/** The caller supplies an authoritative occurrence, not a guessed span from the saved explanation's text. */
fun lockedExplanationMeaningMatches(context: LockedExplanationContext?, expression: SelectableExpression,
    explanation: ExpressionExplanation, allowLegacy: Boolean = true): Boolean {
    if (expression.expressionId != explanation.expressionId || explanation.explanationZh.isBlank()) return false
    if (context == null) return true
    if (!SourceEvidence(1, expression.startOffset, expression.endOffset, expression.display, expression.display,
            listOf(expression.display)).isValidFor(context.textEn)) return false
    val meaning = explanation.contextualMeaningZh ?: return allowLegacy
    fun matches(evidence: SourceEvidence?) = evidence != null && evidence.startUtf16 == expression.startOffset && evidence.endUtf16 == expression.endOffset
    val expected = context.targets.flatMap { target ->
        listOfNotNull(target.explanationZh.takeIf { target.type != LearningTargetType.PATTERN && matches(target.sourceEvidence) }) +
            target.relatedVocabulary.orEmpty().filter { matches(it.sourceEvidence) }.map { it.senseZh }
    }.distinct()
    return expected.size <= 1 && (expected.isEmpty() || meaning == expected.single())
}
