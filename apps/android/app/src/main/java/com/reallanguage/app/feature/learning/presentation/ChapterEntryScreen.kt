package com.reallanguage.app.feature.learning.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import com.reallanguage.app.core.designsystem.EditorialFont
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor

@Composable
fun ChapterEntryScreen(state: ChapterEntryUiState, onAction: (ChapterEntryAction) -> Unit) {
    LearningPage {
        val pageWidth = maxWidth
        val pageHeight = maxHeight
        val fontScale = LocalDensity.current.fontScale
        val wide = maxWidth >= 600.dp * fontScale.coerceAtMost(1.2f)
        val compactHeight = pageHeight < 460.dp * fontScale.coerceAtMost(1.25f)
        val sectionSpacing = if (compactHeight) 4.dp else 7.dp
        Column(
            Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 6.dp)
                .testTag("chapter_entry_fixed_page"),
            verticalArrangement = Arrangement.spacedBy(sectionSpacing),
        ) {
            LearningHeading(state.chapter?.let { "第 ${it.chapterNumber} 章 · 共同学习" } ?: "共同学习",
                onBack = { onAction(ChapterEntryAction.BackRequested) })
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(); Text("正在读取本机章节…")
                    }
                }
                state.failed -> {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("无法读取已固定章节")
                        LearningAction("重试", { onAction(ChapterEntryAction.RetryRequested) }, primary = true)
                        LearningAction("返回首页", { onAction(ChapterEntryAction.BackRequested) })
                    }
                }
                state.chapter != null -> {
                    val chapter = state.chapter
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = if (compactHeight) 22.dp else 25.dp)
                            .testTag("chapter_entry_focus_badge_row"),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LearningRibbon(
                            text = "重点句 ${chapter.sentences.count { it.isFocus }} / ${chapter.sentences.size}",
                            modifier = Modifier.width(if (fontScale > 1.2f) 150.dp else 122.dp)
                                .heightIn(min = if (compactHeight) 22.dp else 25.dp)
                                .testTag("chapter_entry_focus_badge"),
                            color = RlColor.Primary,
                        )
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).testTag("chapter_entry_sentence_space")) {
                        val rowHeight = ((maxHeight - 18.dp) / chapter.sentences.size.coerceAtLeast(1))
                            .coerceIn(32.dp, 130.dp)
                        LearningSentenceLedger(chapter, chapter.sentences.firstOrNull { it.isFocus }?.sentenceId.orEmpty(),
                            Modifier.fillMaxWidth().align(Alignment.Center)
                                .verticalScroll(rememberScrollState()).testTag("chapter_entry_sentences"),
                            preserveHoleAspect = true, uniformColumnGrid = true, compactRows = compactHeight,
                            overviewRowMinHeight = rowHeight)
                    }
                    if (!compactHeight) Text(
                        "学习路径",
                        Modifier.align(Alignment.CenterHorizontally),
                        color = RlColor.Ink,
                        fontFamily = EditorialFont.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    val stages = listOf(
                        JourneyStage("基础讲解", RlColor.Secondary, Icons.AutoMirrored.Outlined.MenuBook),
                        JourneyStage("朗读", RlColor.PaperWarm, Icons.AutoMirrored.Outlined.VolumeUp),
                        JourneyStage("手写抄写", RlColor.ActionVivid, Icons.Outlined.Edit),
                        JourneyStage("音频听写", RlColor.PaperWarm, Icons.Outlined.Headphones),
                        JourneyStage("整句会说", RlColor.Primary, Icons.Outlined.ChatBubbleOutline),
                        JourneyStage("句式会说", RlColor.NoteAccent, Icons.Outlined.FormatQuote),
                        JourneyStage("完整书写", RlColor.Secondary, Icons.Outlined.Edit),
                        JourneyStage("场景回应", RlColor.PaperWarm, Icons.Outlined.Place),
                    )
                    Column(
                        Modifier.fillMaxWidth().testTag("chapter_entry_stages"),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        stages.chunked(if (wide) 8 else if (pageWidth > 430.dp) 4 else 2).forEachIndexed { rowIndex, row ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                row.forEachIndexed { columnIndex, stage ->
                                    StageCard(stage, stages.indexOf(stage) + 1,
                                        Modifier.weight(1f).height(if (wide) 48.dp else 38.dp)
                                            .testTag("chapter_entry_stage_${rowIndex}_${columnIndex}"), wide)
                                    if (columnIndex < row.lastIndex) Icon(Icons.AutoMirrored.Outlined.ArrowForward,
                                        null, Modifier.size(10.dp), tint = RlColor.BorderStrong)
                                }
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = if (compactHeight) 0.dp else 2.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LearningAction(
                            "开始学习",
                            { onAction(ChapterEntryAction.StartRequested) },
                            Modifier.width(if (wide) 270.dp else 240.dp)
                                .height(48.dp)
                                .testTag("chapter_entry_start"),
                            enabled = chapter.sentences.any { it.isFocus },
                            primary = true,
                        )
                    }
                }
            }
        }
    }
}

private data class JourneyStage(val label: String, val color: Color, val icon: ImageVector)

@Composable
private fun StageCard(stage: JourneyStage, number: Int, modifier: Modifier, wide: Boolean) {
    val foreground = if (stage.color == RlColor.PaperWarm) RlColor.Primary else stage.color
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(stage.icon, null, Modifier.size(if (wide) 18.dp else 14.dp), tint = foreground)
                Text(number.toString().padStart(2, '0'), color = RlColor.TextSecondary, fontSize = 10.sp)
            }
            Text(stage.label, color = RlColor.Ink, fontFamily = EditorialFont.Serif,
                fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1)
        }
    }
}
