package com.reallanguage.app.feature.home.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
    val filtered = state.messages.filter {
        filter == MessageFilter.ALL || filter == MessageFilter.READ && it.read || filter == MessageFilter.UNREAD && !it.read
    }
    RlPaperBackground {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val density = LocalDensity.current
            val availableWidth = maxWidth
            val availableHeight = maxHeight
            if (availableWidth < 700.dp || availableHeight < 340.dp || density.fontScale > 1.35f) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ onAction(MessagesAction.ReturnHomeRequested) }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = RlColor.Ink) }
                        Text(stringResource(R.string.messages_title), Modifier.weight(1f), color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 23.sp, lineHeight = 31.sp)
                        MessageFilterMenu(filter, { filter = it })
                    }
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).testTag("messages-scroll-list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.markAllReadFailed) Text(stringResource(R.string.messages_mark_read_failed), color = RlColor.Error, modifier = Modifier.testTag("messages-mark-read-failed"))
                        if (state.loadFailed) {
                            Text(stringResource(R.string.messages_count_unconfirmed), color = RlColor.TextSecondary, modifier = Modifier.testTag("messages-count-unconfirmed"))
                            OutlinedButton({ onAction(MessagesAction.RetryRequested) }) { Text(stringResource(R.string.diary_retry)) }
                        }
                        if (state.loading) Text(stringResource(R.string.messages_loading))
                        else if (filtered.isEmpty() && !state.loadFailed) Text(stringResource(R.string.messages_empty))
                        filtered.forEach { MessageRow(it, state.unavailableMessageId == it.messageId, true, 8.dp, onAction) }
                    }
                    MessagesFooter(state, onAction, availableWidth, true)
                }
                return@BoxWithConstraints
            }
            // A shorter wide viewport still has enough horizontal room for the
            // reference row. Stacking its metadata would consume *more* height.
            val compact = availableWidth < 840.dp || density.fontScale > 1.15f
            var headerHeight by remember { mutableStateOf(0.dp) }
            val filterWidth = minOf(if (compact) 180.dp else 148.dp, availableWidth * .3f)
            Box(Modifier.fillMaxWidth().padding(horizontal = RlSpace.Space24, vertical = RlSpace.Space8)
                .onSizeChanged { headerHeight = with(density) { it.height.toDp() } }) {
                IconButton(
                    onClick = { onAction(MessagesAction.ReturnHomeRequested) },
                    modifier = Modifier.align(Alignment.CenterStart),
                ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = RlColor.Ink) }
                Column(Modifier.align(Alignment.Center).padding(horizontal = filterWidth)
                    .fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.messages_title),
                        color = RlColor.Ink,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 27.sp, lineHeight = 36.sp,
                        ),
                    )
                    Text("学习进度与审核通知", Modifier.fillMaxWidth(), color = RlColor.TextSecondary,
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
                MessageFilterMenu(filter = filter, onSelected = { filter = it },
                    modifier = Modifier.align(Alignment.CenterEnd).width(filterWidth))
            }
            // The source-image regions are measured against the safe canvas, not
            // hardcoded phone pixels. Extra font/keyboard height expands the header
            // and leaves one controlled scrolling list above the persistent actions.
            val listLeft = availableWidth * (670f / 1831f)
            val listRight = availableWidth - availableWidth * (60f / 1831f)
            val listTop = maxOf(availableHeight * (163f / 859f), headerHeight + 8.dp)
            val contentBottom = availableHeight - 16.dp
            // Preserve the 64dp reference row at the no-cutout size. If system
            // insets reduce the height, spend less vertical whitespace while
            // keeping the same text and the 48dp open-action hit area. Content
            // may still grow naturally; long messages stay in the local list.
            val referenceRowHeight = ((contentBottom - listTop - 60.dp - 15.dp) / 4f)
                .coerceIn(48.dp, 64.dp)
            val rowVerticalPadding = (referenceRowHeight - 48.dp) / 2f
            val artworkTop = if (compact) listTop else availableHeight * (94f / 859f)
            val unreadCount = if (state.messages.isNotEmpty() || !state.loading && !state.loadFailed)
                state.messages.count { !it.read } else null
            MessagesArtwork(
                unreadCount = unreadCount,
                loading = state.loading,
                modifier = Modifier.offset(x = availableWidth * (16f / 1831f), y = artworkTop)
                    .width(availableWidth * (630f / 1831f))
                    .height((availableHeight - 36.dp - artworkTop).coerceAtLeast(1.dp)),
            )
            Column(
                modifier = Modifier.offset(x = listLeft, y = listTop).width((listRight - listLeft).coerceAtLeast(1.dp))
                    .height((contentBottom - listTop).coerceAtLeast(1.dp)),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                            .testTag("messages-scroll-list"),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (state.markAllReadFailed) Text(
                            stringResource(R.string.messages_mark_read_failed),
                            color = RlColor.Error,
                            modifier = Modifier.testTag("messages-mark-read-failed"),
                        )
                        if (state.loadFailed) {
                            Text(stringResource(R.string.messages_count_unconfirmed), color = RlColor.TextSecondary,
                                modifier = Modifier.fillMaxWidth().testTag("messages-count-unconfirmed"))
                            OutlinedButton(onClick = { onAction(MessagesAction.RetryRequested) }) {
                                Text(stringResource(R.string.diary_retry))
                            }
                        } else if (state.loading && state.messages.isNotEmpty()) {
                            Text(stringResource(R.string.messages_loading))
                        }
                        when {
                            state.messages.isEmpty() && (state.loading || state.loadFailed) -> Unit
                            filtered.isEmpty() -> Text(stringResource(R.string.messages_empty))
                            else -> filtered.forEach { message ->
                                MessageRow(message, state.unavailableMessageId == message.messageId, compact, rowVerticalPadding, onAction)
                            }
                        }
                    }
                    MessagesFooter(state, onAction, availableWidth, compact)
            }
        }
    }
}

