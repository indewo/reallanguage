package com.reallanguage.app.feature.learning.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.*
import androidx.compose.ui.text.font.FontFamily
import com.reallanguage.app.core.designsystem.EditorialFont
import androidx.compose.ui.unit.sp
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.RlSpace

@Composable
fun ReadWriteCorrectionScreen(
    state: ReadWriteCorrectionUiState,
    onAction: (ReadWriteCorrectionAction) -> Unit,
) {
    ChapterWritingFrame(state.chapter, state.currentFocusSentenceId, stageTitle = "读写订正",
        onBack = { onAction(ReadWriteCorrectionAction.BackRequested) },
        leftPanelContent = {
            Text("订正提示", color = androidx.compose.ui.graphics.Color.White, fontSize = 20.sp, fontFamily = EditorialFont.Serif)
            state.lockedHintItems.forEach { Text(it, color = androidx.compose.ui.graphics.Color.White, fontSize = 15.sp) }
        },
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LearningAction(stringResource(R.string.formation_back), { onAction(ReadWriteCorrectionAction.BackRequested) }, Modifier.weight(1f), enabled = !state.saving)
                LearningAction(stringResource(if (state.audioLooping) R.string.learning_mute else R.string.learning_pronounce),
                    { onAction(ReadWriteCorrectionAction.AudioToggled) }, Modifier.weight(1f), enabled = !state.saving)
                LearningAction(stringResource(R.string.handwriting_finished), { onAction(ReadWriteCorrectionAction.WrittenFinished) },
                    Modifier.weight(1.4f), enabled = !state.saving, primary = true)
            }
        }) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(RlSpace.Space12)) {
            val points = remember { mutableStateListOf<Offset?>() }
            Canvas(
                Modifier.fillMaxWidth().weight(1f)
                    .pointerInput(state.saving) {
                        if (!state.saving) {
                            detectDragGestures(
                                onDragStart = { points += null; points += it },
                                onDrag = { change, _ -> change.consume(); points += change.position },
                            )
                        }
                    },
            ) {
                points.zipWithNext().forEach { (start, end) ->
                    if (start != null && end != null) {
                        drawLine(
                            RlColor.TextPrimary,
                            start,
                            end,
                            5.dp.toPx(),
                            StrokeCap.Round,
                        )
                    }
                }
            }
            if (state.saveFailed) {
                Text(stringResource(R.string.read_write_correction_save_failed), color = RlColor.Error)
            }
            if (state.audioFailed) {
                Text(stringResource(R.string.learning_audio_failed), color = RlColor.Error)
            }
        }
    }
}
