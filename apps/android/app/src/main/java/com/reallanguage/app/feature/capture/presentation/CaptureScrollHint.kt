package com.reallanguage.app.feature.capture.presentation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollIndicatorState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.reallanguage.app.core.designsystem.RlColor

/** A single thumb, proportional to the native scroll extent and visible only during movement. */
@Composable
internal fun CaptureScrollHint(indicator: ScrollIndicatorState?, scrolling: Boolean, modifier: Modifier) {
    val alpha by animateFloatAsState(
        if (scrolling) .56f else 0f,
        tween(durationMillis = if (scrolling) 90 else 220, delayMillis = if (scrolling) 0 else 200),
        label = "内容滚动提示",
    )
    Canvas(modifier) {
        if (alpha <= 0f || indicator == null) return@Canvas
        val full = indicator.contentSize.toFloat()
        val visible = indicator.viewportSize.toFloat()
        if (visible <= 0 || full <= visible) return@Canvas
        val thumb = size.height * visible / full
        val fraction = (indicator.scrollOffset / (full - visible)).coerceIn(0f, 1f)
        drawRoundRect(RlColor.BorderStrong.copy(alpha = alpha),
            topLeft = Offset(0f, (size.height - thumb) * fraction), size = Size(size.width, thumb),
            cornerRadius = CornerRadius(size.width / 2))
    }
}
