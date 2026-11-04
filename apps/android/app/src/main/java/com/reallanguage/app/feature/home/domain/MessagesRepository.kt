package com.reallanguage.app.feature.home.domain

enum class MessageKind { MANUAL_REVIEW_RESULT, SYSTEM }
enum class MessageTargetState { AVAILABLE, UNAVAILABLE, REQUIRES_LOGIN }
data class AppMessageTarget(val route: String, val resourceId: String)
data class AppMessage(
    val messageId: String,
    val kind: MessageKind,
    val summaryZh: String,
    val relatedContentLabelZh: String?,
    val createdAtEpochMillis: Long,
    val read: Boolean,
    val target: AppMessageTarget?,
    val targetState: MessageTargetState,
)
sealed interface MessagesLoadResult {
    data class Ready(val messages: List<AppMessage>) : MessagesLoadResult
    data object Failed : MessagesLoadResult
}
interface MessagesRepository {
    suspend fun load(): MessagesLoadResult
    suspend fun markAllRead(): Boolean
}
