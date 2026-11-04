package com.reallanguage.app.core.vocabulary

import java.text.Normalizer
import java.util.Locale

enum class VocabularyPool(val displayNameZh: String) {
    JUNIOR_HIGH("初中"),
    SENIOR_HIGH("高中"),
    CET_4("四级"),
    CET_6("六级"),
    POSTGRADUATE("考研"),
    TOEFL("托福"),
    IELTS("雅思"),
    GRE("GRE"),
}

enum class VocabularyMatchKind { EXACT, INFLECTION }

data class VocabularyCatalogSense(
    val senseId: String,
    val wordId: String,
    val display: String,
    val normalizedWord: String,
    val initial: String,
    val itemType: String,
    val partOfSpeech: String,
    val meaningZh: String,
    val phonetic: String?,
    val pools: Set<VocabularyPool>,
    val matchKind: VocabularyMatchKind,
)

data class VocabularyCatalogMetadata(
    val schemaVersion: Int,
    val dataVersion: String,
    val wordCount: Int,
    val senseCount: Int,
)

interface VocabularyCatalog {
    suspend fun find(
        surfaceForm: String,
        pool: VocabularyPool? = null,
    ): List<VocabularyCatalogSense>

    suspend fun browse(
        pool: VocabularyPool,
        initial: Char,
        limit: Int = 100,
        offset: Int = 0,
    ): List<VocabularyCatalogSense>

    /**
     * Reads one alphabetical page of words from one pool. Pagination is by word,
     * not by sense, so all meanings of a word always stay on the same page.
     * A non-empty query performs an indexed, normalized prefix lookup.
     */
    suspend fun browsePage(
        pool: VocabularyPool,
        query: String = "",
        limit: Int = 200,
        offset: Int = 0,
    ): List<VocabularyCatalogSense>

    /**
     * Reads one word-paged slice beginning at [initial] and continuing through
     * later initials. This backs the A-Z rail without scanning earlier words.
     */
    suspend fun browsePageFromInitial(
        pool: VocabularyPool,
        initial: Char,
        limit: Int = 200,
        offset: Int = 0,
    ): List<VocabularyCatalogSense> = browsePage(pool, initial.toString(), limit, offset)

    /** Pool-wide sizing reference; implementations should answer from offline indexed data. */
    suspend fun longestDisplay(pool: VocabularyPool): String = ""

    suspend fun metadata(): VocabularyCatalogMetadata
}

fun normalizeVocabularyForm(value: String): String = Normalizer
    .normalize(value, Normalizer.Form.NFKC)
    .replace('’', '\'')
    .replace('‘', '\'')
    .lowercase(Locale.ROOT)
    .replace(Regex("\\s+"), " ")
    .trim(' ', '.', ';', ',', '，', '；')

internal fun normalizeVocabularyMeaning(value: String): String = Normalizer
    .normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)
    .replace(Regex("[\\s,，;；、。.!！?？:：()（）\\[\\]【】<>《》\\-—_/&]"), "")

fun List<VocabularyCatalogSense>.bestMeaningMatch(meaningZh: String): VocabularyCatalogSense? {
    val expected = normalizeVocabularyMeaning(meaningZh)
    if (expected.isBlank()) return null
    val exact = filter { normalizeVocabularyMeaning(it.meaningZh) == expected }
    if (exact.size == 1) return exact.single()
    if (exact.size > 1) return null
    val close = filter { candidate ->
        val actual = normalizeVocabularyMeaning(candidate.meaningZh)
        val shorter = if (actual.length <= expected.length) actual else expected
        val longer = if (actual.length > expected.length) actual else expected
        shorter.length >= 2 && shorter in longer && shorter.length.toDouble() / longer.length >= 0.55
    }
    return close.singleOrNull()
}
