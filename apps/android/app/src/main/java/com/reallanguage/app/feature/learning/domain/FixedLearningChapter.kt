package com.reallanguage.app.feature.learning.domain

data class FixedLearningSentence(
    val sentenceId: String,
    val textEn: String,
    val textZh: String,
    val isFocus: Boolean,
)

/**
 * AI output and mobile keyboards may insert typographic apostrophes with an
 * extra space on either side (for example `I’ m`). Keep the persisted wording
 * intact, but always render English contractions with standard punctuation.
 */
fun String.normalizedEnglishTypography(): String =
    replace(Regex("[\u2018\u2019\u02BC\uFF07]"), "'")
        .replace(
            Regex("(?i)([\\p{L}\\p{M}])\\s*'\\s*(s|m|re|ve|ll|d|t)\\b"),
            "$1'$2",
        )

data class FixedLearningChapter(
    val chapterId: String,
    val chapterNumber: Int,
    val sentences: List<FixedLearningSentence>,
)

sealed interface FixedChapterLoadResult {
    data class Loaded(val chapter: FixedLearningChapter) : FixedChapterLoadResult
    data object Failed : FixedChapterLoadResult
}
fun interface FixedChapterRepository {
    suspend fun load(chapterId: String): FixedChapterLoadResult
}
