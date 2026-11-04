package com.reallanguage.app.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RlActiveAudioCaptureControl(
    statusLabel: String,
    elapsedSeconds: Int,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    testTagPrefix: String = "active-recording",
    actionTestTag: String = "$testTagPrefix-action",
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Surface(color = RlColor.SecondaryContainer, shape = RlShape.Small) {
            Text(
                statusLabel,
                modifier = Modifier.padding(horizontal = RlSpace.Space16, vertical = 1.dp),
                color = RlColor.Ink,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
        Surface(
            modifier = Modifier.width(190.dp).height(42.dp).testTag("$testTagPrefix-active"),
            color = RlColor.Surface,
            shape = RlShape.Panel,
            border = BorderStroke(1.dp, RlColor.Border),
            shadowElevation = 2.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = RlSpace.Space8),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("●", color = RlColor.Recording, fontSize = 12.sp)
                Text(
                    formatAudioCaptureTime(elapsedSeconds),
                    modifier = Modifier.weight(1f).testTag("$testTagPrefix-timer"),
                    color = RlColor.Ink,
                    fontSize = 16.sp,
                    maxLines = 1,
                    softWrap = false,
                )
                Surface(
                    onClick = onAction,
                    enabled = actionEnabled,
                    modifier = Modifier.testTag(actionTestTag),
                    color = RlColor.ActionVivid,
                    contentColor = Color.White,
                    shape = RlShape.Panel,
                ) {
                    Text(
                        actionLabel,
                        modifier = Modifier.padding(horizontal = RlSpace.Space12, vertical = 5.dp),
                        fontSize = 16.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

fun formatAudioCaptureTime(totalSeconds: Int): String =
    "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
