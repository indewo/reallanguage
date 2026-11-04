package com.reallanguage.app.feature.learning.domain

sealed interface HandwritingCompletionResult {
    data object Saved : HandwritingCompletionResult
    data object Failed : HandwritingCompletionResult
}

fun interface HandwritingCompletionRepository {
    suspend fun complete(
        sentenceId: String,
        confirmedCopyCount: Int,
    ): HandwritingCompletionResult
}
