package com.reallanguage.app.feature.formation.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.*

@Composable
fun LearningTargetLoadingScreen(state: LearningTargetLoadingUiState, onAction: (LearningTargetLoadingAction) -> Unit) {
    PaperProcessingScreen(
        title = if (state.failed) "学习目标整理失败" else "正在整理学习目标",
        stages = listOf(
            ProcessPaperStage("分析重点句", Icons.Outlined.Description),
            ProcessPaperStage("提取词汇与句式", Icons.Outlined.MenuBook),
            ProcessPaperStage("整理学习目标", Icons.Outlined.GpsFixed),
        ),
        active = state.stage?.ordinal,
        busy = state.processing,
        error = when {
            state.reconciliationRequired -> "费用待核对"
            state.failed -> "未能完成整理"
            else -> null
        },
        retryLabel = if (state.reconciliationRequired) "检查恢复状态" else "重试",
        backLabel = "返回修改表达",
        onRetry = { onAction(LearningTargetLoadingAction.RetryRequested) },
        onBack = { onAction(LearningTargetLoadingAction.BackRequested) },
    )
}

internal data class ProcessPaperStage(
    val title: String,
    val icon: ImageVector,
    val working: String = "正在整理",
    val completed: String = "已完成",
    val pending: String = "待处理",
)

@Composable
internal fun PaperProcessingScreen(
    title: String,
    stages: List<ProcessPaperStage>,
    active: Int?,
    busy: Boolean,
    error: String?,
    retryLabel: String,
    backLabel: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    topic: String? = null,
    progressTag: String = "target-loading-spinner",
) {
    RlPaperBackground(modifier) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, Modifier.fillMaxWidth(), color = RlColor.Ink, fontFamily = EditorialFont.Serif,
                fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Image(editorialUnderlinePainter(), null, Modifier.align(Alignment.CenterHorizontally).width(180.dp).height(5.dp),
                contentScale = ContentScale.FillBounds)
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val compact = maxWidth < 560.dp || maxHeight < 190.dp || LocalDensity.current.fontScale > 1.35f
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!compact) ProcessingNotebookArtwork(Modifier.weight(.30f).fillMaxHeight(), topic)
                    NotebookPanel(Modifier.weight(if (compact) 1f else .70f).fillMaxHeight(),
                        padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp), trimOuterMargin = true) {
                        if (compact) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            stages.forEachIndexed { index, stage ->
                                ProcessingStageCard(stage, index, active, busy, error != null, true,
                                    Modifier.fillMaxWidth(), progressTag)
                            }
                        } else Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            stages.forEachIndexed { index, stage ->
                                ProcessingStageCard(stage, index, active, busy, error != null, false,
                                    Modifier.weight(1f).fillMaxHeight(.80f), progressTag)
                                if (index < stages.lastIndex) Icon(Icons.AutoMirrored.Outlined.ArrowForward, null,
                                    Modifier.size(16.dp), tint = RlColor.Secondary)
                            }
                        }
                    }
                }
            }
            if (error != null) {
                Text(error, color = RlColor.Error, fontFamily = EditorialFont.Serif, fontSize = 15.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NotebookAction(backLabel, onBack, Modifier.weight(1f))
                    NotebookAction(retryLabel, onRetry, Modifier.weight(1f), primary = true)
                }
            } else if (busy && active == null) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).size(20.dp).testTag(progressTag), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
internal fun ProcessingNotebookArtwork(modifier: Modifier, topic: String? = null) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (!topic.isNullOrBlank()) Text(topic, Modifier.fillMaxWidth().heightIn(max = 82.dp)
            .verticalScroll(rememberScrollState()).padding(5.dp), color = RlColor.Ink,
            fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold,
            fontSize = 18.sp, lineHeight = 24.sp, textAlign = TextAlign.Center)
        Image(painterResource(R.drawable.rl_processing_conversation_cards), null,
            Modifier.weight(1f).fillMaxWidth(), contentScale = ContentScale.Fit)
    }
}

@Composable
private fun ProcessingStageCard(stage: ProcessPaperStage, index: Int, active: Int?, busy: Boolean,
    failed: Boolean, compact: Boolean, modifier: Modifier, progressTag: String) {
    val current = index == active
    val done = active != null && (index < active || (current && !busy && !failed))
    val color = when {
        current && failed -> RlColor.Error
        current && !done -> Color(0xFFA74929)
        done -> Color(0xFF667347)
        else -> Color(0xFF4C687A)
    }
    val status = when {
        done -> stage.completed
        current && failed -> "未完成"
        current && busy -> stage.working
        else -> stage.pending
    }
    val symbol: @Composable () -> Unit = {
        Box(Modifier.size(if (compact) 40.dp else 49.dp), contentAlignment = Alignment.Center) {
            if (current && busy) CircularProgressIndicator(Modifier.fillMaxSize().testTag(progressTag),
                color = color, trackColor = color.copy(alpha = .18f), strokeWidth = 2.dp)
            else Box(Modifier.fillMaxSize().border(1.dp, color.copy(alpha = .75f), CircleShape))
            Icon(if (current && failed) Icons.Outlined.ErrorOutline else if (done) Icons.Outlined.CheckCircle else stage.icon,
                null, Modifier.size(27.dp), tint = color)
        }
    }
    val label: @Composable (Modifier) -> Unit = { labelModifier ->
        Text(stage.title, labelModifier, color = RlColor.Ink, fontFamily = EditorialFont.Serif,
            fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
    }
    val badge: @Composable () -> Unit = {
        Text(status, Modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            fontFamily = EditorialFont.Serif, color = Color.White, fontSize = 13.sp, lineHeight = 17.sp)
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val paper = painterResource(R.drawable.rl_notebook_panel_20261124)
        Image(paper, null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(color, BlendMode.Modulate))
        Image(paper, null, Modifier.matchParentSize().padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
            contentScale = ContentScale.FillBounds, colorFilter = NeutralNotebookPaper)
        if (compact) Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            symbol()
            label(Modifier.weight(1f))
            badge()
        } else Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
            symbol()
            Box(Modifier.fillMaxWidth().heightIn(min = 36.dp), contentAlignment = Alignment.Center) { label(Modifier) }
            badge()
        }
    }
}
