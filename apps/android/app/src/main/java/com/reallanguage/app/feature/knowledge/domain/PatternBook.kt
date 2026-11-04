package com.reallanguage.app.feature.knowledge.domain

data class PatternExample(val textEn: String, val textZh: String, val pronunciationText: String)

/** Read-only contextual explanation; not an additional vocabulary learning state. */
data class PatternRelatedVocabulary(val display: String, val senseZh: String)

data class PatternRecord(
    val patternId: String,
    val structureEn: String,
    val functionZh: String,
    val writingStatus: KnowledgeStatus,
    val speakingStatus: KnowledgeStatus,
    val consecutiveSuccessCount: Int,
    val occurrenceCount: Int,
    val representativeScene: String? = null,
    val examples: List<PatternExample> = emptyList(),
    val writingConsecutiveSuccessCount: Int = consecutiveSuccessCount,
    val speakingConsecutiveSuccessCount: Int = consecutiveSuccessCount,
    val naturalRevalidation: Boolean = true,
    val structureParts: List<String> = emptyList(),
    val relatedVocabulary: List<PatternRelatedVocabulary>? = null,
)

sealed interface PatternLoadResult {
    data class Ready(val patterns: List<PatternRecord>) : PatternLoadResult
    data object Failed : PatternLoadResult
}

interface PatternBookRepository { suspend fun loadAllPatterns(): PatternLoadResult }
