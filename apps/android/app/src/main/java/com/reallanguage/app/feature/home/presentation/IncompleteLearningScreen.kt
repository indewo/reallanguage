package com.reallanguage.app.feature.home.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlSecondaryButton
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.RlSpace
import com.reallanguage.app.feature.home.domain.IncompleteLearningEntry
import com.reallanguage.app.feature.home.domain.OrdinarySourceType
import com.reallanguage.app.feature.home.domain.ResumeLearningKind

@Composable
fun IncompleteLearningScreen(state: IncompleteLearningUiState, onAction: (IncompleteLearningAction) -> Unit) {
    BackHandler { onAction(IncompleteLearningAction.ReturnHomeRequested) }
    IncompleteLearningFrame(count = if (state.loading || state.loadFailed) null else state.entries.size,
        onBack = { onAction(IncompleteLearningAction.ReturnHomeRequested) }) {
        when {
            state.loading -> IncompleteLearningStatus(
                title = stringResource(R.string.incomplete_learning_loading),
                description = "读取完成后会显示可以继续的学习内容。",
                tag = "incomplete-learning-loading",
                modifier = Modifier.weight(1f),
                loading = true,
            )
            state.loadFailed -> IncompleteLearningStatus(
                title = "学习进度暂时无法读取",
                description = "请重试读取；如果仍未成功，可以返回首页后再进入。",
                tag = "incomplete-learning-load-failed",
                modifier = Modifier.weight(1f),
                onRetry = { onAction(IncompleteLearningAction.RetryRequested) },
            )
            state.entries.isEmpty() -> IncompleteLearningStatus(
                title = "暂无未完成学习",
                description = stringResource(R.string.incomplete_learning_empty),
                tag = "incomplete-learning-empty",
                modifier = Modifier.weight(1f),
            )
            else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("incomplete-learning-list"),
                verticalArrangement = Arrangement.spacedBy(RlSpace.Space8)) {
                state.entries.forEach { entry -> IncompleteLearningRow(entry, onAction) }
            }
        }
        TextButton(onClick = { onAction(IncompleteLearningAction.ReturnHomeRequested) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("incomplete-learning-return-home")) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.incomplete_learning_return), color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 17.sp, lineHeight = 24.sp)
                Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null, Modifier.width(61.dp).height(4.dp), contentScale = ContentScale.FillBounds)
            }
        }
    }
}

@Composable
private fun IncompleteLearningStatus(
    title: String,
    description: String,
    tag: String,
    modifier: Modifier,
    loading: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    // The status card keeps its natural text height; only the page's content region scrolls.
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag(tag),
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(color = RlColor.PaperWarm.copy(alpha = .7f), shape = RlShape.Small,
            border = BorderStroke(1.dp, RlColor.Border)) {
            Column(Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (loading) CircularProgressIndicator(Modifier.size(28.dp), color = RlColor.Primary)
                Text(title, Modifier.fillMaxWidth(), color = RlColor.Ink,
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 28.sp,
                    textAlign = TextAlign.Center)
                Text(description, Modifier.fillMaxWidth(), color = RlColor.TextSecondary,
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = 14.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
                if (onRetry != null) OutlinedButton(onClick = onRetry,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("incomplete-learning-retry")) {
                    Text(stringResource(R.string.diary_retry), fontSize = 16.sp, lineHeight = 24.sp)
                }
            }
        }
    }
}

