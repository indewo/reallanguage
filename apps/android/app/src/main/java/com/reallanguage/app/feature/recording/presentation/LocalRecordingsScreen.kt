package com.reallanguage.app.feature.recording.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.*
import com.reallanguage.app.feature.formation.presentation.EditorialSheet
import java.text.DateFormat
import java.util.Date

@Composable
fun LocalRecordingsScreen(state: LocalRecordingsUiState, onAction: (LocalRecordingsAction) -> Unit) {
    BackHandler { onAction(LocalRecordingsAction.ReturnAccountRequested) }
    RecordingEditorialScaffold(
        title = stringResource(R.string.local_recordings_title),
        onBack = { onAction(LocalRecordingsAction.ReturnAccountRequested) },
        paperBody = false,
        artwork = { panel ->
            Box(panel) {
                Image(painterResource(R.drawable.rl_v73_recording_poster), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                Column(Modifier.fillMaxWidth(.78f).padding(start = 34.dp, top = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("本机录音", color = RlColor.OnPrimary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("把真实发生的事\n先说下来", color = RlColor.OnPrimary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 18.sp, lineHeight = 28.sp)
                }
            }
        },
        actions = {
            RlSecondaryButton(stringResource(R.string.ai_billing_return_account),
                onClick = { onAction(LocalRecordingsAction.ReturnAccountRequested) }, modifier = Modifier.fillMaxWidth())
        },
    ) {
        Text("保存在这台设备", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        when {
            state.loading -> CircularProgressIndicator(color = RlColor.Primary)
            state.loadFailed -> EditorialSheet {
                Text("暂时无法读取本机录音", color = RlColor.Error)
                RlSecondaryButton(stringResource(R.string.diary_retry), onClick = { onAction(LocalRecordingsAction.RetryRequested) })
            }
            state.recordings.isEmpty() -> EditorialSheet(contentPadding = 24.dp) {
                Text(stringResource(R.string.local_recordings_empty), color = RlColor.TextSecondary, fontSize = 17.sp, lineHeight = 27.sp)
            }
            else -> state.recordings.forEachIndexed { index, recording ->
                EditorialSheet(Modifier.fillMaxWidth().clickable { onAction(LocalRecordingsAction.RecordingRequested(recording.recordingId)) },
                    contentPadding = 18.dp) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Outlined.MicNone, null, tint = RlColor.ActionVivid, modifier = Modifier.size(32.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("录音 " + (index + 1), color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 21.sp)
                            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(recording.createdAtEpochMillis)),
                                color = RlColor.TextSecondary, fontSize = 13.sp, lineHeight = 21.sp)
                        }
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, "打开录音", tint = RlColor.Primary)
                    }
                }
            }
        }
        Text("录音只保存在本机，可稍后继续处理。", color = RlColor.TextSecondary, fontSize = 13.sp, lineHeight = 22.sp)
    }
}
