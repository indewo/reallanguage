package com.reallanguage.app.feature.home.presentation

import androidx.activity.compose.BackHandler
import com.reallanguage.app.core.designsystem.editorialPaper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.RlSpace
import com.reallanguage.app.feature.home.domain.AppMessage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun MessagesScreen(state: MessagesUiState, onAction: (MessagesAction) -> Unit) {
    BackHandler { onAction(MessagesAction.ReturnHomeRequested) }
    var filter by remember { mutableStateOf(MessageFilter.ALL) }
    val filtered = state.messages.filter { filter == MessageFilter.ALL || filter == MessageFilter.READ && it.read || filter == MessageFilter.UNREAD && !it.read }
    RlPaperBackground(showCornerCollage = false) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            com.reallanguage.app.core.designsystem.EditorialPageHeader("消息", { onAction(MessagesAction.ReturnHomeRequested) })
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(.55f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MessageFilter.entries.forEach { item ->
                        val count = state.messages.count { item == MessageFilter.ALL || item == MessageFilter.READ && it.read || item == MessageFilter.UNREAD && !it.read }
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).editorialPaper(if (filter == item) Color(0xFF6B7044) else Color(0xFFF1E9DC)).clickable { filter = item }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(item.label, Modifier.weight(1f), fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 16.sp, color = if (filter == item) Color.White else RlColor.Ink)
                            Surface(color = RlColor.ActionVivid, shape = CircleShape, modifier = Modifier.size(24.dp)) {
                                Box(contentAlignment = Alignment.Center) { Text(if (state.loadFailed || state.loading && state.messages.isEmpty()) "—" else count.toString(), color = Color.White, fontSize = 13.sp) }
                            }
                        }
                    }
                }
                Column(Modifier.weight(2f).fillMaxHeight().editorialPaper().verticalScroll(rememberScrollState()).padding(16.dp).testTag("messages-scroll-list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.markAllReadFailed) Text(stringResource(R.string.messages_mark_read_failed), Modifier.testTag("messages-mark-read-failed"), color = RlColor.Error)
                    if (state.loadFailed) {
                        Text(stringResource(R.string.messages_count_unconfirmed))
                        OutlinedButton({ onAction(MessagesAction.RetryRequested) }) { Text(stringResource(R.string.diary_retry)) }
                    }
                    if (state.loading) Text(stringResource(R.string.messages_loading))
                    else if (filtered.isEmpty() && !state.loadFailed) Text(stringResource(R.string.messages_empty), color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 14.sp, lineHeight = 21.sp)
                    filtered.forEach { EditorialMessageRow(it, state.unavailableMessageId == it.messageId, onAction) }
                }
            }
            OutlinedButton({ onAction(MessagesAction.MarkAllReadRequested) }, enabled = !state.loading && !state.markingAllRead,
                shape = RlShape.Small, border = BorderStroke(.6.dp, RlColor.Border),
                modifier = Modifier.align(Alignment.End).heightIn(min = 36.dp).testTag("messages-mark-all-read")) { Text("全部标为已读", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 14.sp, fontWeight = FontWeight.Normal) }
            androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
        }
    }
}
@Composable
private fun EditorialMessageRow(message: AppMessage, unavailable: Boolean, onAction: (MessagesAction) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("message-row"), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Surface(color = if (message.read) Color.Transparent else RlColor.ActionVivid, shape = CircleShape, modifier = Modifier.padding(top = 8.dp).size(6.dp)) {}
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(message.presentationType().statusLabelZh, color = message.visual().color, fontSize = 12.sp, lineHeight = 18.sp)
            Text(message.summaryZh, color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
            message.relatedContentLabelZh?.let { Text(it, color = RlColor.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp) }
            if (unavailable) MessageUnavailableLabel()
        }
        Text(message.formattedTime(), color = RlColor.TextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        MessageOpenButton(message, onAction)
    }
    androidx.compose.material3.HorizontalDivider(color = RlColor.Border.copy(alpha = .6f))
}
@Composable
private fun MessageOpenButton(message: AppMessage, onAction: (MessagesAction) -> Unit) {
    IconButton(onClick = { onAction(MessagesAction.MessageRequested(message.messageId)) },
        modifier = Modifier.testTag("messages-open-${message.messageId}")) {
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = RlColor.Ink)
    }
}

@Composable
private fun MessageUnavailableLabel() {
    Text(stringResource(R.string.messages_target_unavailable), Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
}

private data class MessageVisual(val color: Color, val icon: ImageVector, val status: String, val seal: Int? = null)

@Composable
private fun AppMessage.visual(): MessageVisual = when (val presentation = presentationType()) {
    MessagePresentationType.REVIEW_APPROVED -> MessageVisual(RlColor.ActionVivid, Icons.Outlined.CheckCircle, presentation.statusLabelZh, R.drawable.rl_v73_message_approved)
    MessagePresentationType.REVIEW_NEEDS_EDIT -> MessageVisual(RlColor.NoteAccent, Icons.Outlined.WarningAmber, presentation.statusLabelZh, R.drawable.rl_v73_message_warning)
    MessagePresentationType.LEARNING_READY -> MessageVisual(RlColor.Secondary, Icons.AutoMirrored.Outlined.MenuBook, presentation.statusLabelZh, R.drawable.rl_v73_message_learning)
    MessagePresentationType.REVIEW_READY -> MessageVisual(RlColor.BorderStrong, Icons.Outlined.CalendarMonth, presentation.statusLabelZh, R.drawable.rl_v73_message_review)
    MessagePresentationType.VIEW -> MessageVisual(RlColor.TextSecondary, Icons.Outlined.Info, presentation.statusLabelZh)
}

private fun AppMessage.formattedTime(): String {
    val zone = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(createdAtEpochMillis).atZone(zone)
    val today = LocalDate.now(zone)
    val prefix = when (dateTime.toLocalDate()) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> dateTime.format(DateTimeFormatter.ofPattern("M月d日"))
    }
    return "$prefix ${dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))}"
}

private enum class MessageFilter(val label: String) {
    ALL("全部消息"),
    UNREAD("未读消息"),
    READ("已读消息"),
}
