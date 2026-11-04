package com.reallanguage.app.feature.knowledge.domain

sealed interface PatternRemovalResult {
    data object Removed : PatternRemovalResult
    data object AlreadyRemoved : PatternRemovalResult
    data object Failed : PatternRemovalResult
}

interface PatternRemovalRepository {
    suspend fun removeFromPatternBook(patternId: String, commandId: String): PatternRemovalResult
}
