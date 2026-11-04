package com.reallanguage.app.feature.home.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor

/** The bitmap stays unchanged. Only its live labels and readable local viewport are composed. */
@Composable
internal fun MessagesArtwork(unreadCount: Int?, loading: Boolean, modifier: Modifier = Modifier) {
    val countText = when {
        unreadCount != null -> "$unreadCount 条新消息"
        loading -> stringResource(R.string.messages_loading)
        else -> "—"
    }
    BoxWithConstraints(modifier.testTag("messages-inbox-artwork")) {
        val viewportWidth = maxWidth
        val viewportHeight = maxHeight
        val density = LocalDensity.current
        val fitScale = minOf(viewportWidth / 1199f, viewportHeight / 1312f)
        // Keep the normal source-relative typography when the available artwork
        // becomes smaller. This does not override or cap the user's fontScale.
        val sourceRatio = (fitScale / (315.dp / 1199f)).coerceAtMost(1f)
        val titleSize = (26f * sourceRatio).coerceAtLeast(16f)
        val countSize = (21f * sourceRatio).coerceAtLeast(16f)
        val nominalTitleStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
            fontSize = titleSize.sp, lineHeight = (titleSize * 30f / 26f).sp).withPaperLineHeight()
        val nominalCountStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
            fontSize = countSize.sp, lineHeight = (countSize * 28f / 21f).sp).withPaperLineHeight()
        val measurer = rememberTextMeasurer()
        val titleStyle = measurer.calibrateBasePaperStyle("我的收件箱", nominalTitleStyle, density,
            width = fitScale * 770f, height = fitScale * 123f)
        val countStyle = measurer.calibrateBasePaperStyle(countText, nominalCountStyle, density,
            width = fitScale * 590f, height = fitScale * 163f)
        // A single base-size calibration above is followed by these final
        // measurements at the user's real fontScale. Enlarged text keeps that
        // multiplier and can still require the readable local scrolling canvas.
        val titleMeasure = measurer.measure("我的收件箱", titleStyle, softWrap = false)
        val countMeasure = measurer.measure(countText, countStyle, softWrap = false)
        val roundingGuard = with(density) { 1.toDp() }
        val minimumReadableScale = with(density) {
            maxOf(
                (titleMeasure.size.width.toDp() + 2.dp + roundingGuard) / 770f,
                (titleMeasure.size.height.toDp() + 2.dp) / 123f,
                (countMeasure.size.width.toDp() + 2.dp + roundingGuard) / 590f,
                (countMeasure.size.height.toDp() + 2.dp) / 163f,
            )
        }
        // If one line is taller than the fitted blue region, enlarge the *whole*
        // image/text canvas and pan locally. Do not scroll the halves of a glyph.
        val scale = maxOf(fitScale, minimumReadableScale)
        val canvasWidth = maxOf(viewportWidth, scale * 1199f)
        val canvasHeight = maxOf(viewportHeight, scale * 1312f)
        val imageLeft = (canvasWidth - scale * 1199f) / 2f
        val imageTop = (canvasHeight - scale * 1312f) / 2f
        val horizontal = rememberScrollState()
        val vertical = rememberScrollState()
        // Separate layout nodes preserve both accessibility ScrollBy actions.
        // Putting both modifiers on one node keeps both axis ranges but merges
        // their identically named action into just one direction.
        Box(Modifier.fillMaxSize().clipToBounds().horizontalScroll(horizontal)
            .testTag("messages-inbox-horizontal-scroll")) {
            Box(Modifier.width(canvasWidth).height(viewportHeight).verticalScroll(vertical)
                .testTag("messages-inbox-local-scroll")) {
                Box(Modifier.requiredSize(canvasWidth, canvasHeight).testTag("messages-inbox-canvas")) {
                    Image(painterResource(R.drawable.rl_messages_inbox_collage), null,
                        Modifier.fillMaxSize().testTag("messages-inbox-image"), contentScale = ContentScale.Fit)
                    MessagesArtworkLabel("我的收件箱", titleStyle, Modifier
                        .offset(x = imageLeft + scale * 290f, y = imageTop + scale * 205f)
                        .width(scale * 770f).height(scale * 123f))
                    MessagesArtworkLabel(countText, countStyle, Modifier
                        .offset(x = imageLeft + scale * 450f, y = imageTop + scale * 788f)
                        .width(scale * 590f).height(scale * 163f))
                }
            }
        }
        if (vertical.maxValue > 0) {
            val thumbHeight = thumbSize(viewportHeight, vertical, with(density) { viewportHeight.toPx() })
            val top = (viewportHeight - thumbHeight) * (vertical.value.toFloat() / vertical.maxValue)
            Box(Modifier.align(Alignment.CenterEnd).width(2.dp).height(viewportHeight)
                .background(RlColor.Primary.copy(alpha = .2f)).testTag("messages-inbox-vertical-scrollbar")
                .semantics { contentDescription = "上下滑动查看收件箱" })
            Box(Modifier.align(Alignment.TopEnd).offset(y = top).width(2.dp).height(thumbHeight)
                .background(RlColor.Primary))
        }
        if (horizontal.maxValue > 0) {
            val thumbWidth = thumbSize(viewportWidth, horizontal, with(density) { viewportWidth.toPx() })
            val left = (viewportWidth - thumbWidth) * (horizontal.value.toFloat() / horizontal.maxValue)
            Box(Modifier.align(Alignment.BottomStart).width(viewportWidth).height(2.dp)
                .background(RlColor.Primary.copy(alpha = .2f)).testTag("messages-inbox-horizontal-scrollbar")
                .semantics { contentDescription = "左右滑动查看收件箱" })
            Box(Modifier.align(Alignment.BottomStart).offset(x = left).width(thumbWidth).height(2.dp)
                .background(RlColor.Primary))
        }
    }
}

@Composable
private fun MessagesArtworkLabel(text: String, style: TextStyle, modifier: Modifier) {
    Box(modifier.padding(1.dp), contentAlignment = Alignment.Center) {
        Text(text, Modifier.fillMaxWidth(), color = Color.White, style = style, textAlign = TextAlign.Center)
    }
}

private fun TextStyle.withPaperLineHeight() = copy(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    // CJK fallback can remain taller than the declared line height; the actual
    // base-font measurement, not this declaration alone, determines paper fit.
    lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None, mode = LineHeightStyle.Mode.Fixed),
)

private fun TextMeasurer.calibrateBasePaperStyle(text: String, nominal: TextStyle, density: Density,
    width: Dp, height: Dp): TextStyle {
    // The previous 26sp title was an implementation estimate, not a confirmed
    // font specification. Derive its base size from the unchanged blank-paper
    // area using actual metrics. Never cancel the user's accessibility scale.
    val measured = measure(text, nominal, softWrap = false, density = Density(density.density, 1f))
    val ratio = with(density) {
        minOf(1f,
            (width.toPx() - 2.dp.toPx() - 1f).coerceAtLeast(1f) / measured.size.width.coerceAtLeast(1),
            (height.toPx() - 2.dp.toPx() - 1f).coerceAtLeast(1f) / measured.size.height.coerceAtLeast(1))
    }
    val fontSize = (nominal.fontSize.value * ratio).coerceAtLeast(16f)
    return nominal.copy(fontSize = fontSize.sp,
        lineHeight = (nominal.lineHeight.value * fontSize / nominal.fontSize.value).sp)
}

private fun thumbSize(viewport: Dp, state: ScrollState, viewportPixels: Float): Dp =
    (viewport * (viewportPixels / (viewportPixels + state.maxValue))).coerceAtLeast(8.dp).coerceAtMost(viewport)
