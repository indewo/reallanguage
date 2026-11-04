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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    // New content starts at its own heading; feedback/busy/retry emissions for the same
    // target and example retain the user's local reading position.
    val moduleScroll = key(state.currentFocusSentenceId, state.currentPattern?.patternId, state.stage,
        if (state.stage == PatternSpeakingStage.RESPONDING) currentExample?.exampleId else null) {
        rememberScrollState()
    }
    ChapterLearningFrame(
        chapter = state.chapter,
        currentFocusSentenceId = state.currentFocusSentenceId,
        stageTitle = stringResource(R.string.pattern_speaking_title),
        onBack = { onAction(PatternSpeakingAction.BackRequested) },
        leftPaneWeight = .98f,
        rightPaneWeight = 1.22f,
        modulePaperSafeContent = true,
        leftArtworkPreferredHeight = 184.dp,
        leftArtworkOverlayRes = R.drawable.rl_scene_response_microphone,
        leftArtworkOverlaySize = 104.dp,
        leftArtworkOverlayReserve = 56.dp,
        modulePaperMinHeight = 236.dp,
        moduleScrollState = moduleScroll,
        leftArtworkRes = R.drawable.rl_v73_learning_seated_conversation,
        leftArtworkSupplement = {
            // Self-assessment remains in the scrollable content; recording status can stay in the bottom dock.
            Box(Modifier.fillMaxWidth()) {
                Image(painterResource(R.drawable.rl_torn_blue), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                Column(Modifier.fillMaxWidth()) {
                    state.currentPattern?.let { targetAssessment(it) }
                }
            }
        },
        interactiveEnglishContent = { _, modifier ->
            Text(
                state.currentPattern?.structure.orEmpty(),
                modifier = modifier,
                color = RlColor.ActionVivid,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = EditorialFont.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                ),
            )
        },
        leftPanelContent = {
          Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (state.stage == PatternSpeakingStage.READING) {
                Text("例句指读 · ${state.completedReadingExampleIds.size} / ${state.examples.size}",
                    Modifier.fillMaxWidth(), color = RlColor.OnPrimary, fontFamily = EditorialFont.Serif,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 18.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            if (state.stage == PatternSpeakingStage.RESPONDING && currentExample != null) {
                    Text(
                        "场景 ${state.responseIndex + 1} / ${state.examples.size}",
                        modifier = Modifier.fillMaxWidth(),
                        color = RlColor.OnPrimary,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = EditorialFont.Serif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        ),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
            }
            if (state.recording) {
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Mic, contentDescription = null, modifier = Modifier.size(18.dp), tint = RlColor.OnPrimary)
                    Text("正在收音", color = RlColor.OnPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
          }
        },
        footer = {
            OutlinedButton(
                onClick = { onAction(PatternSpeakingAction.AudioToggled) },
                enabled = !state.submitting,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Text(stringResource(if (state.audioMuted) R.string.learning_pronounce else R.string.learning_mute))
            }
            if (state.stage == PatternSpeakingStage.RESPONDING) {
                OutlinedButton(
                    onClick = { onAction(PatternSpeakingAction.SkipSceneRequested) },
                    enabled = !state.submitting && !state.progressFailed,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.pattern_speaking_skip_scene)) }
            }
            RlPrimaryButton(
                text = stringResource(
                    if (state.progressFailed) R.string.full_sentence_speaking_retry_save else when (state.stage) {
                        PatternSpeakingStage.LOADING -> if (state.failureMessageVisible) {
                            R.string.read_speak_retry
                        } else R.string.pattern_speaking_loading
                        PatternSpeakingStage.READING -> R.string.pattern_speaking_start_responses
                        PatternSpeakingStage.RESPONDING -> when {
                            state.technicalRetryPending -> R.string.read_speak_retry
                            state.technicalRetryBlocked -> R.string.full_sentence_speaking_new_recording
                            state.currentResponseCompleted -> R.string.pattern_speaking_next_scene
                            state.recording -> R.string.pattern_speaking_response_finished
                            else -> R.string.pattern_speaking_start_response
                        }
                        PatternSpeakingStage.UNAVAILABLE -> R.string.pattern_speaking_continue_writing
                    },
                ),
                enabled = !state.submitting && (state.progressFailed || when (state.stage) {
                    PatternSpeakingStage.LOADING -> state.failureMessageVisible
                    PatternSpeakingStage.READING -> state.examples.isNotEmpty() &&
                        state.completedReadingExampleIds.size == state.examples.size
                    PatternSpeakingStage.RESPONDING -> !state.submitting &&
                        (state.currentResponseCompleted || currentExample != null)
                    PatternSpeakingStage.UNAVAILABLE -> true
                }),
                onClick = {
                    onAction(
                        if (state.progressFailed) PatternSpeakingAction.RetryProgressRequested else when (state.stage) {
                            PatternSpeakingStage.LOADING -> PatternSpeakingAction.RetryLoadingRequested
                            PatternSpeakingStage.READING -> PatternSpeakingAction.StartResponsesRequested
                            PatternSpeakingStage.RESPONDING -> when {
                                state.currentResponseCompleted -> PatternSpeakingAction.NextSceneRequested
                                state.recording -> PatternSpeakingAction.FinishResponseRequested
                                else -> PatternSpeakingAction.StartResponseRequested
                            }
                            PatternSpeakingStage.UNAVAILABLE ->
                                PatternSpeakingAction.ContinueWithoutPatternRequested
                        },
                    )
                },
                modifier = Modifier.weight(1.35f).heightIn(min = 48.dp),
                containerColor = RlColor.ActionVivid,
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().testTag("pattern-speaking-workspace"),
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(RlSpace.Space12),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "场景 ${state.responseIndex + 1} / ${state.examples.size}",
                            modifier = Modifier.testTag("pattern-speaking-scene-progress"),
                            color = RlColor.Secondary,
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp),
                        )
                        Text(
                            example.sceneZh,
                            modifier = Modifier.weight(1f),
                            color = RlColor.TextSecondary,
                            fontSize = 13.sp, lineHeight = 18.sp,
                        )
                    }
                    Surface(color = RlColor.NoteContainer, shape = RlShape.Small) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("AI", color = RlColor.Secondary, fontWeight = FontWeight.Bold)
                            Text(example.aiPromptEn, fontFamily = EditorialFont.Serif, fontSize = 15.sp, lineHeight = 21.sp)
                        }
                    }
                    Box(Modifier.fillMaxWidth()) {
                        Image(
                            painterResource(R.drawable.rl_torn_blue), contentDescription = null,
                            modifier = Modifier.matchParentSize(), contentScale = ContentScale.FillBounds,
                        )
                        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(example.targetResponseEn, color = RlColor.OnPrimary, fontFamily = EditorialFont.Serif, fontSize = 15.sp, lineHeight = 21.sp)
                            Text(example.targetResponseZh, color = RlColor.OnPrimary, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                    }
                    RlKeyboardAwareOutlinedTextField(
                        value = state.transcript,
                        onValueChange = { onAction(PatternSpeakingAction.TranscriptChanged(it)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        label = { Text(stringResource(R.string.pattern_speaking_transcript)) },
                        enabled = !state.submitting && !state.currentResponseCompleted && !state.technicalRetryPending && !state.technicalRetryBlocked,
                        shape = RlShape.Input,
                    )
                    OutlinedButton(onClick = { onAction(PatternSpeakingAction.PronounceRequested) },
                        enabled = !state.submitting && !state.technicalRetryPending && !state.technicalRetryBlocked) {
                        Icon(Icons.Outlined.PlayCircleOutline, contentDescription = null)
                        Text(stringResource(R.string.pattern_speaking_pronounce))
                    }
                    state.feedbackZh?.let { Text(it) }
                    if (state.failureMessageVisible) {
                        Text(stringResource(when {
                            state.technicalRetryPending -> R.string.learning_audio_retry_pending
                            state.technicalRetryBlocked -> R.string.full_sentence_speaking_retry_blocked
                            else -> R.string.pattern_speaking_capture_failed
                        }), color = RlColor.Error)
                    }
                    if (!state.technicalRetryPending && !state.technicalRetryBlocked && (state.repeatRecommended || state.failureMessageVisible)) {
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
