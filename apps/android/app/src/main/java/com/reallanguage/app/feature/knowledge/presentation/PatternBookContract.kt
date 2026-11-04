package com.reallanguage.app.feature.knowledge.presentation

import com.reallanguage.app.feature.knowledge.domain.PatternRecord

data class PatternBookUiState(
    val patterns: List<PatternRecord> = emptyList(),
    val expandedPatternId: String? = null,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val pronunciationBusy: Boolean = false,
    val pronunciationFailed: Boolean = false,
    val query: String = "",
)
{
    val visiblePatterns: List<PatternRecord>
        get() {
            val term = query.trim()
            return if (term.isEmpty()) patterns else patterns.filter {
                it.structureEn.contains(term, ignoreCase = true) || it.functionZh.contains(term, ignoreCase = true)
            }
        }
    val expandedPattern: PatternRecord?
        get() = visiblePatterns.firstOrNull { it.patternId == expandedPatternId }
}

sealed interface PatternBookAction {
    data class QueryChanged(val query: String) : PatternBookAction
    data class PatternSelected(val patternId: String) : PatternBookAction
    data object CollapseRequested : PatternBookAction
    data object RetryRequested : PatternBookAction
    data object ReturnHomeRequested : PatternBookAction
    data object RemoveRequested : PatternBookAction
    data class ExamplePronunciationRequested(val patternId: String, val exampleIndex: Int) : PatternBookAction
    data class PronunciationFinished(val requestId: Long, val succeeded: Boolean) : PatternBookAction
    data object PronunciationInterrupted : PatternBookAction
    data object RetryPronunciationRequested : PatternBookAction
}
sealed interface PatternBookEffect {
    data object ReturnHome : PatternBookEffect
    data class OpenRemovalConfirmation(val patternId: String) : PatternBookEffect
    data class PlayExample(val text: String, val requestId: Long = 0) : PatternBookEffect
    data object StopExample : PatternBookEffect
}
