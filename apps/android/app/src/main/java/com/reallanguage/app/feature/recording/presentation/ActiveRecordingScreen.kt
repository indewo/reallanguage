package com.reallanguage.app.feature.recording.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.EditorialFont
import com.reallanguage.app.feature.home.presentation.HomeAction
import com.reallanguage.app.feature.home.presentation.HomeScreen
import com.reallanguage.app.feature.home.presentation.HomeUiState

@Composable
fun ActiveRecordingScreen(
    state: ActiveRecordingUiState,
    onAction: (ActiveRecordingAction) -> Unit,
    homeState: HomeUiState = HomeUiState(),
    onHomeAction: (HomeAction) -> Unit = {},
) {
    HomeScreen(homeState, onHomeAction, recordingContent = {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
            RecordingCaptureControl(
                statusLabel = "正在录音",
                elapsedSeconds = state.elapsedSeconds,
                actionLabel = androidx.compose.ui.res.stringResource(R.string.active_recording_stop),
                actionEnabled = state.recording && !state.stopping,
                onAction = { onAction(ActiveRecordingAction.StopRequested) },
            )
            if (state.error != null) {
                OutlinedButton(
                    onClick = { onAction(ActiveRecordingAction.RetryRequested) },
                    modifier = Modifier.testTag("active-recording-retry"),
                ) { Text(stringResource(R.string.diary_retry)) }
            }
        }
    })
}

/** Recording-only responsive control. The shared diary input control remains unchanged. */
@Composable
private fun RecordingCaptureControl(
    statusLabel: String,
    elapsedSeconds: Int,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    val controlWidth = (190.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)).coerceAtMost(250.dp)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Surface(color = RlColor.SecondaryContainer, shape = RlShape.Small) {
            Text(statusLabel, Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
                color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold,
                fontSize = 12.sp, lineHeight = 18.sp)
        }
        Surface(Modifier.width(controlWidth).heightIn(min = 48.dp).testTag("active-recording-active"),
            color = RlColor.Surface, shape = RlShape.Panel,
            border = BorderStroke(1.dp, RlColor.Border), shadowElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FiberManualRecord, null, tint = RlColor.Recording, modifier = Modifier.size(10.dp))
                Text(formatRecordingTime(elapsedSeconds), Modifier.weight(1f).testTag("active-recording-timer"),
                    color = RlColor.Ink, fontFamily = FontFamily.Monospace, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp)
                Surface(onClick = onAction, enabled = actionEnabled,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("active-recording-stop"),
                    color = RlColor.ActionVivid, contentColor = Color.White, shape = RlShape.Panel) {
                    Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                        Text(actionLabel, Modifier.testTag("active-recording-stop-label"), fontFamily = EditorialFont.Serif,
                            fontSize = 16.sp, lineHeight = 22.sp)
                    }
                }
            }
        }
    }
}

internal fun formatRecordingTime(totalSeconds: Int): String =
    com.reallanguage.app.core.designsystem.formatAudioCaptureTime(totalSeconds)
