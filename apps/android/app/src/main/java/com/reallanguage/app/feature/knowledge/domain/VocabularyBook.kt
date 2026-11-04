package com.reallanguage.app.feature.knowledge.domain

enum class KnowledgeStatus { NOT_STARTED, LEARNING, TENTATIVE, MASTERED }

data class VocabularyExample(
    val textEn: String,
    val textZh: String,
    val pronunciationText: String,
)

data class VocabularySenseRecord(
    val senseId: String,
    val englishForm: String,
    val meaningZh: String,
    val status: KnowledgeStatus,
    val effectiveLearnedCount: Int,
    val learningScopes: Set<String>,
    val enteredOrder: Long,
    val phonetic: String? = null,
    val pronunciationText: String? = null,
    val representativeScene: String? = null,
    val examples: List<VocabularyExample> = emptyList(),
    val focusOccurrenceCount: Int = 0,
    val contextOccurrenceCount: Int = 0,
    /** Stable cross-pool identity from the embedded catalog; local learning rows keep their own item id. */
    val catalogSenseId: String? = null,
    val partOfSpeech: String = "",
)

data class VocabularyEntry(
    val englishForm: String,
    val senses: List<VocabularySenseRecord>,
)

sealed interface VocabularyLoadResult {
    data class Ready(
        val currentScopes: Set<String>,
        val records: List<VocabularySenseRecord>,
        val hasMore: Boolean = false,
        val longestHeadword: String = "",
    ) : VocabularyLoadResult
    data object Failed : VocabularyLoadResult
}

interface VocabularyBookRepository {
    suspend fun loadAllRecords(): VocabularyLoadResult

    suspend fun loadPage(query: String, limit: Int, offset: Int): VocabularyLoadResult =
        loadAllRecords()

    suspend fun loadFromInitial(initial: Char, limit: Int, offset: Int): VocabularyLoadResult =
        loadPage(initial.toString(), limit, offset)
}
