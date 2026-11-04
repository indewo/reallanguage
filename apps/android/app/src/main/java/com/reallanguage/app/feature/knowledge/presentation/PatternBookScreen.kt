package com.reallanguage.app.feature.knowledge.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.*
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.PatternRecord

@Composable fun PatternBookScreen(state: PatternBookUiState, onAction: (PatternBookAction) -> Unit) {
    // Expanded reading has its own scroll position. Removing the inline detail
    // must not clamp the original list anchor to the end of the shorter list.
    // A changed query/data snapshot starts a new list, not a stale old anchor.
    val listScrollState = key(state.query, state.patterns) { rememberScrollState() }
    val contentScrollState = if (state.expandedPattern != null) {
        key(state.query, state.patterns) { rememberScrollState(listScrollState.value) }
    } else listScrollState
    val selectedPattern = state.expandedPattern
    val selectedRow = remember { BringIntoViewRequester() }
    val firstExample = remember { BringIntoViewRequester() }
    var selectedRowPlaced by remember(selectedPattern?.patternId, contentScrollState) { mutableStateOf(false) }
    var firstExamplePlaced by remember(selectedPattern?.patternId, contentScrollState) { mutableStateOf(false) }
    LaunchedEffect(selectedPattern?.patternId, contentScrollState, selectedRowPlaced, firstExamplePlaced) {
        if (selectedPattern != null && selectedRowPlaced &&
            (selectedPattern.examples.isEmpty() || firstExamplePlaced)) {
            // A new selection or query/data scroll context reveals its own
            // content after placement. Manual scroll values and playback
            // recompositions are not keys and cannot pull the user back.
            if (selectedPattern.examples.isNotEmpty()) firstExample.bringIntoView()
            selectedRow.bringIntoView()
        }
    }
    PatternBookFrame(state.query, { onAction(PatternBookAction.QueryChanged(it)) },
        { onAction(PatternBookAction.ReturnHomeRequested) }, scrollState = contentScrollState,
        detailVisible = selectedPattern != null, feedback = {
        if (state.pronunciationBusy) Text(stringResource(R.string.vocabulary_playback_busy), color = RlColor.TextSecondary,
            modifier = Modifier.testTag("pattern-pronunciation-busy"))
        if (state.pronunciationFailed) {
            Text(stringResource(R.string.vocabulary_playback_failed), color = RlColor.Error,
                modifier = Modifier.testTag("pattern-pronunciation-failed"))
            TextButton({ onAction(PatternBookAction.RetryPronunciationRequested) }, enabled = !state.loading,
                modifier = Modifier.testTag("pattern-pronunciation-retry")) {
                Text(stringResource(R.string.vocabulary_playback_retry), color = RlColor.Primary)
            }
        }
    }) {
        when {
            state.loading -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = RlColor.Primary, strokeWidth = 2.dp)
                Text(stringResource(R.string.pattern_book_loading), color = RlColor.TextSecondary)
            }
            state.loadFailed -> Column(verticalArrangement = Arrangement.spacedBy(RlSpace.Space8)) {
                Text(stringResource(R.string.pattern_book_load_failed), color = RlColor.Error)
                RlPrimaryButton(stringResource(R.string.diary_retry), true,
                    { onAction(PatternBookAction.RetryRequested) })
            }
            state.patterns.isEmpty() -> Text(stringResource(R.string.pattern_book_empty))
            state.visiblePatterns.isEmpty() -> Text("没有匹配的句式或功能")
            else -> Column(Modifier.fillMaxWidth().testTag("pattern-book-list")) {
                state.visiblePatterns.forEach { pattern ->
                    val index = state.patterns.indexOfFirst { it.patternId == pattern.patternId }
                    val expanded = state.expandedPattern?.patternId == pattern.patternId
                    Column(Modifier.fillMaxWidth().testTag("pattern-item-${pattern.patternId}")) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val rowInset = if (selectedPattern != null && maxWidth >= 600.dp &&
                                LocalDensity.current.fontScale <= 1.3f) maxWidth * .135f else 0.dp
                            PatternRow(pattern, index, expanded, onAction, detailVisible = selectedPattern != null,
                                modifier = Modifier.padding(start = rowInset).then(if (expanded)
                                    Modifier.bringIntoViewRequester(selectedRow)
                                        .onGloballyPositioned { selectedRowPlaced = true } else Modifier))
                        }
                        if (expanded) PatternDetail(pattern, state.loading, onAction,
                            Modifier.bringIntoViewRequester(firstExample)
                                .onGloballyPositioned { firstExamplePlaced = true })
                    }
                }
            }
        }
    }
}

