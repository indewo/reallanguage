package com.reallanguage.app.feature.learning.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import com.reallanguage.app.core.designsystem.EditorialFont
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.feature.learning.domain.ChapterResultCategory
import com.reallanguage.app.feature.learning.domain.ChapterResultSummaryItem

@Composable
fun ChapterResultScreen(state: ChapterResultUiState, onAction: (ChapterResultAction) -> Unit) {
    LearningArchivePage(startFraction = .20f, endFraction = .11f) {
        val compactFooter = maxWidth < 500.dp * LocalDensity.current.fontScale.coerceAtMost(1.5f)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
              .testTag("chapter-result-scroll-viewport"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LearningHeading(state.chapterNumber?.let { "第 $it 章 · 学习结果" } ?: stringResource(R.string.chapter_result_title),
                Modifier.testTag("chapter-result-heading-scroll"))
            when {
                state.archiving -> { CircularProgressIndicator(); Text(stringResource(R.string.chapter_result_archiving)) }
                state.archiveFailed -> LearningPaper(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.chapter_result_failed), color = RlColor.Error)
                    LearningAction(stringResource(R.string.read_speak_retry), { onAction(ChapterResultAction.RetryArchiveRequested) }, primary = true)
                }
                state.summary != null -> {
                    val summary = state.summary
                    val needsPractice = summary.items.firstOrNull {
                        it.completedCount < it.totalCount ||
                            it.category == ChapterResultCategory.WRONG ||
                            it.category == ChapterResultCategory.INCOMPLETE
                    }
                    val compactCompletedLayout = !summary.hasNextChapter &&
                        needsPractice == null && summary.reinforcementItems.isEmpty()
                    Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = RlColor.NoteAccent)
                        Text("本章已完成并归档", color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Icon(Icons.Outlined.CheckCircle, null, tint = RlColor.Secondary)
                    }
                    // This archive has a 20% spine margin; its reference canvas leaves
                    // 577dp of usable paper, still enough for two readable 278dp columns.
                    // Keep them side by side there, but stack on phones or larger type.
                    LearningTwoPane(Modifier.fillMaxWidth().testTag("chapter-result-summary"), leftWeight = .50f,
                        minimumTwoPaneWidth = 500.dp,
                        left = { pane -> Column(pane, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            summary.items.forEach { SummaryLedgerRow(it) }
                        } },
                        right = { pane -> Column(pane, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                          if (compactCompletedLayout) {
                            LearningPaper(
                                Modifier.fillMaxWidth().height(94.dp)
                                    .testTag("chapter-result-reinforcement-paper"),
                                PaddingValues(horizontal = 18.dp, vertical = 9.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxSize(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    LearningRibbon(
                                        "本章已全部完成",
                                        Modifier.widthIn(min = 142.dp, max = 190.dp)
                                            .heightIn(min = 38.dp)
                                            .testTag("chapter-result-complete-ribbon"),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Image(
                                        painterResource(R.drawable.rl_v73_learning_archive_seal),
                                        null,
                                        Modifier.width(58.dp).height(68.dp)
                                            .testTag("chapter-result-complete-seal"),
                                        contentScale = ContentScale.Fit,
                                    )
                                }
                            }
                            ChapterResultActions(
                                summary = summary,
                                onAction = onAction,
                                modifier = Modifier.fillMaxWidth()
                                    .testTag("chapter-result-footer"),
                            )
                          } else {
                            LearningPaper(Modifier.fillMaxWidth().testTag("chapter-result-reinforcement-paper"), PaddingValues(22.dp)) {
                              LearningRibbon(if (needsPractice == null) "本章已全部完成" else "需要继续巩固")
                              if (summary.reinforcementItems.isNotEmpty()) {
                                  summary.reinforcementItems.distinctBy { Triple(it.sentenceId, it.capabilityType, it.targetId) }.forEach { item ->
                                      Column(Modifier.fillMaxWidth().testTag("chapter-reinforcement-${item.sentenceId}-${item.capabilityType}-${item.targetId}"),
                                          verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                          Text(item.display, color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 18.sp, lineHeight = 25.sp)
                                          item.explanationZh?.takeIf { it.isNotBlank() }?.let { Text(it, color = RlColor.Ink, fontSize = 15.sp, lineHeight = 22.sp) }
                                          reinforcementCapabilityLabel(item.capabilityType)?.let { Text(stringResource(it), color = RlColor.TextSecondary, fontSize = 13.sp) }
                                          HorizontalDivider(color = RlColor.Border)
                                      }
                                  }
                              } else needsPractice?.let {
                                  Text(it.labelZh, fontFamily = EditorialFont.Serif, fontSize = 18.sp)
                                  Text(stringResource(R.string.chapter_result_count, it.completedCount, it.totalCount), color = RlColor.TextSecondary)
                              }
                            }
                            Image(painterResource(R.drawable.rl_v73_learning_archive_seal), null,
                                Modifier.align(Alignment.End).padding(end = 8.dp).height(112.dp).width(102.dp), contentScale = ContentScale.Fit)
                          }
                        } })
                }
            }
          }
          // The archive may contain arbitrarily many results. Only its body scrolls;
          // the exit/next actions remain inside the safe viewport at every text size.
          if (!state.archiving && !state.archiveFailed && state.summary != null) {
            val summary = state.summary
            val needsPractice = summary.items.any {
                it.completedCount < it.totalCount ||
                    it.category == ChapterResultCategory.WRONG ||
                    it.category == ChapterResultCategory.INCOMPLETE
            }
            val actionsAlreadyBesideResults = !summary.hasNextChapter &&
                !needsPractice && summary.reinforcementItems.isEmpty()
            if (!actionsAlreadyBesideResults) {
            Row(Modifier.fillMaxWidth(if (compactFooter) 1f else .55f)
                .align(Alignment.End).testTag("chapter-result-footer"),
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ChapterResultActions(summary, onAction, Modifier.fillMaxWidth())
            }
            }
          }
        }
    }
}

