package com.reallanguage.app.feature.settings.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.reallanguage.app.R

/** Source 06 uses the visible paper edge as its layout boundary, not export padding. */
@Composable
internal fun voiceSettingPaperPainter(): BitmapPainter {
    val bitmap = ImageBitmap.imageResource(R.drawable.rl_torn_heading_label)
    return remember(bitmap) { BitmapPainter(bitmap, IntOffset(75, 136), IntSize(1915, 456)) }
}

@Composable
internal fun voiceWaveformPaperPainter(): BitmapPainter {
    val bitmap = ImageBitmap.imageResource(R.drawable.rl_voice_waveform_strip)
    return remember(bitmap) { BitmapPainter(bitmap, IntOffset(24, 158), IntSize(2128, 382)) }
}
