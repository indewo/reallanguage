package com.reallanguage.app.feature.learning.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import com.reallanguage.app.core.designsystem.EditorialFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.RlSpace
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import kotlinx.coroutines.isActive

/** The current sentence stays in its original row; a held finger reveals overflow in that row. */
@Composable
internal fun ReadSpeakWordTrack(
    sentence: FixedLearningSentence,
    listening: Boolean,
    highlightedWordCount: Int,
    modifier: Modifier,
    onAction: (ReadSpeakAction) -> Unit,
) {
    val words = remember(sentence.textEn) { sentence.textEn.split(Regex("\\s+")) }
    val scroll = rememberScrollState()
    val starts = remember(words) { FloatArray(words.size) { Float.POSITIVE_INFINITY } }
    val ends = remember(words) { FloatArray(words.size) { Float.POSITIVE_INFINITY } }
    val density = LocalDensity.current
    val paddingPx = with(density) { RlSpace.Space8.toPx() }
    val edgePx = paddingPx * 4f
    var viewportWidth by remember { mutableStateOf(0) }
    var visibleBounds by remember { mutableStateOf(Rect.Zero) }
    var pointer by remember(words) { mutableStateOf<Offset?>(null) }
    val lastNotified = remember(words, listening) { intArrayOf(highlightedWordCount - 1) }
    val latestHighlight by rememberUpdatedState(highlightedWordCount)
    val latestAction by rememberUpdatedState(onAction)
    val latestListening by rememberUpdatedState(listening)
    val notifyPassed: (Float) -> Unit = { visibleX ->
        val contentX = visibleX + scroll.value
        val last = words.indices.lastOrNull { index ->
            contentX >= starts[index] &&
                (index < words.lastIndex || ends[index] <= scroll.value + visibleBounds.right)
        } ?: -1
        val first = maxOf(lastNotified[0] + 1, latestHighlight)
        if (last >= first) {
            lastNotified[0] = last
            for (index in first..last) latestAction(ReadSpeakAction.WordReached(index))
        }
    }
    val latestNotify by rememberUpdatedState(notifyPassed)
    LaunchedEffect(sentence.sentenceId, listening) {
        if (listening) scroll.scrollTo(0)
    }
    val edgeDirection = when {
        pointer == null || viewportWidth == 0 || !visibleBounds.contains(pointer!!) -> 0
        pointer!!.x >= visibleBounds.right - edgePx -> 1
        pointer!!.x <= visibleBounds.left + edgePx -> -1
        else -> 0
    }
    LaunchedEffect(listening, edgeDirection, words) {
        if (!listening || edgeDirection == 0) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (isActive) {
            val frame = withFrameNanos { it }
            val position = pointer ?: break
            if (!visibleBounds.contains(position)) break
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, .05f)
            previousFrame = frame
            val moved = scroll.scrollBy(edgeDirection * paddingPx * 30f * seconds)
            latestNotify(position.x)
            if (moved == 0f) break
        }
    }
    Row(
        modifier = modifier.heightIn(min = 48.dp)
            .onSizeChanged { viewportWidth = it.width }
            .onGloballyPositioned { coordinates ->
                val clipped = coordinates.findRootCoordinates()
                    .localBoundingBoxOf(coordinates, clipBounds = true)
                visibleBounds = clipped.translate(-coordinates.localToRoot(Offset.Zero))
            }
            .pointerInput(sentence.sentenceId, sentence.textEn) {
                fun track(position: Offset) {
                    pointer = position.takeIf { visibleBounds.contains(it) }
                    pointer?.let { latestNotify(it.x) }
                }
                detectHorizontalDragGestures(
                    onDragStart = { position ->
                        if (!latestListening) latestAction(ReadSpeakAction.StartRequested)
                        track(position)
                    },
                    onDragEnd = { pointer = null },
                    onDragCancel = { pointer = null },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        track(change.position)
                    },
                )
            }
            .horizontalScroll(scroll, enabled = false),
        horizontalArrangement = Arrangement.spacedBy(with(density) { 2.5.sp.toDp() }),
    ) {
        words.forEachIndexed { index, word ->
            Text(
                word,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = EditorialFont.Serif,
                    fontSize = 26.sp,
                    lineHeight = 35.sp,
                    letterSpacing = 0.sp,
                ),
                color = RlColor.Ink,
                modifier = Modifier
                    .onGloballyPositioned { coordinates ->
                        starts[index] = coordinates.positionInParent().x
                        ends[index] = coordinates.positionInParent().x + coordinates.size.width
                    }
                    .background(
                        if (index < highlightedWordCount) RlColor.ReadingWordHighlight else Color.Transparent,
                        RlShape.Input,
                    )
                    .testTag("read-speak-word-$index")
                    .clickable(enabled = listening) {
                        if (index < words.lastIndex || ends[index] <= scroll.value + visibleBounds.right) {
                            onAction(ReadSpeakAction.WordReached(index))
                        }
                    }
                    .padding(horizontal = RlSpace.Space4, vertical = RlSpace.Space8),
            )
        }
    }
}
