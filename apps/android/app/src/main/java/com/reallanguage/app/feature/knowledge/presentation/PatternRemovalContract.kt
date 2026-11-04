package com.reallanguage.app.feature.knowledge.presentation

data class PatternRemovalUiState(
    val patternId: String,
    val commandId: String,
    val removing: Boolean = false,
    val removeFailed: Boolean = false,
)
sealed interface PatternRemovalAction {
    data object CancelRequested : PatternRemovalAction
    data object ConfirmRequested : PatternRemovalAction
}
sealed interface PatternRemovalEffect {
    data object ReturnToDetail : PatternRemovalEffect
    data class PatternRemoved(val patternId: String) : PatternRemovalEffect
}
