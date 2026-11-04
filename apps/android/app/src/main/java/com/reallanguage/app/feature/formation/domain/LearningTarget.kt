package com.reallanguage.app.feature.formation.domain

enum class LearningTargetType {
    WORD,
    FIXED_EXPRESSION,
    PATTERN,
}

/** A candidate-card illustration, never fixed source evidence or a grading answer. */
data class CandidateExample(val textEn: String, val textZh: String)

fun CandidateExample.isValid(): Boolean = textEn.isNotBlank() && textZh.isNotBlank()

data class LearningTarget(
    val targetId: String,
    val unitId: String,
    val display: String,
    val explanationZh: String,
    val type: LearningTargetType,
    val structureParts: List<String> = emptyList(),
    val relatedVocabulary: List<String> = emptyList(),
    val learningScopes: Set<String> = emptySet(),
    val sourceEvidence: SourceEvidence? = null,
    val knownKnowledgeRef: String? = null,
    val candidateExample: CandidateExample? = null,
    val relatedVocabularyItems: List<RelatedVocabularyItem>? = null,
    val relatedVocabularyItemRefs: List<String>? = null,
)

/** Validate supplied evidence without reformatting it or inventing missing legacy parts. */
fun LearningTarget.hasValidStructureParts(requirePatternStructure: Boolean = true): Boolean =
    structureParts.all(String::isNotBlank) &&
        (!requirePatternStructure || type != LearningTargetType.PATTERN || structureParts.isNotEmpty())

fun LearningTarget.hasValidCandidateExample(): Boolean = candidateExample?.isValid() != false

/**
 * Vocabulary-book entries are lexical units, not complete utterances. The
 * external word lists can refine membership later; this invariant already
 * rejects an AI response that labels a whole sentence as WORD/FIXED_EXPRESSION.
 */
fun LearningTarget.isVocabularyBookEligible(textEn: String): Boolean {
    if (type == LearningTargetType.PATTERN) return true
    val words = sourceWordTokens.findAll(display.trim()).map { it.value }.toList()
    if (type == LearningTargetType.WORD) return words.size == 1
    if (words.isEmpty() || words.size > 7) return false
    val sourceWords = sourceWordTokens.findAll(textEn).toList()
    val coversWholeSentence = sourceEvidence?.let { evidence ->
        sourceWords.firstOrNull()?.range?.first == evidence.startUtf16 &&
            sourceWords.lastOrNull()?.range?.last?.plus(1) == evidence.endUtf16
    } ?: normalizeLexical(display) == normalizeLexical(textEn)
    return !(coversWholeSentence && looksLikeCompleteSentence(textEn, sourceWords.size)) &&
        !looksLikeCompleteSentence(display, words.size)
}

/** Narrow historical-data filter used when the original sentence is unavailable. */
fun String.isPlausibleVocabularyDisplay(): Boolean {
    val count = sourceWordTokens.findAll(this).count()
    return count in 1..7 && !looksLikeCompleteSentence(this, count)
}

private fun looksLikeCompleteSentence(value: String, wordCount: Int): Boolean {
    if (wordCount < 4) return false
    val text = value.trim()
    val startsWithSubject = Regex("^(?:i|you|he|she|we|they|it|this|that|there)\\b", RegexOption.IGNORE_CASE)
        .containsMatchIn(text)
    val startsWithQuestion = Regex(
        "^(?:can|could|would|will|do|does|did|is|are|was|were|have|has|had|may|might|should|must)\\s+(?:i|you|he|she|we|they|it|this|that|there)\\b",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(text)
    return startsWithSubject || startsWithQuestion || (wordCount >= 7 && text.lastOrNull() in setOf('.', '!', '?'))
}

private fun normalizeLexical(value: String): String = sourceWordTokens.findAll(value)
    .joinToString(" ") { it.value.lowercase() }

data class QueryableExpression(
    val expressionId: String,
    val unitId: String,
    val display: String,
    /** Preview-only contextual meaning shown before the user adds this optional target. */
    val explanationZh: String? = null,
)

/** Offsets are within the confirmed English text, never positions in AI suggestions. */
fun EnglishSentenceDraft.wordExpressions(selectedCandidateId: String?): List<QueryableExpression> {
    val english = candidates.firstOrNull { it.candidateId == selectedCandidateId }?.textEn ?: return emptyList()
    return sourceWordTokens.findAll(english).map {
        QueryableExpression("word:${it.range.first}", unitId, it.value)
    }.toList()
}

sealed interface LearningTargetGenerationResult {
    data class Added(val target: LearningTarget) : LearningTargetGenerationResult
    data object Failed : LearningTargetGenerationResult
}

fun interface LearningTargetGenerator {
    suspend fun generate(
        unitId: String,
        expressionId: String,
    ): LearningTargetGenerationResult
}

sealed interface LearningTargetExtractionResult {
    data class Extracted(
        val targets: List<LearningTarget>,
        val queryableExpressions: List<QueryableExpression>,
    ) : LearningTargetExtractionResult
    data object Failed : LearningTargetExtractionResult
}

interface LearningTargetExtractionRepository : LearningTargetGenerator {
    suspend fun extract(): LearningTargetExtractionResult
}
