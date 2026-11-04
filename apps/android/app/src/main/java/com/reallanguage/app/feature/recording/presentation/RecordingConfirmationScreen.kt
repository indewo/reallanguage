package com.reallanguage.app.feature.recording.presentation

import com.reallanguage.app.feature.formation.presentation.EditorialSheet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.RlSpace

@Composable
fun RecordingConfirmationScreen(state: RecordingConfirmationUiState, onAction: (RecordingConfirmationAction) -> Unit) {
    BackHandler { onAction(RecordingConfirmationAction.BackRequested) }
    RecordingEditorialScaffold(
        title = stringResource(R.string.recording_confirmation_page_title),
        headerTag = "recording-confirmation-header",
        onBack = { onAction(RecordingConfirmationAction.BackRequested) },
        artwork = { modifier ->
            RecordingCompletedArtwork(state.recording?.durationSeconds ?: 0, state.playing, state.playbackBusy,
                { onAction(RecordingConfirmationAction.PreviewRequested) }, modifier)
        },
        actions = {
            if (state.recording != null && !state.loading) {
                OutlinedButton(onClick = { onAction(RecordingConfirmationAction.DeleteRequested) },
                    modifier = Modifier.weight(.85f).heightIn(min = 46.dp).testTag("recording-confirmation-delete"),
                    border = BorderStroke(1.dp, RlColor.Error), shape = RlShape.Input) {
                    Text(stringResource(R.string.recording_confirmation_delete), color = RlColor.Error)
                }
                RlPrimaryButton(stringResource(R.string.recording_confirmation_transcribe), state.recording.endReason != null,
                    { onAction(RecordingConfirmationAction.TranscribeRequested) },
                    Modifier.weight(1.15f).heightIn(min = 46.dp).testTag("recording-confirmation-transcribe"), containerColor = RlColor.ActionVivid)
                OutlinedButton(onClick = { onAction(RecordingConfirmationAction.ReturnHomeRequested) },
                    modifier = Modifier.weight(.85f).heightIn(min = 46.dp).testTag("recording-confirmation-home"), shape = RlShape.Input) {
                    Text(stringResource(R.string.incomplete_learning_return))
                }
            }
        },
    ) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            state.error == RecordingConfirmationError.LOAD_FAILED -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("暂时无法读取这段录音", color = RlColor.ActionVivid,
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 34.sp)
                Text("请重试读取，或返回后稍后处理。", color = RlColor.TextSecondary, fontSize = 15.sp, lineHeight = 23.sp)
                OutlinedButton(onClick = { onAction(RecordingConfirmationAction.RetryRequested) }, modifier = Modifier.heightIn(min = 46.dp)) {
                    Text(stringResource(R.string.diary_retry), fontSize = 16.sp, lineHeight = 24.sp)
                }
            }
            state.recording != null -> {
                Text(stringResource(R.string.recording_confirmation_title), color = RlColor.ActionVivid,
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 37.sp)
                Text("你可以先试听，再决定如何继续。", color = RlColor.Ink, fontSize = 15.sp, lineHeight = 23.sp)
                ConfirmationFact(Icons.Outlined.Save, "原始录音仍保存在本机", RlColor.Secondary)
                ConfirmationFact(Icons.Outlined.CloudUpload, "开始转写后将发送到处理服务", RlColor.ActionVivid)
                ConfirmationFact(Icons.Outlined.Home, "返回首页不会删除录音", RlColor.Primary)
                if (state.error == RecordingConfirmationError.PLAYBACK_FAILED) Text(stringResource(R.string.recording_confirmation_playback_failed), color = RlColor.Error)
                if (state.recording.endReason == null) Text("这份旧录音没有停止原因记录，可以试听；为避免错误截断，请重新录制后再转写。", color = RlColor.Error)
            }
        }
    }
}

@Composable
private fun RecordingCompletedArtwork(
    durationSeconds: Int,
    playing: Boolean,
    playbackBusy: Boolean,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.testTag("recording-confirmation-artwork")) {
        val compact = maxHeight < 260.dp
        Image(
            painter = painterResource(R.drawable.rl_v73_recording_poster),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 40.dp, end = 28.dp,
                top = if (compact) 16.dp else 46.dp, bottom = if (compact) 12.dp else 32.dp),
        ) {
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("recording-confirmation-audio-text")) {
            Text(
                "本机录音",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                formatRecordingTime(durationSeconds),
                modifier = Modifier.padding(top = RlSpace.Space20),
                color = Color.White,
                style = MaterialTheme.typography.displayMedium.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif),
            )
            }
            Surface(
                onClick = onPreview,
                enabled = !playbackBusy,
                modifier = Modifier.fillMaxWidth().padding(top = RlSpace.Space16).heightIn(min = 52.dp)
                    .testTag("recording-confirmation-preview"),
                color = RlColor.Surface.copy(alpha = .94f),
                shape = RlShape.Panel,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = RlSpace.Space12, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(modifier = Modifier.size(34.dp), color = RlColor.Primary, shape = androidx.compose.foundation.shape.CircleShape) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.PlayArrow, null, tint = Color.White)
                        }
                    }
                    Image(
                        painter = painterResource(R.drawable.rl_voice_waveform_strip),
                        contentDescription = null,
                        modifier = Modifier.weight(1f).height(32.dp),
                        contentScale = ContentScale.FillBounds,
                    )
                    Text(
                        stringResource(if (playing) R.string.recording_confirmation_stop_preview else R.string.recording_confirmation_preview),
                        color = RlColor.Primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfirmationFact(icon: ImageVector, text: String, tint: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("recording-confirmation-fact"),
        color = RlColor.Surface.copy(alpha = .72f),
        shape = RlShape.Small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = RlSpace.Space12),
            horizontalArrangement = Arrangement.spacedBy(RlSpace.Space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(modifier = Modifier.size(32.dp), color = tint, shape = RlShape.Panel) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White) }
            }
            Text(text, color = RlColor.Ink, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
