package com.reallanguage.app.feature.recording.presentation

import com.reallanguage.app.feature.formation.presentation.EditorialSheet

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.automirrored.outlined.ArrowRightAlt
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.RlSpace

@Composable
fun RecordingTranscriptionScreen(
    state: RecordingTranscriptionUiState,
    onAction: (RecordingTranscriptionAction) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        RecordingEditorialScaffold(
            title = stringResource(R.string.recording_transcription_page_title),
            headerTag = "recording-transcription-header",
            artwork = { panel ->
                Box(panel.testTag("recording-transcription-artwork")) {
                    Image(painterResource(R.drawable.rl_v73_recording_poster), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                    Column(Modifier.fillMaxWidth(.7f).padding(start = 36.dp, top = 44.dp)) {
                        Text("本机录音", color = Color.White, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 27.sp)
                        Text(formatRecordingTime(state.request.durationSeconds), Modifier.padding(top = 20.dp),
                            color = Color.White, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 36.sp)
                    }
                }
            },
            actions = {
                TranscriptionFooter(onAction)
            },
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.recording_transcription_title), Modifier.fillMaxWidth(), color = RlColor.ActionVivid,
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold,
                    fontSize = 27.sp, lineHeight = 38.sp, textAlign = TextAlign.Center)
                Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null,
                    Modifier.size(width = 175.dp, height = 5.dp), contentScale = ContentScale.FillBounds)
                Text("请稍候，我们正在把你的录音整理成文字。", Modifier.fillMaxWidth(), color = RlColor.Ink,
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = 15.sp, lineHeight = 24.sp, textAlign = TextAlign.Center)
                TranscriptionStages(state.processing)
                if (state.processing) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).testTag("recording-transcription-live-progress"),
                    color = RlColor.ActionVivid, trackColor = RlColor.Border.copy(alpha = .6f))
            }
        }
    }
}

@Composable
private fun TranscriptionFooter(onAction: (RecordingTranscriptionAction) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 390.dp || LocalDensity.current.fontScale > 1.35f
        EditorialSheet(Modifier.fillMaxWidth(), contentPadding = 8.dp) {
            val note: @Composable (Modifier) -> Unit = { noteModifier ->
                Text("离开此页不会删除本机录音", noteModifier, color = RlColor.TextSecondary,
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = 13.sp, lineHeight = 22.sp)
            }
            val home: @Composable (Modifier) -> Unit = { buttonModifier ->
                com.reallanguage.app.core.designsystem.RlSecondaryButton("返回首页",
                    onClick = { onAction(RecordingTranscriptionAction.ReturnHomeRequested) },
                    modifier = buttonModifier.testTag("recording-transcription-home"))
            }
            if (stacked) {
                note(Modifier.fillMaxWidth())
                home(Modifier.fillMaxWidth())
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                note(Modifier.weight(1f))
                // The shared button fills its available width. Bound it before
                // measuring the weighted note so the note cannot receive 0 px.
                home(Modifier.width(132.dp))
            }
        }
    }
}

@Composable
private fun TranscriptionStages(processing: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 3.dp)) {
        val stacked = maxWidth < 320.dp || LocalDensity.current.fontScale > 1.35f
        // No per-stage receipts exist. Decorative rings never imply upload completion.
        if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TranscriptionStage(Icons.Outlined.CloudUpload, "上传录音", "等待确认", RlColor.Secondary, false, Modifier.fillMaxWidth())
            TranscriptionStage(Icons.Outlined.FindInPage, "识别内容", "等待结果", RlColor.ActionVivid, processing, Modifier.fillMaxWidth())
            TranscriptionStage(Icons.Outlined.Description, "生成文本", "等待结果", RlColor.Primary, false, Modifier.fillMaxWidth())
        } else {
            val segment = maxWidth / 3
            listOf(RlColor.Secondary, RlColor.Primary).forEachIndexed { index, color ->
                Icon(Icons.AutoMirrored.Outlined.ArrowRightAlt, null, tint = color.copy(alpha = .78f),
                    modifier = Modifier.offset(x = segment * (index + 1) - 28.dp, y = 20.dp).size(width = 56.dp, height = 24.dp))
            }
            Row(Modifier.fillMaxWidth()) {
                TranscriptionStage(Icons.Outlined.CloudUpload, "上传录音", "等待确认", RlColor.Secondary, false, Modifier.weight(1f))
                TranscriptionStage(Icons.Outlined.FindInPage, "识别内容", "等待结果", RlColor.ActionVivid, processing, Modifier.weight(1f))
                TranscriptionStage(Icons.Outlined.Description, "生成文本", "等待结果", RlColor.Primary, false, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TranscriptionStage(
    icon: ImageVector,
    title: String,
    status: String,
    tint: Color,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.testTag("recording-transcription-stage"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.rl_v73_recording_stage_ring), null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(tint))
            Icon(icon, null, tint = tint, modifier = Modifier.size(32.dp))
        }
        Text(title, color = if (active) tint else RlColor.Ink, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 16.sp, lineHeight = 23.sp, textAlign = TextAlign.Center)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (active) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = tint)
            Text(if (active) "  $status" else status, color = RlColor.TextSecondary,
                fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}