@Composable
private fun PatternRow(pattern: PatternRecord, index: Int, expanded: Boolean, onAction: (PatternBookAction) -> Unit,
    detailVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    val accent = when (index % 4) { 0 -> RlColor.Secondary; 1 -> RlColor.ActionVivid; 2 -> RlColor.NoteAccent; else -> RlColor.Primary }
    Column(modifier.fillMaxWidth().testTag("pattern-row-${pattern.patternId}")
        .clickable { onAction(PatternBookAction.PatternSelected(pattern.patternId)) }) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = if (detailVisible) 4.dp else 10.dp)) {
            val stacked = maxWidth < 470.dp || fontScale > 1.3f
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = if (detailVisible) 40.dp else 44.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (detailVisible) 12.dp else 17.dp)) {
                    Box(Modifier.width(29.dp * fontScale).heightIn(min = 22.dp)
                        .align(if (detailVisible) Alignment.CenterVertically else Alignment.Top), contentAlignment = Alignment.Center) {
                        Image(painterResource(when (index % 4) { 0 -> R.drawable.rl_olive_torn_paper
                            1 -> R.drawable.rl_torn_terracotta; 2 -> R.drawable.rl_torn_note; else -> R.drawable.rl_torn_blue }), null,
                            Modifier.matchParentSize(), contentScale = ContentScale.FillBounds,
                            colorFilter = if (index % 4 == 2) ColorFilter.tint(RlColor.NoteAccent) else null)
                        Text("%02d".format(index + 1), color = RlColor.OnPrimary,
                            fontSize = 14.sp, lineHeight = 18.sp, fontFamily = EditorialFont.Serif,
                            modifier = Modifier.padding(vertical = 2.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(pattern.structureEn, color = accent, fontFamily = EditorialFont.Serif,
                            fontSize = 15.sp, lineHeight = 21.sp)
                        if (!detailVisible) Canvas(Modifier.fillMaxWidth().height(1.dp)) {
                            drawLine(accent.copy(alpha = .7f), Offset.Zero, Offset(size.width, 0f),
                                strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 4f)))
                        }
                        Text(pattern.functionZh, color = RlColor.TextSecondary, fontFamily = EditorialFont.Serif,
                            fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    if (!stacked) {
                        val statusColumn = Modifier.width(105.dp).then(if (detailVisible) Modifier else Modifier.drawBehind {
                            drawLine(RlColor.BorderStrong.copy(alpha = .6f),
                                Offset((-8).dp.toPx(), -6.dp.toPx()), Offset((-8).dp.toPx(), size.height + 6.dp.toPx()),
                                strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 4f)))
                        })
                        PatternStatus("会写", pattern.writingStatus, pattern.writingConsecutiveSuccessCount, false, statusColumn, accent)
                        PatternStatus("会说", pattern.speakingStatus, pattern.speakingConsecutiveSuccessCount, true, statusColumn, accent)
                    }
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
                        tint = RlColor.Primary, modifier = Modifier.size(20.dp))
                }
                if (stacked) Row(Modifier.padding(start = 45.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    PatternStatus("会写", pattern.writingStatus, pattern.writingConsecutiveSuccessCount, false, Modifier.weight(1f))
                    PatternStatus("会说", pattern.speakingStatus, pattern.speakingConsecutiveSuccessCount, true, Modifier.weight(1f))
                }
            }
        }
        HorizontalDivider(color = RlColor.Border.copy(alpha = .65f), thickness = .5.dp)
    }
}

