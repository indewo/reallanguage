package com.reallanguage.app.feature.review.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.*
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
import com.reallanguage.app.feature.learning.presentation.*
import com.reallanguage.app.feature.review.domain.ReviewSourceType

@Composable
fun TodayReviewScreen(state: TodayReviewUiState, onAction: (TodayReviewAction) -> Unit) {
    LearningPage {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LearningHeading(stringResource(R.string.today_review_title), onBack = { onAction(TodayReviewAction.ReturnHomeRequested) })
            when {
                state.loading -> CircularProgressIndicator()
                state.loadFailed -> {
                    Text(stringResource(R.string.today_review_load_failed), color = RlColor.Error)
                    LearningAction(stringResource(R.string.diary_retry), { onAction(TodayReviewAction.RetryRequested) }, primary = true)
                }
                state.chapters.isEmpty() -> Text(stringResource(if (state.deferredByDailyLimit) R.string.today_review_deferred else R.string.today_review_empty))
                else -> LearningTwoPane(leftWeight = .52f, left = { pane ->
                  Box(pane.testTag("today-review-queue")) {
                    Image(painterResource(R.drawable.rl_v73_review_basket_empty), null,
                        Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                    Column(Modifier.fillMaxWidth().padding(start = 23.dp, end = 23.dp, top = 31.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.chapters.forEachIndexed { index, chapter ->
                            LearningPaper(Modifier.fillMaxWidth(), PaddingValues(horizontal = 18.dp, vertical = 13.dp), ruled = true) {
                              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                val color = when (index % 3) {
                                    0 -> RlColor.Secondary
                                    1 -> RlColor.ActionVivid
                                    else -> RlColor.NoteAccent
                                }
                                LearningSeal(when (chapter.sourceType) {
                                    ReviewSourceType.DIARY -> Icons.Outlined.EditNote
                                    ReviewSourceType.QUICK_RECORDING -> Icons.Outlined.Mic
                                    ReviewSourceType.AI_SCENE -> Icons.Outlined.People
                                }, color)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(stringResource(chapter.sourceType.labelResource()), color = color, fontFamily = EditorialFont.Serif, fontSize = 13.sp)
                                    Text(chapter.sourceTitle, color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 25.sp)
                                }
                              }
                                Text(chapter.focusSentence, color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 21.sp)
                                Text(stringResource(R.string.today_review_chapter_summary, chapter.focusSentenceCount, chapter.pendingAbilityCount),
                                    color = RlColor.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                            }
                        }
                    }
                  }
                }, right = { pane -> Box(pane) {
                  Image(painterResource(R.drawable.rl_v73_review_dossier), null,
                      Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                  Column(Modifier.fillMaxWidth().padding(start = 39.dp, end = 43.dp, top = 49.dp, bottom = 55.dp),
                      verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(35.dp), tint = RlColor.NoteAccent)
                    Text("今天有 ${state.chapters.sumOf { it.focusSentenceCount }} 个重点句需要回顾", color = RlColor.Ink,
                        fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 31.sp)
                    HorizontalDivider(color = RlColor.Border)
                    Text("预计 ${state.chapters.size} 个回顾章节", color = RlColor.TextSecondary, fontFamily = EditorialFont.Serif, fontSize = 17.sp)
                    LearningAction(stringResource(R.string.today_review_start), { onAction(TodayReviewAction.StartCurrentChapterRequested) },
                        Modifier.fillMaxWidth().testTag("today-review-start"), enabled = state.currentChapter != null, primary = true)
                    LearningAction(stringResource(R.string.review_return_home), { onAction(TodayReviewAction.ReturnHomeRequested) }, Modifier.fillMaxWidth())
                  }
                } })
            }
            if (state.loading || state.loadFailed || state.chapters.isEmpty()) {
                LearningAction(stringResource(R.string.review_return_home), { onAction(TodayReviewAction.ReturnHomeRequested) })
            }
        }
    }
}

private fun ReviewSourceType.labelResource(): Int = when (this) {
    ReviewSourceType.DIARY -> R.string.today_review_source_diary
    ReviewSourceType.QUICK_RECORDING -> R.string.today_review_source_recording
    ReviewSourceType.AI_SCENE -> R.string.today_review_source_ai_scene
}
