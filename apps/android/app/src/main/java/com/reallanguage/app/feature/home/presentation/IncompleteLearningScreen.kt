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
import androidx.compose.material.icons.outlined.PhotoLibrary
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
import com.reallanguage.app.core.designsystem.EditorialFont
import com.reallanguage.app.core.designsystem.editorialPaper
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
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.entries.forEach { entry -> IncompleteLearningRow(entry, onAction) }
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
    val forming = entry.kind == ResumeLearningKind.FORMATION
    val currentStep = entry.savedStepId.takeIf { !forming && it.matches(Regex("LR-0[1-9]")) }
        ?.substringAfterLast('-')?.toIntOrNull()
    val chapter = if (!forming && entry.chapterNumber != null) " · 第 ${entry.chapterNumber} 章" else ""
    val position = when {
        forming -> "${entry.savedStepLabelZh} · 尚未确认"
        currentStep != null -> "${entry.savedStepLabelZh} · 第 $currentStep / 9 步"
        else -> "${entry.savedStepLabelZh} · 进度待确认"
    }
    BoxWithConstraints(Modifier.fillMaxWidth().editorialPaper().padding(14.dp).testTag("incomplete-learning-card")) {
        @Composable fun Details(modifier: Modifier) {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.sourceType.label() + chapter, color = RlColor.TextSecondary,
                    fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 18.sp)
                Text(entry.sourceLabelZh, Modifier.fillMaxWidth(), color = RlColor.Ink,
                    fontFamily = EditorialFont.Serif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp)
                Text(position, Modifier.fillMaxWidth(), color = RlColor.TextSecondary,
                    fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 18.sp)
                if (currentStep != null) LinearProgressIndicator(progress = { currentStep / 9f },
                    modifier = Modifier.fillMaxWidth().height(3.dp).testTag("incomplete-learning-progress"), color = RlColor.Secondary,
                    trackColor = RlColor.Border.copy(alpha = .55f))
            }
        }
        if (maxWidth >= 350.dp && LocalDensity.current.fontScale <= 1.35f) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Details(Modifier.weight(1f))
                IncompleteResumeButton(entry, RlColor.Secondary, onAction, Modifier.width(88.dp))
            }
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Details(Modifier.fillMaxWidth())
            IncompleteResumeButton(entry, RlColor.Secondary, onAction, Modifier.width(104.dp).align(Alignment.End))
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
private fun IncompleteChapterSummary(entry: IncompleteLearningEntry, forming: Boolean, currentStep: Int?, accent: Color, modifier: Modifier, showLabel: Boolean = true) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showLabel) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
        Text("继续", Modifier.fillMaxWidth(), fontFamily = EditorialFont.Serif, fontSize = 16.sp, lineHeight = 22.sp,
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
    OrdinarySourceType.SCREENSHOT -> IncompleteVisual(RlColor.NoteAccent, Icons.Outlined.PhotoLibrary)
    OrdinarySourceType.AUDIO_IMPORT -> IncompleteVisual(RlColor.Secondary, Icons.Outlined.GraphicEq)
}

@Composable
private fun OrdinarySourceType.label(): String = stringResource(when (this) {
    OrdinarySourceType.DIARY -> R.string.today_review_source_diary
    OrdinarySourceType.QUICK_RECORDING -> R.string.today_review_source_recording
    OrdinarySourceType.AI_SCENE -> R.string.today_review_source_ai_scene
    OrdinarySourceType.SCREENSHOT -> R.string.incomplete_source_screenshot
    OrdinarySourceType.AUDIO_IMPORT -> R.string.incomplete_source_audio_import
})
