package com.reallanguage.app.feature.learning.domain

enum class WritingTargetType {
    MEANING,
    PATTERN,
}

data class WritingTargetSpan(
    val startUtf16: Int,
    val endUtf16: Int,
    val surfaceForm: String,
)

class WritingTargetEvidenceUnavailable : IllegalStateException("Writing target evidence is unavailable or ambiguous")

data class LockedWritingTarget(
    val targetId: String,
    val sentenceId: String,
    val type: WritingTargetType,
    val expectedForms: List<String>,
    val sourceSpan: WritingTargetSpan? = null,
)

data class WritingTargetResult(
    val targetId: String,
    val type: WritingTargetType,
    val passed: Boolean,
)

data class SentenceWritingResult(
    val sentenceId: String,
    val wholeSentencePassed: Boolean,
    val targetResults: List<WritingTargetResult>,
)

sealed interface CompleteWritingSaveResult {
    data object Saved : CompleteWritingSaveResult
    data object Failed : CompleteWritingSaveResult
}

fun interface CompleteWritingRepository {
    suspend fun loadFinalBatch(chapterId: String): FinalWritingBatchResult? = null

    suspend fun saveFinalBatch(
        chapterId: String,
        result: FinalWritingBatchResult,
    ): CompleteWritingSaveResult
}

sealed interface FinalWritingBatchResult {
    data class Valid(val sentenceResults: List<SentenceWritingResult>) : FinalWritingBatchResult
    data object SupportedLearningOnly : FinalWritingBatchResult
}

class CompleteWritingEvaluator(
    private val comparator: FixedWritingComparator,
) {
    fun evaluate(
        sentences: List<FixedLearningSentence>,
        inputs: Map<String, String>,
        targets: List<LockedWritingTarget>,
        helpUsed: Boolean,
    ): FinalWritingBatchResult {
        if (helpUsed) return FinalWritingBatchResult.SupportedLearningOnly
        return FinalWritingBatchResult.Valid(
            sentences.map { sentence ->
                val actual = inputs.getValue(sentence.sentenceId)
                SentenceWritingResult(
                    sentenceId = sentence.sentenceId,
                    wholeSentencePassed = comparator.compare(
                        sentence.textEn,
                        actual,
                    ) == FixedWritingResult.Correct,
                    targetResults = targets
                        .filter { it.sentenceId == sentence.sentenceId }
                        .map { target ->
                            WritingTargetResult(
                                targetId = target.targetId,
                                type = target.type,
                                passed = when (comparator.matchTarget(sentence.textEn, actual, target.expectedForms, target.sourceSpan)) {
                                    WritingTargetMatch.CORRECT -> true
                                    WritingTargetMatch.INCORRECT -> false
                                    WritingTargetMatch.UNRESOLVED -> throw WritingTargetEvidenceUnavailable()
                                },
                            )
                        },
                )
            },
        )
    }

}