@Composable
private fun IncompleteLearningRow(entry: IncompleteLearningEntry, onAction: (IncompleteLearningAction) -> Unit) {
    val visual = entry.sourceType.visual()
    val forming = entry.kind == ResumeLearningKind.FORMATION
    val currentStep = entry.savedStepId.takeIf { !forming && it.matches(Regex("LR-0[1-9]")) }
        ?.substringAfterLast('-')?.toIntOrNull()
    val position = when {
        forming -> "尚未固定"
        currentStep != null -> "停在 $currentStep / 9 步"
        else -> "进度待确认"
    }
    Box(
        modifier = Modifier.fillMaxWidth().testTag("incomplete-learning-card"),
    ) {
        val paper = ImageBitmap.imageResource(R.drawable.rl_v73_incomplete_paper)
        val paperPainter = remember(paper) { BitmapPainter(paper, srcOffset = IntOffset(0, (paper.height * .09f).toInt()), srcSize = IntSize(paper.width, (paper.height * .79f).toInt())) }
        Image(paperPainter, null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        BoxWithConstraints {
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val typography = MaterialTheme.typography
            fun textWidth(text: String, style: androidx.compose.ui.text.TextStyle) = with(density) {
                measurer.measure(AnnotatedString(text), style = style, softWrap = false).size.width.toDp()
            }
            // Keep the approved 100% columns. When their actual allocated widths cannot
            // hold readable text, reflow the same fields in the same reading order inside
            // their existing card; do not shrink fonts or create vertical character strips.
            val buttonWidth = maxOf(104.dp, textWidth("继续", typography.labelLarge) + 48.dp + RlSpace.Space8)
            val remaining = (maxWidth - 52.dp - buttonWidth - 24.dp).coerceAtLeast(0.dp)
            val sourceMinimum = textWidth(stringResource(R.string.today_review_source_recording),
                typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            val chapterMinimum = maxOf(textWidth("当前位置", typography.bodyMedium),
                18.dp + textWidth(if (forming) " 当前步骤" else " 章节", typography.labelSmall))
            val positionMinimum = maxOf(18.dp + textWidth(" 当前位置", typography.labelSmall),
                textWidth(position, typography.bodyMedium))
            val useOriginalColumns = remaining * (.95f / 2.8f) - 24.dp >= sourceMinimum &&
                remaining * (1.1f / 2.8f) - 16.dp >= chapterMinimum &&
                remaining * (.75f / 2.8f) - 16.dp >= positionMinimum

            Row(Modifier.heightIn(min = 94.dp).height(IntrinsicSize.Min).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(42.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    val ribbon = ImageBitmap.imageResource(R.drawable.rl_v73_incomplete_ribbon)
                    val ribbonPainter = remember(ribbon) { BitmapPainter(ribbon, srcOffset = IntOffset((ribbon.width * .17f).toInt(), (ribbon.height * .035f).toInt()), srcSize = IntSize((ribbon.width * .65f).toInt(), (ribbon.height * .93f).toInt())) }
                    Image(ribbonPainter, null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds,
                        colorFilter = if (entry.sourceType == OrdinarySourceType.DIARY) null else incompleteRibbonTint(visual.accent))
                    Icon(visual.icon, null, tint = Color.White, modifier = Modifier.width(25.dp))
                }
                if (useOriginalColumns) {
                    IncompleteSourceSummary(entry, Modifier.weight(.95f).padding(horizontal = RlSpace.Space12))
                    IncompleteChapterSummary(entry, forming, currentStep, visual.accent,
                        Modifier.weight(1.1f).padding(horizontal = RlSpace.Space8))
                    IncompletePositionSummary(position, Modifier.weight(.75f).padding(horizontal = RlSpace.Space8))
                    IncompleteResumeButton(entry, visual.accent, onAction, Modifier.width(buttonWidth).padding(end = RlSpace.Space8))
                } else {
                    Column(Modifier.weight(1f).padding(RlSpace.Space12), verticalArrangement = Arrangement.spacedBy(RlSpace.Space12)) {
                        IncompleteSourceSummary(entry, Modifier.fillMaxWidth())
                        IncompleteChapterSummary(entry, forming, currentStep, visual.accent, Modifier.fillMaxWidth())
                        IncompletePositionSummary(position, Modifier.fillMaxWidth())
                        IncompleteResumeButton(entry, visual.accent, onAction,
                            Modifier.width(buttonWidth).align(Alignment.End))
                    }
                }
            }
        }
    }
}

@Composable
private fun IncompleteSourceSummary(entry: IncompleteLearningEntry, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(entry.sourceLabelZh, Modifier.fillMaxWidth(), color = RlColor.Ink,
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp))
        Text(entry.sourceType.label(), color = RlColor.TextSecondary, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp))
    }
}

