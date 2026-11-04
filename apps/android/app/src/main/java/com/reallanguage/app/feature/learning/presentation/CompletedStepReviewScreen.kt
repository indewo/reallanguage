package com.reallanguage.app.feature.learning.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
fun CompletedStepReviewScreen(state: CompletedStepReviewUiState, onAction: (CompletedStepReviewAction) -> Unit) {
    val displayTitle = state.snapshot.titleZh.split('·').joinToString(" · ") { it.trim() }
    val isScene = state.snapshot.stepId == "LR-09"
    val reviewed = state.snapshot.reviewedExpression.takeUnless { isScene }
    val sceneUnavailable = isScene && !state.loading && state.snapshot.completedSceneTasks.isEmpty()
    val showSavedFacts = !isScene || (!state.loading && !state.loadingFailed && !sceneUnavailable)
    val contentReady = !state.returning && !state.loading && !state.loadingFailed && !sceneUnavailable
    LearningArchivePage(Modifier.testTag("completed-step-review-sheet"), startFraction = .105f) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            LearningHeading(displayTitle, onBack = { onAction(CompletedStepReviewAction.ReturnToCurrentTaskRequested) },
                trailing = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.completed_step_stamp), color = RlColor.ActionVivid, fontFamily = EditorialFont.Serif, fontSize = 17.sp, lineHeight = 23.sp)
                        LearningRibbon(stringResource(R.string.completed_step_status_banner), color = Color(0xFF84867A))
                    }
                })
            LearningPaper(Modifier.fillMaxWidth().testTag("completed-step-context"), PaddingValues(6.dp), ruled = true, contentSpacing = 1.dp) {
                if (showSavedFacts && state.snapshot.contextSentences.isNotEmpty()) {
                    state.snapshot.contextSentences.forEach { sentence ->
                        val active = sentence.sentenceId == state.snapshot.activeSentenceId
                        LearningTwoPane(Modifier.fillMaxWidth().then(if (active) Modifier.background(RlColor.NoteContainer.copy(alpha = .7f)).testTag("completed-step-highlight-row") else Modifier), leftWeight = .54f,
                            left = { Text(sentence.textEn, it.padding(vertical = 2.dp), fontFamily = EditorialFont.Serif, fontSize = 13.sp, lineHeight = 18.sp,
                                color = if (active) RlColor.Ink else RlColor.TextSecondary) },
                            right = { Text(sentence.textZh, it.padding(vertical = 2.dp), fontFamily = EditorialFont.Serif, fontSize = 13.sp, lineHeight = 18.sp,
                                color = if (active) RlColor.Ink else RlColor.TextSecondary) })
                    }
                } else if (showSavedFacts) {
                    Text(state.snapshot.contentEn, Modifier.testTag("completed-step-highlight-row"), fontFamily = EditorialFont.Serif, fontSize = 18.sp, lineHeight = 26.sp)
                    Text(state.snapshot.contentZh, fontFamily = EditorialFont.Serif, fontSize = 16.sp, lineHeight = 24.sp)
                }
            }
            LearningTwoPane(leftWeight = .43f, left = { pane -> BoxWithConstraints(pane.heightIn(min = 140.dp).testTag("completed-step-expression-panel")) {
                // The lower-left 39% contains a pale botanical collage. Keep
                // audio and meaning on the blue field, including wrapped titles.
                val detailStartInset = (maxWidth * .39f - 18.dp).coerceAtLeast(76.dp)
                Image(painterResource(R.drawable.rl_v73_learning_expression_blue_paper), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (showSavedFacts) reviewed?.expression?.display ?: state.snapshot.contentEn else "", Modifier.padding(end = 32.dp), color = Color.White,
                        fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 27.sp)
                    Row(Modifier.fillMaxWidth().padding(start = detailStartInset), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        IconButton(onClick = { onAction(CompletedStepReviewAction.ExpressionAudioToggled) }, modifier = Modifier.size(36.dp).testTag("completed-step-expression-audio"), enabled = contentReady) {
                            Icon(Icons.Outlined.VolumeUp, stringResource(R.string.learning_pronounce), tint = Color.White)
                        }
                        reviewed?.explanation?.phonetic?.let { Text(it, Modifier.weight(1f), color = Color.White, fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp) }
                    }
                    Text(if (showSavedFacts) reviewed?.explanation?.contextualMeaningZh ?: reviewed?.explanation?.explanationZh ?: state.snapshot.contentZh else "",
                        Modifier.padding(start = detailStartInset).testTag("completed-step-expression-meaning"), color = Color(0xFFE7DED0), fontFamily = EditorialFont.Serif, fontSize = 15.sp, lineHeight = 22.sp)
                }
            } }, right = { pane -> Column(pane, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LearningPaper(Modifier.fillMaxWidth(), PaddingValues(8.dp), contentSpacing = 4.dp) {
                    if (state.loading) Text(stringResource(R.string.incomplete_learning_loading), color = RlColor.TextSecondary)
                    if (state.loadingFailed || sceneUnavailable) {
                        Text(stringResource(if (isScene) R.string.completed_scene_load_failed else R.string.completed_step_load_failed), color = RlColor.Error)
                        LearningAction(stringResource(R.string.diary_retry), { onAction(CompletedStepReviewAction.RetryLoadRequested) }, enabled = !state.returning)
                    }
                    if (state.audioFailed) Text(stringResource(R.string.learning_audio_failed), color = RlColor.Error)
                    if (isScene) {
                        if (contentReady) state.snapshot.completedSceneTasks.forEach { task ->
                            Column(Modifier.fillMaxWidth().testTag("completed-scene-task-${task.sentenceId}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                task.backgroundEn?.let { Text(it, color = RlColor.TextSecondary, fontFamily = EditorialFont.Serif, fontSize = 15.sp, lineHeight = 22.sp) }
                                task.contextLines.forEach { Text("${it.speaker}: ${it.textEn}", color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 17.sp, lineHeight = 25.sp) }
                                Text(task.targetSentenceEn, color = RlColor.Secondary, fontFamily = EditorialFont.Serif, fontSize = 18.sp, lineHeight = 26.sp)
                            }
                        }
                    } else if (reviewed != null) {
                        ExplanationDetailRow(stringResource(R.string.completed_step_usage), example = false) {
                            Text(reviewed.explanation.usageZh ?: stringResource(R.string.expression_usage_not_provided), color = RlColor.TextPrimary,
                                fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp)
                        }
                        reviewed.explanation.naturalExample?.let { example ->
                            ExplanationDetailRow(stringResource(R.string.completed_step_natural_example), example = true) {
                                Text(example.textEn, fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp)
                                Text(example.textZh, fontFamily = EditorialFont.Serif, fontSize = 13.sp, lineHeight = 18.sp, color = RlColor.TextSecondary)
                            }
                        }
                        reviewed.explanation.expressionHintZh?.let { ExpressionUsageHint(it) }
                    } else {
                        Text(stringResource(R.string.completed_step_read_only), color = RlColor.TextSecondary)
                        Text(state.snapshot.contentEn, fontFamily = EditorialFont.Serif, fontSize = 18.sp, lineHeight = 26.sp)
                        Text(state.snapshot.contentZh, color = RlColor.TextSecondary, fontSize = 16.sp, lineHeight = 24.sp)
                    }
                    if (state.explanationVisible) state.snapshot.explanationZh?.let {
                        Text(it, color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 16.sp, lineHeight = 24.sp)
                    }
                }
            } })
            Text(stringResource(R.string.completed_step_read_only), Modifier.testTag("completed-step-protection"), color = RlColor.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LearningAction(stringResource(if (state.audioPlaying) R.string.learning_mute else R.string.learning_pronounce),
                        { onAction(CompletedStepReviewAction.AudioToggled) }, Modifier.weight(1f).testTag("completed-step-playback"), enabled = contentReady)
                    state.snapshot.explanationZh?.let {
                        LearningAction(stringResource(R.string.completed_step_explanation), { onAction(CompletedStepReviewAction.ExplanationToggled) }, Modifier.weight(1f).testTag("completed-step-explanation-toggle"), enabled = contentReady)
                    }
                    LearningAction(stringResource(R.string.completed_step_return_current), { onAction(CompletedStepReviewAction.ReturnToCurrentTaskRequested) },
                        Modifier.weight(1.4f).testTag("completed-step-return"), enabled = !state.returning, primary = true, color = RlColor.Secondary)
                }
        }
    }
}
