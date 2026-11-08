package com.reallanguage.app.feature.recording.presentation

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.EditorialFont
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.editorialUnderlinePainter
import com.reallanguage.app.feature.home.presentation.HomeAction
import com.reallanguage.app.feature.home.presentation.HomeUiState

@Suppress("UNUSED_PARAMETER")
@Composable
fun ActiveRecordingScreen(
    state: ActiveRecordingUiState,
    onAction: (ActiveRecordingAction) -> Unit,
    homeState: HomeUiState = HomeUiState(),
    onHomeAction: (HomeAction) -> Unit = {},
    onBack: (() -> Unit)? = null,
    transcribing: Boolean = false,
) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    RecordingCapturePage(state, onAction, onBack ?: { dispatcher?.onBackPressed(); Unit }, transcribing)
}

/** Both capture and transcription occupy the same layout, including the frozen timer and waveform. */
@Composable
fun RecordingCapturePage(
    state: ActiveRecordingUiState,
    onAction: (ActiveRecordingAction) -> Unit,
    onBack: () -> Unit,
    transcribing: Boolean = false,
    transcriptionFailure: String? = null,
    onRetryTranscription: () -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Image(painterResource(R.drawable.rl_long_recording_page), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        val headHeight = (maxHeight * .16f).coerceIn(48.dp, 82.dp)
        val timerSize = (maxHeight.value * .16f).coerceIn(32f, 93f)
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = maxWidth * .087f)) {
            Box(Modifier.fillMaxWidth().heightIn(min = headHeight)) {
                IconButton(onClick = onBack, Modifier.align(Alignment.CenterStart), enabled = !transcribing && !state.stopping) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = RlColor.Ink)
                }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("录音", color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold,
                        fontSize = (headHeight.value * .42f).coerceIn(19f, 31f).sp)
                    Image(editorialUnderlinePainter(), null, Modifier.width(headHeight * 1.3f).height(4.dp), contentScale = ContentScale.FillBounds)
                }
            }
            HorizontalDivider(color = RlColor.Border.copy(alpha = .62f))
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                val status = when {
                    transcriptionFailure != null -> "转译未完成"
                    transcribing || state.stopping -> "转译中"
                    state.error != null -> "录音遇到问题"
                    state.paused -> "已暂停"
                    state.recording -> "录音中"
                    else -> "准备录音"
                }
                Surface(shape = CircleShape, color = if (transcribing || state.stopping) Color(0xFF667F89) else Color(0xFF73865E)) {
                    Row(Modifier.padding(horizontal = 13.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (state.recording && !state.paused && !state.stopping && !transcribing) {
                            Box(Modifier.size(8.dp).background(RlColor.ActionVivid, CircleShape))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(status, fontFamily = EditorialFont.Serif, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(formatRecordingTime(state.elapsedSeconds), Modifier.testTag("active-recording-timer"),
                    style = MaterialTheme.typography.displayLarge.copy(fontFamily = EditorialFont.Serif,
                        fontWeight = FontWeight.SemiBold, color = RlColor.Ink,
                        fontSize = timerSize.sp, lineHeight = (timerSize * 1.18f).sp))
                Text("单次录音上限 60 分钟", color = RlColor.TextSecondary, fontFamily = EditorialFont.Serif, fontSize = 14.sp)
                Spacer(Modifier.height((headHeight * .16f).coerceAtLeast(4.dp)))
                RecordingSignalStrip(state.levels, Modifier.fillMaxWidth().height(headHeight * .95f))
                if (transcriptionFailure != null) Text(transcriptionFailure, color = RlColor.TextSecondary, fontSize = 13.sp)

            }
            val scale = LocalDensity.current.fontScale
            Row(Modifier.align(Alignment.CenterHorizontally).fillMaxWidth(if (scale > 1.3f) 1f else .75f)
                .padding(bottom = (headHeight * .32f).coerceAtMost(23.dp)),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val enabled = state.recording && !state.stopping && !transcribing
                OutlinedButton(
                    onClick = { onAction(ActiveRecordingAction.TogglePauseRequested) }, enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("active-recording-pause"),
                    border = BorderStroke(1.dp, if (enabled) RlColor.Ink else RlColor.Border),
                    shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(6.dp),
                ) {
                    Icon(if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (state.paused) "继续录音" else "暂停录音", fontFamily = EditorialFont.Serif, fontSize = 16.sp)
                }
                val retry = state.error != null && !transcribing && !state.stopping
                val busy = transcribing || state.stopping
                Button(
                    onClick = {
                        if (transcriptionFailure != null) onRetryTranscription()
                        else onAction(if (retry) ActiveRecordingAction.RetryRequested else ActiveRecordingAction.StopRequested)
                    },
                    enabled = enabled || retry || transcriptionFailure != null,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .testTag(if (retry) "active-recording-retry" else "active-recording-stop"),
                    shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RlColor.ActionVivid, contentColor = Color.White,
                        disabledContainerColor = if (busy) RlColor.ActionVivid else RlColor.Border, disabledContentColor = Color.White),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    else if (!retry) Icon(Icons.Filled.Stop, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(when {
                        transcriptionFailure != null -> "重新转译"
                        busy -> "正在转译文字"
                        retry -> "重试"
                        else -> "结束并转译文字"
                    }, Modifier.testTag("active-recording-stop-label"), fontFamily = EditorialFont.Serif, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun RecordingSignalStrip(levels: List<Float>, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.rl_long_recording_wave_paper), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Canvas(Modifier.fillMaxWidth(.92f).fillMaxHeight(.74f)) {
            val capacity = 128
            val step = size.width / capacity
            val mid = size.height / 2
            val start = capacity - levels.size.coerceAtMost(capacity)
            for (index in 0 until capacity) {
                val level = levels.getOrNull(index - start) ?: 0f
                val half = (level * mid * .90f).coerceAtLeast(.44.dp.toPx())
                val x = (index + .5f) * step
                drawLine(Color(0xFF527995), Offset(x, mid-half), Offset(x, mid+half),
                    strokeWidth = (step * .44f).coerceAtMost(2.2.dp.toPx()), cap = StrokeCap.Round)
            }
        }
    }
}

internal fun formatRecordingTime(totalSeconds: Int): String =
    com.reallanguage.app.core.designsystem.formatAudioCaptureTime(totalSeconds)