@Composable
private fun IncompleteChapterSummary(entry: IncompleteLearningEntry, forming: Boolean, currentStep: Int?, accent: Color, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Book, null, tint = RlColor.TextSecondary, modifier = Modifier.width(18.dp))
            Text(if (forming) " 当前步骤" else " 章节", Modifier.weight(1f), color = RlColor.TextSecondary,
                style = MaterialTheme.typography.labelSmall)
        }
        Text(if (forming || entry.chapterNumber == null) entry.savedStepLabelZh else "第 ${entry.chapterNumber} 章 · ${entry.savedStepLabelZh}",
            Modifier.fillMaxWidth(), color = RlColor.Ink, style = MaterialTheme.typography.bodyMedium)
        if (currentStep != null) LinearProgressIndicator(progress = { currentStep / 9f },
            modifier = Modifier.fillMaxWidth().height(4.dp).testTag("incomplete-learning-progress"), color = accent,
            trackColor = RlColor.Border.copy(alpha = .55f))
    }
}

@Composable
private fun IncompletePositionSummary(position: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.LocationOn, null, tint = RlColor.TextSecondary, modifier = Modifier.width(18.dp))
            Text(" 当前位置", Modifier.weight(1f), color = RlColor.TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
        Text(position, Modifier.fillMaxWidth(), color = RlColor.Ink, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun IncompleteResumeButton(entry: IncompleteLearningEntry, accent: Color,
    onAction: (IncompleteLearningAction) -> Unit, modifier: Modifier) {
    // Preserve the shared primary-button visuals, but give this measured label
    // its actual content width instead of a rounded wrap-content glyph width.
    Button(
        onClick = { onAction(IncompleteLearningAction.ResumeRequested(entry.learningId)) },
        modifier = modifier.testTag("incomplete-learning-resume-${entry.learningId}")
            .fillMaxWidth().defaultMinSize(minHeight = 44.dp),
        shape = RlShape.Input,
        colors = ButtonDefaults.buttonColors(
            containerColor = accent,
            contentColor = RlColor.OnPrimary,
            disabledContainerColor = RlColor.DisabledContainer,
            disabledContentColor = RlColor.TextSecondary,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
    ) {
        Text("继续", Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center)
    }
}

private data class IncompleteVisual(val accent: Color, val icon: ImageVector)

private fun incompleteRibbonTint(color: Color): ColorFilter {
    fun channel(value: Float) = floatArrayOf(.2126f * .8f * value, .7152f * .8f * value, .0722f * .8f * value, 0f, value * .6f * 255f)
    return ColorFilter.colorMatrix(ColorMatrix(channel(color.red) + channel(color.green) + channel(color.blue) + floatArrayOf(0f, 0f, 0f, 1f, 0f)))
}

@Composable
private fun OrdinarySourceType.visual(): IncompleteVisual = when (this) {
    OrdinarySourceType.DIARY -> IncompleteVisual(RlColor.ActionVivid, Icons.Outlined.CalendarMonth)
    OrdinarySourceType.QUICK_RECORDING -> IncompleteVisual(RlColor.Secondary, Icons.Outlined.GraphicEq)
    OrdinarySourceType.AI_SCENE -> IncompleteVisual(RlColor.NoteAccent, Icons.Outlined.AutoAwesome)
}

@Composable
private fun OrdinarySourceType.label(): String = stringResource(when (this) {
    OrdinarySourceType.DIARY -> R.string.today_review_source_diary
    OrdinarySourceType.QUICK_RECORDING -> R.string.today_review_source_recording
    OrdinarySourceType.AI_SCENE -> R.string.today_review_source_ai_scene
})
