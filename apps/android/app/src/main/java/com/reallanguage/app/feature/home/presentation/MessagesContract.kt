package com.reallanguage.app.feature.home.presentation

import com.reallanguage.app.feature.home.domain.AppMessage
import com.reallanguage.app.feature.home.domain.AppMessageTarget

data class MessagesUiState(
    val messages: List<AppMessage> = emptyList(),
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val unavailableMessageId: String? = null,
    val markingAllRead: Boolean = false,
    val markAllReadFailed: Boolean = false,
)
sealed interface MessagesAction {
    data class MessageRequested(val messageId: String) : MessagesAction
    data object MarkAllReadRequested : MessagesAction
    data object RetryRequested : MessagesAction
    data object ReturnHomeRequested : MessagesAction
}
sealed interface MessagesEffect {
    data class OpenTarget(val target: AppMessageTarget) : MessagesEffect
    data class OpenLoginForTarget(val target: AppMessageTarget) : MessagesEffect
    data object OpenHome : MessagesEffect
}
