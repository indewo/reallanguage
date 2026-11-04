package com.reallanguage.app.feature.recording.presentation

import com.reallanguage.app.feature.formation.presentation.EditorialSheet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.*

@Composable
fun TranscriptionFailureScreen(state: TranscriptionFailureUiState, onAction: (TranscriptionFailureAction) -> Unit) {
    BackHandler { onAction(TranscriptionFailureAction.ReturnRequested) }
    RecordingEditorialScaffold(
        title = "录音转写",
        headerTag = "transcription-failure-header",
        onBack = { onAction(TranscriptionFailureAction.ReturnRequested) },
        artwork = { FailureRecordingArtwork(state, it) },
        actions = {
            RlSecondaryButton(stringResource(R.string.transcription_failure_return),
                onClick = { onAction(TranscriptionFailureAction.ReturnRequested) },
                modifier = Modifier.weight(1f).testTag("transcription-failure-return"))
            RlPrimaryButton(stringResource(R.string.transcription_failure_retry), state.recordingId.isNotBlank(),
                { onAction(TranscriptionFailureAction.RetryRequested) },
                Modifier.weight(1.25f).testTag("transcription-failure-retry"), containerColor = RlColor.ActionVivid)
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(46.dp), tint = RlColor.ActionVivid)
            Text("转写失败", color = RlColor.ActionVivid, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 42.sp)
        }
        HorizontalDivider(color = RlColor.Border)
        Text(stringResource(R.string.transcription_failure_explanation), color = RlColor.Ink, fontSize = 17.sp, lineHeight = 28.sp)
        Surface(color = RlColor.NoteContainer, shape = RlShape.Small) {
            Text("录音未丢失", Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                color = RlColor.Primary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(stringResource(R.string.transcription_failure_preserved), color = RlColor.TextSecondary, fontSize = 14.sp, lineHeight = 23.sp)
    }
}

@Composable
private fun FailureRecordingArtwork(state: TranscriptionFailureUiState, modifier: Modifier) {
    Box(modifier.testTag("transcription-failure-recording")) {
        Image(
            painter = painterResource(R.drawable.rl_v73_recording_poster),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Column(
            Modifier.fillMaxSize().padding(start = 42.dp, top = 62.dp, end = 28.dp),
            verticalArrangement = Arrangement.spacedBy(RlSpace.Space12),
        ) {
            Text(
                "本机录音",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Image(
                painterResource(R.drawable.rl_voice_waveform_strip),
                null,
                Modifier.fillMaxWidth(.72f).height(42.dp),
                contentScale = ContentScale.FillBounds,
            )
            state.durationSeconds?.let {
                Text(
                    "%02d:%02d".format(it / 60, it % 60),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "原始录音仍保存在本机",
                Modifier.padding(bottom = 36.dp).testTag("transcription-failure-preserved"),
                color = RlColor.SecondaryContainer,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 22.sp),
            )
        }
    }
}
