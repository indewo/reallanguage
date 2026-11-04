package com.reallanguage.app.feature.learning.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import com.reallanguage.app.core.designsystem.EditorialFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlKeyboardAwareOutlinedTextField
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.feature.learning.domain.SceneEvaluationDecision

@Composable
fun SceneResponseScreen(state: SceneResponseUiState, onExit: (() -> Unit)? = null, onAction: (SceneResponseAction) -> Unit) {
    val task = state.currentTask
    val mainAction = when {
        state.saveFailed || state.progressFailed -> SceneResponseAction.RetryRequested
        state.technicalRetryPending -> SceneResponseAction.RetryRequested
        state.technicalRetryBlocked -> SceneResponseAction.StartRequested
        task == null || state.captureFailed || state.finalDecision == SceneEvaluationDecision.INCONCLUSIVE || state.finalDecision == SceneEvaluationDecision.FAIL -> SceneResponseAction.RetryRequested
        state.transcriptReady -> SceneResponseAction.ConfirmTranscriptRequested
        state.trainingRoundCompleted -> SceneResponseAction.ContinueRoundRequested
        state.recording -> SceneResponseAction.FinishedRequested
        else -> SceneResponseAction.StartRequested
    }
    val mainLabel = stringResource(when {
        state.saveFailed || state.progressFailed -> R.string.full_sentence_speaking_retry_save
        state.technicalRetryPending -> R.string.read_speak_retry
        state.technicalRetryBlocked -> R.string.full_sentence_speaking_new_recording
        state.transcribing || state.submitting -> R.string.scene_response_checking
        task == null && state.captureFailed -> R.string.read_speak_retry
        task == null -> R.string.scene_response_loading
        state.transcriptReady -> R.string.scene_response_confirm_transcript
        state.trainingRoundCompleted -> R.string.scene_response_next_round
        state.captureFailed || state.finalDecision == SceneEvaluationDecision.INCONCLUSIVE || state.finalDecision == SceneEvaluationDecision.FAIL -> R.string.read_speak_retry
        state.recording -> R.string.pattern_speaking_response_finished
        else -> R.string.pattern_speaking_start_response
    })
    LearningPage {
        val compact = maxWidth < 600.dp * LocalDensity.current.fontScale.coerceAtMost(1.5f)
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .testTag("scene-content-scroll-viewport"), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            LearningHeading("第 ${state.chapter.chapterNumber} 章 · ${stringResource(R.string.scene_response_title)}",
                onBack = { onAction(SceneResponseAction.BackRequested) }, onExit = onExit, trailing = {
                    Text(stringResource(R.string.scene_response_round_total, state.roundIndex + 1),
                        color = RlColor.ActionVivid, fontSize = 13.sp, lineHeight = 18.sp)
                })
            if (state.progressFailed) Text(stringResource(R.string.full_sentence_speaking_progress_failed), color = RlColor.Error)
            if (state.needsNewRecording) Text(stringResource(R.string.full_sentence_speaking_needs_recording), color = RlColor.Error)
            LearningPaper(Modifier.fillMaxWidth(), PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LearningRibbon(stringResource(R.string.scene_response_context_label))
                    Text(task?.backgroundEn.orEmpty(), Modifier.weight(1f).testTag("scene-background-scroll"),
                        color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 15.sp, lineHeight = 21.sp)
                }
            }
            LearningTwoPane(Modifier.fillMaxWidth().testTag("scene-response-workspace"), leftWeight = .42f, contentFirstWhenStacked = true,
                left = { pane -> Column(pane, horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(BitmapPainter(ImageBitmap.imageResource(R.drawable.rl_v73_learning_corridor_polaroid),
                        srcOffset = IntOffset(0, 124), srcSize = IntSize(1164, 1140)), null,
                        Modifier.fillMaxWidth().height(194.dp).graphicsLayer { clip = true }
                            .testTag("scene-response-context-image"),
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center) {
                    Image(painterResource(R.drawable.rl_scene_response_microphone), null,
                        Modifier.size(118.dp).offset(y = (-10).dp).testTag("scene-response-microphone-art"), contentScale = ContentScale.Fit)
                    }
                } },
                right = { pane -> Column(pane, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SceneFilmStrip()
                    LearningPaper(Modifier.fillMaxWidth(), PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(task?.contextLines?.joinToString("\n") { "${it.speaker}: ${it.textEn}" }.orEmpty(),
                            Modifier.fillMaxWidth().testTag("scene-dialogue-scroll"), color = RlColor.Ink,
                            fontFamily = EditorialFont.Serif, fontSize = 16.sp, lineHeight = 23.sp)
                    }
                    state.feedbackZh?.let { feedback -> LearningPaper(Modifier.fillMaxWidth(), PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(feedback, Modifier.testTag("scene-feedback-scroll"), fontSize = 14.sp, lineHeight = 20.sp, color = RlColor.TextPrimary)
                    } }
                    SceneResponsePaper {
                        if (state.transcriptReady) {
                            RlKeyboardAwareOutlinedTextField(value = state.transcript,
                                onValueChange = { onAction(SceneResponseAction.TranscriptChanged(it)) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                                enabled = !state.submitting && state.finalDecision == null && !state.technicalRetryPending && !state.technicalRetryBlocked,
                                shape = RlShape.Input,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White, fontFamily = EditorialFont.Serif, fontSize = 18.sp, lineHeight = 27.sp))
                        } else Text(state.transcript.ifBlank { if (state.isFinalRound) "" else task?.targetSentenceEn.orEmpty() },
                            Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("scene-transcript-scroll"),
                            color = Color.White, fontFamily = EditorialFont.Serif, fontSize = 17.sp, lineHeight = 24.sp)
                    }
                } })
            if (state.technicalRetryBlocked) Text(stringResource(R.string.full_sentence_speaking_retry_blocked), color = RlColor.Error)
            else if (state.technicalRetryPending) Text(stringResource(R.string.learning_audio_retry_pending), color = RlColor.Error)
            else if (state.captureFailed || state.finalDecision == SceneEvaluationDecision.INCONCLUSIVE) Text(stringResource(R.string.scene_response_inconclusive), color = RlColor.Error)
            if (state.finalDecision == SceneEvaluationDecision.FAIL) TextButton(onClick = { onAction(SceneResponseAction.StopPractisingRequested) }) { Text(stringResource(R.string.full_sentence_speaking_stop)) }
            if (state.saveFailed) Text(stringResource(R.string.scene_response_save_failed), color = RlColor.Error)
        }
        Column(Modifier.fillMaxWidth().testTag("scene-response-footer"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          // Recording is ongoing independently of where the user has scrolled.
          // Keep its status beside (or above) the fixed controls, not under a photo.
          val checking = state.transcribing || state.submitting
          if (compact) SceneRecordingStatus(state.recording, checking, Modifier.fillMaxWidth())
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!compact) {
                SceneRecordingStatus(state.recording, checking, Modifier.weight(.42f))
                Spacer(Modifier.width(20.dp))
            }
            Row(if (compact) Modifier.fillMaxWidth() else Modifier.weight(.58f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LearningAction(stringResource(if (state.audioMuted) R.string.learning_pronounce else R.string.scene_response_play_previous),
                    { onAction(SceneResponseAction.AudioToggled) }, Modifier.weight(1f),
                    enabled = !state.submitting && !state.transcribing && !state.technicalRetryPending && !state.technicalRetryBlocked)
                LearningAction(stringResource(R.string.read_speak_retry), { onAction(SceneResponseAction.RetryRequested) }, Modifier.weight(.7f),
                    enabled = !state.loading && !state.submitting && !state.transcribing && !state.technicalRetryBlocked)
                LearningAction(mainLabel, { onAction(mainAction) }, Modifier.weight(1.2f)
                    .testTag("scene-response-main-action"), primary = true, enabled = when {
                    state.submitting || state.transcribing || state.loading -> false
                    state.saveFailed || state.progressFailed -> true
                    task == null -> state.captureFailed
                    state.transcriptReady -> state.transcript.isNotBlank() && !state.submitting
                    state.trainingRoundCompleted -> true
                    state.captureFailed || state.finalDecision == SceneEvaluationDecision.INCONCLUSIVE || state.finalDecision == SceneEvaluationDecision.FAIL -> true
                    else -> !state.submitting && !state.transcribing && state.finalDecision == null
                })
            }
          }
        }
        }
    }
}

