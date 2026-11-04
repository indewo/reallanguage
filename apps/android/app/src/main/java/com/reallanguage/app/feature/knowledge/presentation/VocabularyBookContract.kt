package com.reallanguage.app.feature.knowledge.presentation

import com.reallanguage.app.feature.knowledge.domain.VocabularyEntry
import com.reallanguage.app.feature.knowledge.domain.VocabularySenseRecord

data class VocabularyBookUiState(
    val currentScope: String = "",
    val entries: List<VocabularyEntry> = emptyList(),
    val expandedSenseId: String? = null,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val pronunciationBusy: Boolean = false,
    val pronunciationFailed: Boolean = false,
    val query: String = "",
    val appliedQuery: String = "",
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val selectedInitial: Char = 'A',
    val longestHeadword: String = "",
)
{
    val expandedSense: VocabularySenseRecord?
        get() = entries.asSequence().flatMap { it.senses.asSequence() }
            .firstOrNull { it.senseId == expandedSenseId }
}

sealed interface VocabularyBookAction {
    data class QueryChanged(val value: String) : VocabularyBookAction
    data object SearchRequested : VocabularyBookAction
    data object ClearSearchRequested : VocabularyBookAction
    data object LoadMoreRequested : VocabularyBookAction
    data class InitialSelected(val initial: Char) : VocabularyBookAction
    data class SenseSelected(val senseId: String) : VocabularyBookAction
    data object CollapseRequested : VocabularyBookAction
    data object RetryRequested : VocabularyBookAction
    data object ReturnHomeRequested : VocabularyBookAction
    data object PronunciationRequested : VocabularyBookAction
    data class SensePronunciationRequested(val senseId: String) : VocabularyBookAction
    data class ExamplePronunciationRequested(val senseId: String, val exampleIndex: Int) : VocabularyBookAction
    data class PronunciationFinished(val requestId: Long, val succeeded: Boolean) : VocabularyBookAction
    data object PronunciationInterrupted : VocabularyBookAction
    data object RetryPronunciationRequested : VocabularyBookAction
}

sealed interface VocabularyBookEffect {
    data object ReturnHome : VocabularyBookEffect
    data class PlayPronunciation(val text: String, val requestId: Long = 0) : VocabularyBookEffect
    data object StopPronunciation : VocabularyBookEffect
}
