package com.reallanguage.app.feature.learning.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import com.reallanguage.app.core.designsystem.EditorialFont
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlKeyboardAwareOutlinedTextField
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.RlSpace

@Composable
fun PatternSpeakingScreen(state: PatternSpeakingUiState, onAction: (PatternSpeakingAction) -> Unit) {
    PatternSpeakingScreen(state, onAction, targetAssessment = {})
}

@Composable
fun PatternSpeakingScreen(
    state: PatternSpeakingUiState,
    onAction: (PatternSpeakingAction) -> Unit,
    targetAssessment: @Composable (com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern) -> Unit,
) {
    val currentExample = state.currentResponseExample
    val pinSample = !state.currentResponseCompleted && currentExample != null && currentExample.targetResponseEn.length <= 90 &&
        LocalDensity.current.fontScale <= 1.3f && androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 650
    val feedbackVisibility = remember { BringIntoViewRequester() }
    LaunchedEffect(state.failureMessageVisible, state.feedbackZh) {
        if (state.failureMessageVisible || state.feedbackZh != null) {
            androidx.compose.runtime.withFrameNanos { }
            feedbackVisibility.bringIntoView()
        }
    }
    // New content starts at its own heading; feedback/busy/retry emissions for the same
    // target and example retain the user's local reading position.
    val moduleScroll = key(state.currentFocusSentenceId, state.currentPattern?.patternId, state.stage,
        if (state.stage == PatternSpeakingStage.RESPONDING) currentExample?.exampleId else null) {
        rememberScrollState()
    }
    PatternNotebookFrame(state, moduleScroll,
        onBack = { onAction(PatternSpeakingAction.BackRequested) },
        targetAssessment = targetAssessment,
        pinSample = pinSample, onPronounce = { onAction(PatternSpeakingAction.PronounceRequested) },
        footer = { PatternNotebookControls(state, onAction, currentExample != null) },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().testTag("pattern-speaking-workspace"),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (state.progressFailed) {
                Text(stringResource(R.string.full_sentence_speaking_progress_failed), color = RlColor.Error)
            }
            when (state.stage) {
                PatternSpeakingStage.LOADING -> {
                    Text(
                        stringResource(
                            if (state.failureMessageVisible) R.string.pattern_speaking_loading_failed
                            else R.string.pattern_speaking_loading,
                        ),
                        color = if (state.failureMessageVisible) RlColor.Error else RlColor.TextSecondary,
                    )
                }
                PatternSpeakingStage.READING -> {
                    Text(stringResource(R.string.pattern_speaking_read_instruction),
                        fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp, color = RlColor.Ink)
                    if (state.technicalRetryPending || state.technicalRetryBlocked) {
                        Text(stringResource(if (state.technicalRetryBlocked) R.string.full_sentence_speaking_retry_blocked
                            else R.string.learning_audio_retry_pending), color = RlColor.Error)
                    }
                    state.examples.forEachIndexed { index, example ->
                        LearningPaper(Modifier.fillMaxWidth().testTag("pattern-reading-example-${example.exampleId}"), padding = PaddingValues(7.dp), contentSpacing = 4.dp) {
                          BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val readingFontScale = LocalDensity.current.fontScale
                            val stacked = maxWidth < 360.dp * readingFontScale.coerceAtMost(1.5f)
                            val exampleCopy: @Composable (Modifier) -> Unit = { copyModifier ->
                                Row(copyModifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Surface(Modifier.size(26.dp * readingFontScale.coerceAtLeast(1f)), shape = CircleShape, color = RlColor.Secondary, contentColor = RlColor.OnPrimary) {
                                        Box(contentAlignment = Alignment.Center) { Text("${index + 1}", fontFamily = EditorialFont.Serif, fontSize = 15.sp, lineHeight = 20.sp, textAlign = TextAlign.Center) }
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(example.targetResponseEn, fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 19.sp, color = RlColor.Ink)
                                        Text(example.targetResponseZh, color = RlColor.TextSecondary, fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 17.sp)
                                    }
                                }
                            }
                            val readingAction: @Composable () -> Unit = {
                              LearningAction(
                                modifier = Modifier.width(128.dp),
                                text = stringResource(
                                    if (state.technicalRetryPending && state.feedbackExampleId == example.exampleId) R.string.read_speak_retry
                                    else if (state.technicalRetryBlocked && state.feedbackExampleId == example.exampleId) R.string.full_sentence_speaking_new_recording
                                    else if (state.recording && state.readingExampleId == example.exampleId) R.string.pattern_speaking_response_finished
                                    else if (example.exampleId in state.completedReadingExampleIds) R.string.pattern_speaking_read_complete
                                    else R.string.pattern_speaking_read_once,
                                ),
                                onClick = {
                                    onAction(if (state.recording && state.readingExampleId == example.exampleId)
                                        PatternSpeakingAction.ReadingFinished(example.exampleId) else PatternSpeakingAction.StartReadingRequested(example.exampleId))
                                },
                                enabled = !state.submitting &&
                                    (!state.technicalRetryPending || state.feedbackExampleId == example.exampleId) &&
                                    (!state.recording || state.readingExampleId == example.exampleId) &&
                                    example.exampleId !in state.completedReadingExampleIds,
                                primary = example.exampleId !in state.completedReadingExampleIds,
                              )
                            }
                            if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                exampleCopy(Modifier.fillMaxWidth())
                                readingAction()
                            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                exampleCopy(Modifier.weight(1f))
                                readingAction()
                            }
                          }
                        }
                    }
                }
                PatternSpeakingStage.RESPONDING -> currentExample?.let { example ->
                    if (!pinSample) PatternExamplePrompt(example, { onAction(PatternSpeakingAction.PronounceRequested) },
                        !state.submitting && !state.recording && !state.technicalRetryPending && !state.technicalRetryBlocked)
                    if (state.currentResponseCompleted) {
                        LearningPaper(Modifier.fillMaxWidth(), padding = PaddingValues(9.dp), ruled = true) {
                            Text("本次转写", color = RlColor.TextSecondary, fontSize = 11.sp)
                            Text(state.transcript, fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp)
                        }
                    } else RlKeyboardAwareOutlinedTextField(
                        value = state.transcript,
                        onValueChange = { onAction(PatternSpeakingAction.TranscriptChanged(it)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        label = { Text("本次转写") },
                        enabled = !state.submitting && !state.recording && !state.technicalRetryPending && !state.technicalRetryBlocked,
                        shape = RlShape.Input,
                    )
                    state.feedbackZh?.let {
                        Box(Modifier.fillMaxWidth().bringIntoViewRequester(feedbackVisibility)
                            .testTag("pattern-response-feedback")) {
                            Text(it, Modifier.padding(6.dp), color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp)
                        }
                    }
                    if (state.failureMessageVisible) Text(stringResource(when {
                        state.technicalRetryPending -> R.string.learning_audio_retry_pending
                        state.technicalRetryBlocked -> R.string.full_sentence_speaking_retry_blocked
                        else -> R.string.pattern_speaking_capture_failed
                    }), modifier = Modifier.bringIntoViewRequester(feedbackVisibility), color = RlColor.Error)
                    if (!state.technicalRetryPending && !state.technicalRetryBlocked && state.repeatRecommended) {
                        OutlinedButton(onClick = { onAction(PatternSpeakingAction.RetryResponseRequested) }) {
                            Text(stringResource(R.string.pattern_speaking_read_again))
                        }
                    }
                }
                PatternSpeakingStage.UNAVAILABLE -> Text(
                    stringResource(R.string.pattern_speaking_unavailable),
                    color = RlColor.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PatternNotebookControls(state: PatternSpeakingUiState,
    onAction: (PatternSpeakingAction) -> Unit, hasExample: Boolean) {
    com.reallanguage.app.core.designsystem.NotebookAction(
        stringResource(if (state.audioMuted) R.string.learning_pronounce else R.string.learning_mute),
        { onAction(PatternSpeakingAction.AudioToggled) }, Modifier.weight(1f), enabled = !state.recording && !state.submitting,
        leadingIcon = Icons.Outlined.PlayCircleOutline)
    if (state.stage == PatternSpeakingStage.RESPONDING)
        com.reallanguage.app.core.designsystem.NotebookAction("跳过场景", { onAction(PatternSpeakingAction.SkipSceneRequested) },
            Modifier.weight(1f), enabled = !state.submitting && !state.progressFailed, leadingIcon = Icons.Outlined.Close)
    val label = if (state.progressFailed) "重试保存" else when (state.stage) {
        PatternSpeakingStage.LOADING -> if (state.failureMessageVisible) "重试" else stringResource(R.string.pattern_speaking_loading)
        PatternSpeakingStage.READING -> "开始具体回应"
        PatternSpeakingStage.UNAVAILABLE -> stringResource(R.string.pattern_speaking_continue_writing)
        PatternSpeakingStage.RESPONDING -> when {
            state.technicalRetryPending -> "重试"
            state.technicalRetryBlocked -> stringResource(R.string.full_sentence_speaking_new_recording)
            state.currentResponseCompleted -> if (state.responseIndex == state.examples.lastIndex) "继续" else "下一场景"
            state.recording -> "回应完毕"
            state.failureMessageVisible -> "重新回应"
            else -> "开始回应"
        }
    }
    com.reallanguage.app.core.designsystem.NotebookAction(label, {
        onAction(if (state.progressFailed) PatternSpeakingAction.RetryProgressRequested else when (state.stage) {
            PatternSpeakingStage.LOADING -> PatternSpeakingAction.RetryLoadingRequested
            PatternSpeakingStage.READING -> PatternSpeakingAction.StartResponsesRequested
            PatternSpeakingStage.UNAVAILABLE -> PatternSpeakingAction.ContinueWithoutPatternRequested
            PatternSpeakingStage.RESPONDING -> when {
                state.technicalRetryPending -> PatternSpeakingAction.RetryResponseRequested
                state.currentResponseCompleted -> PatternSpeakingAction.NextSceneRequested
                state.recording -> PatternSpeakingAction.FinishResponseRequested
                else -> PatternSpeakingAction.StartResponseRequested
            }
        })
    }, Modifier.weight(1.35f), primary = true, leadingIcon = Icons.AutoMirrored.Outlined.ArrowForward,
        enabled = !state.submitting && (state.progressFailed || when (state.stage) {
        PatternSpeakingStage.LOADING -> state.failureMessageVisible
        PatternSpeakingStage.READING -> state.examples.isNotEmpty() && state.completedReadingExampleIds.size == state.examples.size
        PatternSpeakingStage.RESPONDING -> state.currentResponseCompleted || hasExample
        PatternSpeakingStage.UNAVAILABLE -> true
    }))
}