@Composable
private fun PatternStatus(label: String, status: KnowledgeStatus, count: Int, spoken: Boolean, modifier: Modifier,
    color: androidx.compose.ui.graphics.Color = RlColor.Secondary) {
    Row(modifier.semantics { contentDescription = "$label，${status.displayName()}，连续成功 $count 次" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(23.dp).then(if (status != KnowledgeStatus.NOT_STARTED) Modifier.background(color, CircleShape) else Modifier),
            contentAlignment = Alignment.Center) {
            Icon(when { status == KnowledgeStatus.NOT_STARTED -> Icons.Outlined.Lock
                status == KnowledgeStatus.LEARNING -> Icons.Outlined.MenuBook
                spoken -> Icons.Outlined.ChatBubbleOutline; else -> Icons.Outlined.Edit },
                null, tint = if (status == KnowledgeStatus.NOT_STARTED) RlColor.TextSecondary else RlColor.OnSecondary,
                modifier = Modifier.size(16.dp))
        }
        Text(if (status == KnowledgeStatus.MASTERED) "$label  $count 次" else status.displayName(),
            color = RlColor.Ink, fontFamily = EditorialFont.Serif,
            fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatternDetail(pattern: PatternRecord, loading: Boolean, onAction: (PatternBookAction) -> Unit,
    firstExampleModifier: Modifier = Modifier,
) {
    val largeType = LocalDensity.current.fontScale > 1.3f
    PatternDetailPaper(Modifier.fillMaxWidth().testTag("pattern-detail-sheet")) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = if (largeType) 12.dp else 4.dp),
            verticalArrangement = Arrangement.spacedBy(if (largeType) 9.dp else 2.dp)) {
            PatternDetailColumns(1f, 1.1f, first = { columnModifier ->
                Column(columnModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PatternDetailSectionTitle(if (pattern.structureParts.isEmpty()) "结构说明" else "结构拆解")
                    if (pattern.structureParts.isEmpty()) {
                        Text(pattern.structureEn, color = RlColor.Ink, style = MaterialTheme.typography.bodySmall.copy(fontFamily = EditorialFont.Serif, fontSize = 13.sp, lineHeight = 19.sp))
                    } else {
                        FlowRow(Modifier.fillMaxWidth().testTag("pattern-structure-parts-${pattern.patternId}"),
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            pattern.structureParts.forEach { part ->
                                Surface(border = BorderStroke(.5.dp, RlColor.BorderStrong), color = RlColor.PaperWarm) {
                                    Text(part, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = RlColor.Ink,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = EditorialFont.Serif,
                                            fontSize = 13.sp, lineHeight = 19.sp))
                                }
                            }
                        }
                    }
                    // The exact function is already displayed under this pattern's
                    // heading. Repeating it here is not a translation of each part
                    // (those translations are not stored) and steals a full line
                    // from the reference's examples/actions on shorter windows.
                    pattern.relatedVocabulary?.takeIf { it.isNotEmpty() }?.let { vocabulary ->
                        Column(Modifier.fillMaxWidth().testTag("pattern-related-vocabulary-${pattern.patternId}"),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            PatternDetailSectionTitle("相关生词")
                            vocabulary.forEach { item ->
                                Text("${item.display}：${item.senseZh}", color = RlColor.Ink,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = EditorialFont.Serif, fontSize = 13.sp, lineHeight = 19.sp))
                            }
                        }
                    }
                }
            }, second = { columnModifier ->
                Column(columnModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PatternDetailSectionTitle("代表场景")
                    pattern.representativeScene?.let { Text(it, color = RlColor.TextSecondary,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 18.sp)) }
                    }
                    Image(painterResource(R.drawable.rl_v73_pattern_detail_scene), null,
                        Modifier.fillMaxWidth().heightIn(min = 32.dp).aspectRatio(10.3f)
                            .testTag("pattern-detail-scene"),
                        alignment = Alignment.Center, contentScale = ContentScale.Crop)
                }
            })
            HorizontalDivider(color = RlColor.Border)
            PatternDetailColumns(1.8f, .85f, first = { columnModifier ->
                Column(columnModifier) {
                    PatternDetailSectionTitle("典型例句")
                    pattern.examples.forEachIndexed { index, example ->
                        TextButton({ onAction(PatternBookAction.ExamplePronunciationRequested(pattern.patternId, index)) },
                            enabled = !loading && example.pronunciationText.isNotBlank(),
                            shape = androidx.compose.ui.graphics.RectangleShape,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pattern-example-${pattern.patternId}-$index"),
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)) {
                            PatternDetailAudioIcon(stringResource(R.string.vocabulary_example_pronunciation, index + 1))
                            Column(Modifier.weight(1f).padding(start = 5.dp)) {
                                Text(example.textEn, (if (index == 0) firstExampleModifier else Modifier).fillMaxWidth(), color = RlColor.Ink,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = EditorialFont.Serif, fontSize = 13.sp, lineHeight = 18.sp))
                                Text(example.textZh, Modifier.fillMaxWidth(), color = RlColor.TextSecondary,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 17.sp))
                            }
                        }
                    }
                }
            }, second = { columnModifier ->
                Column(columnModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PatternDetailSectionTitle("学习统计")
                    PatternDetailStatistic("连续会写 ${pattern.writingConsecutiveSuccessCount} 次", Icons.Outlined.Edit, RlColor.Secondary)
                    PatternDetailStatistic("连续会说 ${pattern.speakingConsecutiveSuccessCount} 次", Icons.Outlined.ChatBubbleOutline, RlColor.ActionVivid)
                    PatternDetailOccurrenceStatistic("共出现 ${pattern.occurrenceCount} 次")
                    if (!pattern.naturalRevalidation) Text(stringResource(R.string.pattern_detail_not_natural),
                        color = RlColor.TextSecondary, style = MaterialTheme.typography.bodySmall.copy(fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 18.sp))
                }
            })
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PatternDetailAction(stringResource(R.string.vocabulary_detail_collapse), destructive = false,
                    modifier = Modifier.testTag("pattern-detail-collapse")) { onAction(PatternBookAction.CollapseRequested) }
                PatternDetailAction(stringResource(R.string.pattern_detail_remove), destructive = true,
                    modifier = Modifier.testTag("pattern-detail-remove")) { onAction(PatternBookAction.RemoveRequested) }
            }
        }
    }
}

@Composable
private fun PatternDetailColumns(
    firstWeight: Float,
    secondWeight: Float,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 440.dp || fontScale > 1.3f) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                first(Modifier.weight(firstWeight))
                second(Modifier.weight(secondWeight))
            }
        }
    }
}

private fun KnowledgeStatus.displayName(): String = when (this) {
    KnowledgeStatus.NOT_STARTED -> "待验证"
    KnowledgeStatus.LEARNING -> "学习中"
    KnowledgeStatus.TENTATIVE -> "初步掌握"
    KnowledgeStatus.MASTERED -> "已掌握"
}