@Composable
private fun ChapterResultActions(
    summary: com.reallanguage.app.feature.learning.domain.ChapterArchiveSummary,
    onAction: (ChapterResultAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (summary.hasNextChapter) {
            LearningAction(
                stringResource(R.string.chapter_result_later),
                { onAction(ChapterResultAction.LearnLaterRequested) },
                Modifier.weight(1f),
            )
            LearningAction(
                stringResource(R.string.chapter_result_next_chapter),
                { onAction(ChapterResultAction.EnterNextChapterRequested) },
                Modifier.weight(1f),
                primary = true,
            )
        } else {
            LearningAction(
                stringResource(R.string.review_return_home),
                { onAction(ChapterResultAction.ReturnHomeRequested) },
                Modifier.fillMaxWidth(),
                primary = true,
            )
        }
    }
}

private fun reinforcementCapabilityLabel(capability: String): Int? = when (capability) {
    "FULL_WRITING" -> R.string.review_module_complete_writing
    "FULL_SPEAKING" -> R.string.review_module_sentence_speaking
    "SCENE_RESPONSE" -> R.string.review_module_scene_response
    "MEANING_WRITING" -> R.string.review_module_word_meaning
    "PATTERN_WRITING" -> R.string.review_module_pattern_writing
    "PATTERN_SPEAKING" -> R.string.review_module_pattern_speaking
    else -> null
}

@Composable
private fun SummaryLedgerRow(item: ChapterResultSummaryItem) {
    val (icon, color) = when (item.category) {
        // Historical rows can explicitly identify writing/speaking in their saved
        // label. Generic "整句能力" is not split or assigned invented capability data.
        ChapterResultCategory.SENTENCE -> when (item.labelZh.trim()) {
            "整句会说", "整句口述", "整句口语" -> Icons.Outlined.Mic to RlColor.Secondary
            "整句会写", "整句书写" -> Icons.Outlined.EditNote to RlColor.Secondary
            else -> Icons.AutoMirrored.Outlined.MenuBook to RlColor.Secondary
        }
        ChapterResultCategory.MEANING -> Icons.AutoMirrored.Outlined.MenuBook to RlColor.NoteAccent
        ChapterResultCategory.PATTERN -> Icons.Outlined.TaskAlt to RlColor.ActionVivid
        ChapterResultCategory.CORRECTION -> Icons.Outlined.CheckCircle to RlColor.TextSecondary
        ChapterResultCategory.WRONG, ChapterResultCategory.INCOMPLETE -> Icons.Outlined.Mic to RlColor.Primary
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 4.dp)
        .testTag("chapter-result-ledger-${item.category.name}"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LearningSeal(icon, color)
        Text(item.labelZh, Modifier.weight(1f), color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 16.sp, lineHeight = 22.sp)
        Text(if (item.category == ChapterResultCategory.CORRECTION) "${item.totalCount} 次" else "${item.completedCount} / ${item.totalCount}",
            color = if (item.completedCount == item.totalCount) RlColor.ActionVivid else RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 19.sp)
    }
}
