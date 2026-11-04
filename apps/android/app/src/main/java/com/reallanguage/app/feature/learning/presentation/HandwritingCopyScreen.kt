package com.reallanguage.app.feature.learning.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.RlSpace

@Composable
fun HandwritingCopyScreen(
    state: HandwritingCopyUiState,
    onAction: (HandwritingCopyAction) -> Unit,
) {
    ChapterWritingFrame(
        chapter = state.chapter,
        currentFocusSentenceId = state.currentFocusSentenceId,
        stageTitle = stringResource(R.string.handwriting_title),
        onBack = { onAction(HandwritingCopyAction.BackRequested) },
        centerLeftPanelContent = true,
        leftPanelContent = {
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)
                    .testTag("handwriting-instruction-note"),
            ) {
                Image(
                    painter = painterResource(R.drawable.rl_torn_note),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.FillBounds,
                )
                Text(
                    "照着当前句\n完整抄写",
                    modifier = Modifier.padding(start = RlSpace.Space12, top = 14.dp),
                    color = RlColor.Ink,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        lineHeight = 25.sp,
                    ),
                )
            }
            Spacer(Modifier.weight(1f))
            Surface(
                modifier = Modifier.align(Alignment.CenterHorizontally)
                    .padding(top = RlSpace.Space4, bottom = 2.dp)
                    .testTag("handwriting-attempt-status-container"),
                color = RlColor.Primary.copy(alpha = .82f),
                shape = RlShape.Small,
            ) {
                Text(
                    "第 ${if (state.copyInProgress) state.confirmedCopyCount + 1 else state.confirmedCopyCount} 次 / 共 2 次",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        .testTag("handwriting-attempt-status"),
                    color = RlColor.OnPrimary,
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp, lineHeight = 14.sp),
                )
            }
        },
        footer = { HandwritingControls(state, onAction) },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().testTag("handwriting-workspace"),
            verticalArrangement = Arrangement.spacedBy(RlSpace.Space4),
        ) {
            if (state.saving) {
                Text(
                    stringResource(R.string.handwriting_saving),
                    color = RlColor.TextSecondary,
                )
            }
            if (state.saveFailed) {
                Text(stringResource(R.string.handwriting_save_failed), color = RlColor.Error)
            }
            key(state.canvasRevision) {
                val points = remember { mutableStateListOf<Offset?>() }
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .clipToBounds()
                        .testTag("handwriting-paper"),
                ) {
                    Image(
                        painter = painterResource(R.drawable.rl_ruled_handwriting_paper),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 22.dp)
                            .clipToBounds()
                            .testTag("handwriting-drawing-region"),
                    ) {
                        Canvas(
                            modifier = Modifier.fillMaxSize()
                                .clipToBounds()
                                .testTag("handwriting-canvas")
                                .pointerInput(state.copyInProgress) {
                                    if (state.copyInProgress) {
                                        fun clamped(offset: Offset) = Offset(
                                            x = offset.x.coerceIn(0f, size.width.toFloat()),
                                            y = offset.y.coerceIn(0f, size.height.toFloat()),
                                        )
                                        detectDragGestures(
                                            onDragStart = { offset ->
                                                points += null
                                                points += clamped(offset)
                                            },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                points += clamped(change.position)
                                            },
                                        )
                                    }
                                },
                        ) {
                            points.zipWithNext().forEach { (start, end) ->
                                if (start != null && end != null) {
                                    drawLine(
                                        color = RlColor.PrimaryPressed,
                                        start = start,
                                        end = end,
                                        strokeWidth = 5.dp.toPx(),
                                        cap = StrokeCap.Round,
                                    )
                                }
                            }
                        }
                    }
                    Image(
                        painter = painterResource(R.drawable.rl_diary_fountain_pen),
                        contentDescription = null,
                        modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd)
                            .width(78.dp).height(176.dp)
                            .padding(end = RlSpace.Space8, bottom = RlSpace.Space8),
                        contentScale = ContentScale.Fit,
                        alpha = .94f,
                    )
                }
            }
        }
    }
}

@Composable
private fun HandwritingControls(state: HandwritingCopyUiState, onAction: (HandwritingCopyAction) -> Unit) {
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val compact = maxWidth < 650.dp * LocalDensity.current.fontScale
    val controls: @Composable (Modifier) -> Unit = { controlsModifier ->
        Row(
                modifier = controlsModifier,
                horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                if (!state.copyInProgress) {
                    OutlinedButton(
                        onClick = { onAction(HandwritingCopyAction.CopyAgainRequested) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.handwriting_copy_again))
                    }
                }
                RlPrimaryButton(
                    text = stringResource(
                        if (state.copyInProgress) R.string.handwriting_finished
                        else R.string.handwriting_continue,
                    ),
                    enabled = if (state.copyInProgress) !state.saving
                    else state.minimumCompleted && !state.saving,
                    onClick = {
                        onAction(
                            if (state.copyInProgress) HandwritingCopyAction.WrittenFinished
                            else HandwritingCopyAction.ContinueRequested,
                        )
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                        .testTag("handwriting-finish"),
                    containerColor = RlColor.ActionVivid,
                )
            }
    }
    if (compact) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("手写只保留在当前页面，不上传、不识别、不判错。", color = RlColor.TextSecondary,
            fontSize = 12.sp, lineHeight = 17.sp)
        controls(Modifier.fillMaxWidth())
    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("手写只保留在当前页面，不上传、不识别、不判错。", Modifier.weight(1f), color = RlColor.TextSecondary,
            fontSize = 12.sp, lineHeight = 17.sp)
        controls(Modifier.weight(.7f))
    }
  }
}