@Composable
private fun SceneRecordingStatus(recording: Boolean, checking: Boolean, modifier: Modifier = Modifier) {
    LearningPaper(modifier.heightIn(min = 44.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }
        .testTag("scene-response-recording-status"),
        PaddingValues(horizontal = 12.dp, vertical = 6.dp), contentSpacing = 0.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (checking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp).testTag("scene-response-checking-spinner"),
                    color = RlColor.Secondary,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(Icons.Outlined.Mic, null, Modifier.size(24.dp), tint = RlColor.Secondary)
            }
            Text(stringResource(when {
                checking -> R.string.scene_response_checking
                recording -> R.string.scene_response_listening
                else -> R.string.scene_response_your_turn
            }),
                Modifier.weight(1f), color = if (recording || checking) RlColor.Secondary else RlColor.TextSecondary,
                fontFamily = EditorialFont.Serif, fontSize = 15.sp, lineHeight = 21.sp)
        }
    }
}

/** Fixed torn edges and a stretchable texture centre keep the paper's safe area
 * constant even for very long transcripts; white text never sits on transparent edges. */
@Composable
private fun SceneResponsePaper(content: @Composable () -> Unit) {
    val paper = ImageBitmap.imageResource(R.drawable.rl_torn_blue)
    val edgePixels = 48
    val blueInk = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        1.19f, 0f, 0f, 0f, -48.45f,
        0f, 1.30f, 0f, 0f, -76.5f,
        0f, 0f, 1.436f, 0f, -111.18f,
        0f, 0f, 0f, 1f, 0f,
    )))
    Box(Modifier.fillMaxWidth().testTag("scene-response-blue-paper")) {
        Column(Modifier.matchParentSize()) {
            Image(BitmapPainter(paper, srcOffset = IntOffset.Zero,
                srcSize = IntSize(paper.width, edgePixels)), null,
                Modifier.fillMaxWidth().height(8.dp), contentScale = ContentScale.FillBounds, colorFilter = blueInk)
            Image(BitmapPainter(paper, srcOffset = IntOffset(0, edgePixels),
                srcSize = IntSize(paper.width, paper.height - edgePixels * 2)), null,
                Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.FillBounds, colorFilter = blueInk)
            Image(BitmapPainter(paper, srcOffset = IntOffset(0, paper.height - edgePixels),
                srcSize = IntSize(paper.width, edgePixels)), null,
                Modifier.fillMaxWidth().height(8.dp), contentScale = ContentScale.FillBounds, colorFilter = blueInk)
        }
        Canvas(Modifier.matchParentSize()) {
            val step = 16.dp.toPx()
            var y = 12.dp.toPx()
            while (y < size.height - 8.dp.toPx()) {
                drawCircle(Color(0xFFF2E8D8), 3.dp.toPx(), androidx.compose.ui.geometry.Offset(10.dp.toPx(), y))
                y += step
            }
        }
        Image(painterResource(R.drawable.rl_home_scene_paperclip), null,
            Modifier.align(Alignment.TopEnd).padding(end = 10.dp).offset(y = (-5).dp).size(width = 17.dp, height = 41.dp),
            contentScale = ContentScale.Fit)
        Box(Modifier.fillMaxWidth().padding(start = 26.dp, end = 38.dp, top = 16.dp, bottom = 16.dp)) { content() }
    }
}

@Composable
private fun SceneFilmStrip() {
    val photo = ImageBitmap.imageResource(R.drawable.rl_v73_learning_corridor_polaroid)
    Column(Modifier.fillMaxWidth().height(96.dp).background(Color(0xFFD8C9AF)).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        FilmPerforations()
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            val offsets = listOf(IntOffset(75, 230), IntOffset(300, 230), IntOffset(615, 240), IntOffset(260, 310))
            val sizes = listOf(IntSize(1020, 740), IntSize(765, 700), IntSize(400, 430), IntSize(400, 430))
            repeat(4) { index -> Image(BitmapPainter(photo, srcOffset = offsets[index], srcSize = sizes[index]), null,
                Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter) }
        }
        FilmPerforations()
    }
}

@Composable
private fun FilmPerforations() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
        repeat(18) { Box(Modifier.size(width = 5.dp, height = 4.dp).background(Color(0xFFF3E9D9))) }
    }
}
