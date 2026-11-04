package com.reallanguage.app.feature.review.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import com.reallanguage.app.core.designsystem.EditorialFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.feature.learning.presentation.*
import com.reallanguage.app.feature.review.domain.ReviewAbilityModule

@Composable
fun ReviewChapterScreen(state: ReviewChapterUiState, onAction: (ReviewChapterAction) -> Unit) {
    val chapterNumber = state.chapter?.originalChapterNumber?.takeIf { it > 0 }
    val originalSentences = state.chapter?.let { chapter ->
        chapter.originalSentences?.takeIf { sentences ->
            sentences.isNotEmpty() && sentences.map { it.textEn } == chapter.originalScene &&
                sentences.all { it.sentenceId.isNotBlank() && it.sentenceOrder >= 0 } &&
                sentences.map { it.sentenceId }.distinct().size == sentences.size &&
                sentences.zipWithNext().all { (first, second) -> first.sentenceOrder < second.sentenceOrder }
        }
    }
    val focusSentences = originalSentences?.filter { it.isFocus }
    val currentFocusId = state.currentAbility?.sentenceId?.takeIf { id -> focusSentences?.any { it.sentenceId == id } == true }
    val focusPosition = focusSentences?.indexOfFirst { it.sentenceId == currentFocusId }?.takeIf { it >= 0 }?.plus(1)
    val totalSentences = focusSentences?.size
    LearningPage(Modifier.testTag("review-chapter-workspace")) {
        val stacked = maxWidth < 600.dp * LocalDensity.current.fontScale
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            LearningHeading(chapterNumber?.let { "统一回顾 · 第 $it 章" } ?: stringResource(R.string.review_chapter_title),
                onBack = { onAction(ReviewChapterAction.ExitRequested) }, trailing = {
                    Column(horizontalAlignment = Alignment.End) {
                        LearningRibbon(stringResource(R.string.today_review_badge), color = RlColor.ActionVivid)
                        if (focusPosition != null && totalSentences != null) Text("重点句 $focusPosition / $totalSentences", fontSize = 13.sp)
                    }
                })
            when {
                state.loading -> LearningPaper(Modifier.fillMaxWidth()) { CircularProgressIndicator() }
                state.loadFailed -> LearningPaper(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.review_chapter_load_failed), color = RlColor.Error)
                    LearningAction(stringResource(R.string.diary_retry), { onAction(ReviewChapterAction.RetryLoadRequested) }, primary = true)
                }
                state.chapter != null -> {
                    LearningPaper(Modifier.fillMaxWidth(), PaddingValues(8.dp), ruled = true, contentSpacing = 3.dp) {
                        Text(state.chapter.sourceTitle, color = RlColor.TextSecondary, fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 17.sp)
                        state.chapter.originalScene.forEachIndexed { index, line ->
                            val sentenceId = originalSentences?.get(index)?.sentenceId
                            val isCurrent = currentFocusId != null && sentenceId == currentFocusId
                            val rowModifier = Modifier.fillMaxWidth()
                                .then(if (sentenceId != null) Modifier.testTag("review-context-$sentenceId") else Modifier)
                                .semantics { selected = isCurrent }.background(if (isCurrent) RlColor.NoteContainer else Color.Transparent)
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                            if (stacked) Column(rowModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(line, fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp, color = if (isCurrent) RlColor.ActionVivid else RlColor.Ink)
                                originalSentences?.get(index)?.let { Text(it.textZh, color = RlColor.TextSecondary, fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp) }
                            } else Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                Text(line, Modifier.weight(1f), fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp, color = if (isCurrent) RlColor.ActionVivid else RlColor.Ink)
                                originalSentences?.get(index)?.let { Text(it.textZh, Modifier.weight(1f), color = RlColor.TextSecondary, fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp) }
                            }
                        }
                    }
                    LearningTwoPane(leftWeight = .37f, left = { pane ->
                        LearningOliveNote(pane.heightIn(min = 156.dp)) {
                                Text("统一回顾", color = Color.White, fontFamily = EditorialFont.Serif, fontSize = 25.sp, lineHeight = 32.sp)
                                Text("在真实场景里重新找回表达。", color = Color.White, fontFamily = EditorialFont.Serif, fontSize = 16.sp, lineHeight = 23.sp)
                        }
                    }, right = { pane -> LearningPaper(pane, PaddingValues(14.dp), contentSpacing = 6.dp) {
                        Text(stringResource(R.string.review_chapter_pending_title), fontFamily = EditorialFont.Serif, fontSize = 17.sp, lineHeight = 23.sp)
                        state.pendingAbilities.forEach { ability ->
                            Surface(color = if (ability == state.currentAbility) RlColor.PrimaryContainerStrong else RlColor.NoteContainer) {
                                Text(stringResource(ability.module.labelResource()), Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                                    color = if (ability == state.currentAbility) RlColor.Primary else RlColor.TextSecondary,
                                    fontFamily = EditorialFont.Serif, fontSize = 15.sp, lineHeight = 21.sp)
                            }
                        }
                        if (state.commitFailed) {
                            Text(stringResource(R.string.review_chapter_commit_failed), color = RlColor.Error)
                            LearningAction(stringResource(R.string.diary_retry), { onAction(ReviewChapterAction.RetryCommitRequested) }, primary = true)
                        } else if (state.committing) Text(stringResource(R.string.review_chapter_committing))
                    } })
                }
            }
        }
        if (state.chapter != null && !state.loading && !state.loadFailed) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LearningAction(stringResource(R.string.review_exit_title), { onAction(ReviewChapterAction.ExitRequested) }, Modifier.weight(1f))
            if (!state.commitFailed && state.currentAbility != null && !state.committing) {
                LearningAction(stringResource(R.string.review_chapter_continue), { onAction(ReviewChapterAction.OpenCurrentModuleRequested) },
                    Modifier.weight(1.4f).testTag("review-chapter-continue"), primary = true)
            }
        }
        }
    }
}

private fun ReviewAbilityModule.labelResource(): Int = when (this) {
    ReviewAbilityModule.COMPLETE_WRITING -> R.string.review_module_complete_writing
    ReviewAbilityModule.FULL_SENTENCE_SPEAKING -> R.string.review_module_sentence_speaking
    ReviewAbilityModule.SCENE_RESPONSE -> R.string.review_module_scene_response
    ReviewAbilityModule.WORD_MEANING -> R.string.review_module_word_meaning
    ReviewAbilityModule.PATTERN_WRITING -> R.string.review_module_pattern_writing
    ReviewAbilityModule.PATTERN_SPEAKING -> R.string.review_module_pattern_speaking
}
