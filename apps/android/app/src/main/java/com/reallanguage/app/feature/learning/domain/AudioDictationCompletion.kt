package com.reallanguage.app.feature.learning.domain

sealed interface AudioDictationCompletionResult {
    data object Saved : AudioDictationCompletionResult
    data object Failed : AudioDictationCompletionResult
}

fun interface AudioDictationCompletionRepository {
    suspend fun complete(sentenceId: String): AudioDictationCompletionResult
}
