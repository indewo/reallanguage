package com.reallanguage.app.feature.recording.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.*

@Composable
fun RecordingDeletionDialog(
    state: RecordingDeletionUiState,
    durationSeconds: Int? = null,
    onAction: (RecordingDeletionAction) -> Unit,
) {
    Dialog(onDismissRequest = { if (!state.deleting) onAction(RecordingDeletionAction.CancelRequested) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        RlImmersiveDialogWindow(dimAmount = .57f)
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), contentAlignment = Alignment.Center) {
            val width = minOf(510.dp, maxWidth)
            val availableHeight = maxHeight
            Box(Modifier.width(width).heightIn(max = availableHeight)) {
                Image(painterResource(R.drawable.rl_v73_recording_delete_clean_paper), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                Column(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 65.dp, bottom = 36.dp)
                    .heightIn(max = (availableHeight - 101.dp).coerceAtLeast(90.dp)).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(15.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.recording_deletion_title), color = RlColor.ActionVivid,
                        fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 38.sp, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.recording_deletion_explanation), color = RlColor.Ink,
                        fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 23.sp, textAlign = TextAlign.Center)
                    Box(Modifier.fillMaxWidth().testTag("recording-deletion-summary")) {
                        Image(painterResource(R.drawable.rl_torn_note), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.MicNone, null, tint = RlColor.Secondary, modifier = Modifier.size(28.dp))
                            Text(if (durationSeconds == null) "本机录音" else "本机录音 · ${formatRecordingTime(durationSeconds)}",
                                color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 17.sp, lineHeight = 25.sp, modifier = Modifier.weight(1f))
                        }
                    }
                    Box(contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.rl_scene_abandon_warning_tape), null,
                            Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                        Text("此操作无法撤销", color = RlColor.Ink, fontFamily = EditorialFont.Serif,
                            fontSize = 13.sp, lineHeight = 20.sp,
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 7.dp).testTag("recording-deletion-warning"))
                    }
                    if (state.error != null) Text(stringResource(R.string.recording_deletion_failed), color = RlColor.Error, fontSize = 13.sp, lineHeight = 20.sp)
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        RlSecondaryButton(stringResource(R.string.recording_deletion_cancel), !state.deleting,
                            { onAction(RecordingDeletionAction.CancelRequested) }, Modifier.weight(1f).fillMaxHeight().testTag("recording-deletion-cancel"))
                        RlPrimaryButton(stringResource(R.string.recording_deletion_confirm), !state.deleting,
                            { onAction(RecordingDeletionAction.ConfirmRequested) }, Modifier.weight(1f).fillMaxHeight().testTag("recording-deletion-confirm"), containerColor = RlColor.ActionVivid)
                    }
                }
            }
        }
    }
}