@Composable
private fun MessagesFooter(state: MessagesUiState, onAction: (MessagesAction) -> Unit, canvasWidth: Dp, compact: Boolean) {
    Row(Modifier.fillMaxWidth().padding(start = if (compact) 0.dp else canvasWidth * (30f / 1831f)),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else canvasWidth * (40f / 1831f)),
        verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { onAction(MessagesAction.MarkAllReadRequested) },
            enabled = !state.loading && !state.markingAllRead,
            modifier = (if (compact) Modifier.weight(1f) else Modifier.width(canvasWidth * (414f / 1831f)))
                .heightIn(min = 48.dp).messagePaper().messageInsetOutline(RlColor.Border)
                .testTag("messages-mark-all-read"),
            shape = RlShape.Small,
            border = BorderStroke(1.dp, RlColor.Border),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RlColor.Ink),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Outlined.CheckCircle, null,
                Modifier.size(24.dp).testTag("messages-read-check-circle-icon"))
            Text("  全部标为已读", Modifier.weight(1f), textAlign = TextAlign.Center)
        }
        Button(
            onClick = { onAction(MessagesAction.ReturnHomeRequested) },
            modifier = (if (compact) Modifier.weight(1f) else Modifier.width(canvasWidth * (373f / 1831f)))
                .heightIn(min = 48.dp).messageInsetOutline(RlColor.PaperWarm)
                .testTag("messages-return-home"),
            shape = RlShape.Input,
            colors = ButtonDefaults.buttonColors(containerColor = RlColor.Primary, contentColor = RlColor.OnPrimary),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Outlined.Home, null, Modifier.size(24.dp).testTag("messages-home-icon"))
            Text("返回首页", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun MessageFilterMenu(filter: MessageFilter, onSelected: (MessageFilter) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag("messages-filter"),
            shape = RlShape.Small,
            border = BorderStroke(1.dp, RlColor.Border),
        ) {
            Icon(Icons.Outlined.FilterList, null, modifier = Modifier.size(18.dp))
            Text("  ${filter.label}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MessageFilter.entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    onClick = {
                        onSelected(item)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun MessageRow(message: AppMessage, unavailable: Boolean, compact: Boolean, verticalPadding: Dp, onAction: (MessagesAction) -> Unit) {
    val visual = message.visual()
    Box(
        modifier = Modifier.fillMaxWidth().messagePaper(bottomPaintOutset = 4.dp).testTag("message-row"),
    ) {
        if (compact) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    MessageKindIcon(visual)
                    Text(message.summaryZh, Modifier.weight(1f), color = RlColor.Ink,
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold))
                }
                message.relatedContentLabelZh?.let {
                    Text(it, Modifier.fillMaxWidth(), color = RlColor.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                if (unavailable) MessageUnavailableLabel()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message.formattedTime(), Modifier.weight(1f), color = RlColor.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    MessageStatus(visual)
                    MessageOpenButton(message, onAction)
                }
            }
        } else Row(
            modifier = Modifier.heightIn(min = 48.dp + verticalPadding * 2f)
                .padding(horizontal = RlSpace.Space12, vertical = verticalPadding),
            horizontalArrangement = Arrangement.spacedBy(RlSpace.Space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MessageKindIcon(visual)
            Column(Modifier.weight(1f)) {
                Text(
                    message.summaryZh,
                    Modifier.fillMaxWidth(),
                    color = RlColor.Ink,
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold),
                )
                message.relatedContentLabelZh?.let {
                    Text(it, Modifier.fillMaxWidth(), color = RlColor.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                if (unavailable) MessageUnavailableLabel()
            }
            Text(message.formattedTime(), Modifier.width(78.dp).drawBehind {
                // Use the existing inter-column gap without moving either text
                // column or consuming the action's touch target.
                val x = -6.dp.toPx()
                val halfHeight = 19.dp.toPx()
                drawLine(RlColor.Border, Offset(x, size.height / 2 - halfHeight),
                    Offset(x, size.height / 2 + halfHeight), strokeWidth = .75.dp.toPx())
            }, color = RlColor.TextSecondary, style = MaterialTheme.typography.bodySmall)
            MessageStatus(visual)
            MessageOpenButton(message, onAction)
        }
    }
}

@Composable
private fun Modifier.messagePaper(bottomPaintOutset: Dp = 0.dp): Modifier {
    val paper = ImageBitmap.imageResource(R.drawable.rl_torn_heading_label)
    return drawBehind {
        // Display the blank independent asset's paper and its existing shadow,
        // excluding only outer transparent padding. The source file is untouched.
        // Its tear proportions are not an exact match for the reference row.
        // The source's lower shadow/transparent padding must not consume the
        // text's paper area on short rows. Paint into the existing 5dp row gap;
        // preserve the row hit box, its text layout, and the full torn edge.
        drawImage(paper, srcOffset = IntOffset(64, 128), srcSize = IntSize(1936, 484),
            dstSize = IntSize(size.width.roundToInt(), (size.height + bottomPaintOutset.toPx()).roundToInt()),
            filterQuality = FilterQuality.Medium)
    }
}

private fun Modifier.messageInsetOutline(color: Color): Modifier = drawWithContent {
    drawContent()
    val inset = 3.dp.toPx()
    drawRoundRect(color, topLeft = Offset(inset, inset),
        size = Size((size.width - 2 * inset).coerceAtLeast(0f), (size.height - 2 * inset).coerceAtLeast(0f)),
        cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(.75.dp.toPx()))
}

@Composable
private fun MessageKindIcon(visual: MessageVisual) {
    if (visual.seal != null) {
        Image(painterResource(visual.seal), null, Modifier.size(42.dp))
        return
    }
    Surface(shape = CircleShape, color = visual.color.copy(alpha = .18f), modifier = Modifier.size(42.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(visual.icon, null, tint = visual.color, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun MessageStatus(visual: MessageVisual) {
    Surface(shape = CircleShape, color = visual.color.copy(alpha = .86f)) {
        Text(visual.status, Modifier.padding(horizontal = RlSpace.Space12, vertical = RlSpace.Space4),
            color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
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
