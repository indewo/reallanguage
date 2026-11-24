package com.reallanguage.app.feature.formation.presentation

import androidx.compose.foundation.Image
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.*

/** V73 generated process-reference layout. The step list describes work, not fabricated progress. */
@Composable
internal fun EditorialProcessWorkspace(
    title: String,
    supportingText: String,
    stages: List<String>,
    activeStage: Int = 1,
    failed: Boolean = false,
    modifier: Modifier = Modifier,
    @DrawableRes poster: Int = R.drawable.rl_v73_semantic_poster_r2,
    pageTitle: String? = null,
    posterCaption: String? = null,
    footerNote: String? = null,
    fixedStages: Boolean = false,
    artwork: (@Composable (Modifier) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    RlPaperBackground(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val compact = maxWidth < 640.dp || maxHeight < 330.dp || LocalDensity.current.fontScale > 1.35f
            val showCompactArtwork = artwork != null && maxWidth >= 640.dp && LocalDensity.current.fontScale <= 1.35f
            val stageScrollState = rememberScrollState()
            val heading: @Composable ColumnScope.() -> Unit = {
                Text(title, modifier = Modifier.fillMaxWidth(), color = if (failed) RlColor.Error else Color(0xFF934019),
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 26.sp else 31.sp, lineHeight = if (compact) 34.sp else 41.sp,
                    textAlign = TextAlign.Center)
                Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null,
                    Modifier.align(Alignment.CenterHorizontally).width(190.dp).height(6.dp), contentScale = ContentScale.FillBounds)
                if (supportingText.isNotBlank()) Text(supportingText, Modifier.fillMaxWidth(), color = RlColor.Ink, fontFamily = EditorialFont.Serif,
                    fontSize = 15.sp, lineHeight = 23.sp, textAlign = TextAlign.Center)
            }
            val body: @Composable ColumnScope.() -> Unit = {
                heading()
                Spacer(Modifier.height(8.dp))
                stages.forEachIndexed { index, label ->
                    Row(Modifier.fillMaxWidth().padding(vertical = if (fixedStages) 5.dp else 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).border(1.dp,
                            if (failed && index == activeStage) RlColor.Error else RlColor.Primary, CircleShape),
                            contentAlignment = Alignment.Center) {
                            Icon(processStageIcon(label),
                                null, tint = if (failed && index == activeStage) RlColor.Error else RlColor.Primary)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(label, color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold,
                                fontSize = 19.sp, lineHeight = 27.sp)
                        }
                    }
                    if (index != stages.lastIndex) HorizontalDivider(color = RlColor.Border.copy(alpha = .55f))
                }
                Spacer(Modifier.height(8.dp))
            }
            if (compact) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (fixedStages) {
                        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (showCompactArtwork && artwork != null) {
                            Box(Modifier.weight(.70f).fillMaxHeight().testTag("process_context_panel")) {
                                artwork(Modifier.matchParentSize())
                            }
                        }
                        EditorialSheet(
                            Modifier.weight(1.30f).fillMaxHeight(),
                            contentPadding = 8.dp,
                            contentSpacing = 3.dp,
                            contentModifier = Modifier.fillMaxSize().testTag("process_stage_panel"),
                            showSpine = true,
                            roundSpineHoles = true,
                        ) {
                            heading()
                            Row(
                                Modifier.fillMaxWidth().weight(1f).testTag("process_stage_rail"),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                stages.forEachIndexed { index, label ->
                                    ProcessStageCard(
                                        index = index,
                                        label = label,
                                        failed = failed && index == activeStage,
                                        compactCard = true,
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                    )
                                }
                            }
                        }
                        }
                    } else {
                        Column(
                            Modifier.fillMaxWidth().weight(1f)
                                .verticalScroll(stageScrollState)
                                .testTag("process_stage_panel"),
                        ) {
                            EditorialSheet(contentPadding = 24.dp, content = body)
                        }
                    }
                    EditorialSheet(contentPadding = 12.dp, content = content)
                }
            } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pageTitle?.let {
                    Text(it, Modifier.fillMaxWidth(), color = RlColor.Ink,
                        fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold,
                        fontSize = 27.sp, lineHeight = 34.sp, textAlign = TextAlign.Center)
                    HorizontalDivider(color = RlColor.Border)
                }
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.weight(.70f).fillMaxHeight()) {
                        if (artwork != null) artwork(Modifier.matchParentSize())
                        else Image(painterResource(poster), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                        posterCaption?.let { caption ->
                            Text(caption, Modifier.fillMaxWidth().padding(horizontal = 35.dp, vertical = 28.dp), color = RlColor.OnPrimary,
                                fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 19.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Column(Modifier.weight(1.30f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EditorialSheet(Modifier.fillMaxWidth().weight(1f), contentPadding = if (fixedStages) 10.dp else 14.dp,
                            showSpine = true, roundSpineHoles = fixedStages,
                            contentModifier = Modifier.fillMaxSize()
                                .then(if (fixedStages) Modifier else Modifier.verticalScroll(stageScrollState))
                                .testTag("process_stage_panel"), contentSpacing = 5.dp) {
                            heading()
                            Spacer(Modifier.height(3.dp))
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag("process_stage_rail"), verticalAlignment = Alignment.CenterVertically) {
                                stages.forEachIndexed { index, label ->
                                    ProcessStageCard(index, label, failed && index == activeStage, fixedStages,
                                        Modifier.weight(1f).heightIn(min = if (fixedStages) 118.dp else 150.dp).fillMaxHeight())
                                    if (index != stages.lastIndex) {
                                        val connectorColor = if (index == 0) RlColor.Secondary else RlColor.Primary
                                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null,
                                            Modifier.width(22.dp).height(18.dp), tint = connectorColor)
                                    }
                                }
                            }
                        }
                        EditorialSheet(Modifier.fillMaxWidth(), contentPadding = 10.dp, contentSpacing = 6.dp) {
                            if (footerNote != null && !failed) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(content = content)
                                Text(footerNote, Modifier.weight(1f), color = RlColor.Ink, fontFamily = EditorialFont.Serif,
                                    fontSize = 15.sp, lineHeight = 23.sp)
                            } else content()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessStageCard(index: Int, label: String, failed: Boolean, compactCard: Boolean, modifier: Modifier) {
    val accent = if (failed) RlColor.Error else listOf(RlColor.Secondary, RlColor.ActionVivid, RlColor.Primary)[index % 3]
    Box(modifier.testTag("process_stage_card_$index")) {
        Box(Modifier.aspectRatio(1176f / 1337f).align(Alignment.Center).clipToBounds().testTag("process_stage_art_$index")) {
        Image(painterResource(listOf(R.drawable.rl_v73_process_card_green, R.drawable.rl_v73_process_card_terracotta, R.drawable.rl_v73_process_card_blue)[index % 3]), null,
            Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            val labelStyle = LocalTextStyle.current.copy(
                fontFamily = EditorialFont.Serif,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineBreak = LineBreak.Heading,
            )
            val labelSize = fitStageLabelSize(label, maxWidth, maxHeight, labelStyle)
            val iconSize = if (compactCard) 24.dp else 30.dp
            // The artwork already contains a pin and a circular stamp. Keep the semantic icon in
            // the stamp and the label below it, instead of stacking either one on the torn edges.
            Icon(processStageIcon(label), null, tint = accent,
                modifier = Modifier.align(Alignment.Center).offset(y = -maxHeight * .11f)
                    .size(iconSize).testTag("process_stage_icon_$index"))
            Text(label, color = accent, maxLines = 2,
                modifier = Modifier.align(Alignment.BottomCenter).offset(y = -maxHeight * .12f)
                    .fillMaxWidth().testTag("process_stage_label_$index"),
                style = labelStyle.copy(fontSize = labelSize, lineHeight = labelSize * 1.3f))
        }
        }
    }
}

/** Keeps the stage label below the stamp and clear of the torn bottom edge. */
@Composable
private fun fitStageLabelSize(label: String, maxWidth: Dp, maxHeight: Dp, style: TextStyle): TextUnit {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(label, maxWidth, maxHeight, style, density) {
        val widthPx = with(density) { maxWidth.toPx() }.toInt().coerceAtLeast(1)
        val labelHeightPx = with(density) { maxHeight.toPx() } * .29f
        listOf(15f, 14.5f, 14f, 13.5f, 13f, 12.5f, 12f, 11.5f).firstOrNull { size ->
            val result = measurer.measure(
                text = label,
                style = style.copy(fontSize = size.sp, lineHeight = (size * 1.3f).sp),
                maxLines = 2,
                constraints = Constraints(maxWidth = widthPx),
            )
            result.lineCount <= 2 && result.size.height <= labelHeightPx &&
                (0 until result.lineCount).all { line ->
                    result.getLineEnd(line) - result.getLineStart(line) >= 2
                }
        }?.sp ?: 11.5.sp
    }
}

private fun processStageIcon(label: String) = when {
    "提取词" in label -> Icons.Outlined.MenuBook
    "学习目标" in label -> Icons.Outlined.GpsFixed
    "分句" in label -> Icons.Outlined.ContentCut
    "检查" in label -> Icons.Outlined.FactCheck
    "生成自然" in label -> Icons.Outlined.Translate
    else -> Icons.Outlined.Description
}
