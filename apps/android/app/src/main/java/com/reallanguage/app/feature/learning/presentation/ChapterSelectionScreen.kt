package com.reallanguage.app.feature.learning.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.reallanguage.app.core.designsystem.EditorialFont
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor

@Composable
fun ChapterSelectionScreen(state: ChapterSelectionUiState, onAction: (ChapterSelectionAction) -> Unit) {
    LearningArchivePage(startFraction = .065f, backgroundRes = R.drawable.rl_v73_learning_open_book) {
        val rightPageTopInset = if (maxWidth >= 600.dp) 20.dp else 0.dp
        IconButton(onClick = { onAction(ChapterSelectionAction.BackRequested) }, modifier = Modifier.align(Alignment.TopStart)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = RlColor.Ink)
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                LearningTwoPane(leftWeight = .31f, contentFirstWhenStacked = true, left = { pane ->
                    Column(pane.padding(start = 22.dp, end = 16.dp, top = 66.dp)) {
                        LearningPaper(
                            Modifier.fillMaxWidth().heightIn(min = 270.dp),
                            padding = PaddingValues(horizontal = 22.dp, vertical = 24.dp),
                            contentSpacing = 15.dp,
                        ) {
                            // Selection state has chapter identities, not the prior chapter's result counters.
                            // Keep the editorial archive panel without fabricating the reference's sample statistics.
                            Text("学习目录", Modifier.align(Alignment.CenterHorizontally), color = RlColor.Secondary,
                                fontFamily = EditorialFont.Serif, fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.chapter_selection_explanation), color = RlColor.TextSecondary,
                                fontFamily = EditorialFont.Serif, fontSize = 15.sp, lineHeight = 22.sp)
                            Image(painterResource(R.drawable.rl_v73_learning_archive_seal), null,
                                Modifier.align(Alignment.CenterHorizontally).size(104.dp), contentScale = ContentScale.Fit)
                        }
                    }
                }, right = { pane -> Column(pane.padding(top = rightPageTopInset), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    LearningHeading(stringResource(R.string.chapter_selection_title))
                    when {
                        state.loading -> Text(stringResource(R.string.chapter_selection_loading))
                        state.loadFailed -> {
                            Text(stringResource(R.string.chapter_selection_failed), color = RlColor.Error)
                            LearningAction(stringResource(R.string.read_speak_retry), { onAction(ChapterSelectionAction.RetryLoadRequested) })
                        }
                        else -> Text("这份日记还有 ${state.chapters.size} 个未开始章节", color = RlColor.Ink,
                            fontFamily = EditorialFont.Serif, fontSize = 20.sp, lineHeight = 28.sp)
                    }
                    Column(Modifier.fillMaxWidth().testTag("chapter-selection-list"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        state.chapters.forEachIndexed { index, chapter ->
                            LearningPaper(Modifier.fillMaxWidth().heightIn(min = 108.dp), PaddingValues(16.dp)) {
                                LearningRibbon("第 ${chapter.chapterNumber} 章", color = if (index % 2 == 0) RlColor.Primary else RlColor.Secondary)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(chapter.identityZh, Modifier.weight(1f), color = RlColor.Ink,
                                        fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 25.sp)
                                    LearningAction("开始", { onAction(ChapterSelectionAction.ChapterSelected(chapter.chapterId)) },
                                        Modifier.testTag("chapter-selection-start-${chapter.chapterId}"), primary = true)
                                }
                            }
                        }
                    }
                } })
            }
            LearningAction(
                stringResource(R.string.chapter_result_later),
                { onAction(ChapterSelectionAction.LearnLaterRequested) },
                Modifier.align(Alignment.End).widthIn(min = 168.dp).testTag("chapter-selection-later"),
                enabled = !state.loading,
            )
        }
    }
}
