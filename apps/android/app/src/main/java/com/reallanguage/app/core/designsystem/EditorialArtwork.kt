package com.reallanguage.app.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.reallanguage.app.R

/** Use the painted ink bounds, not the transparent export canvas, when measuring the underline. */
@Composable
fun editorialUnderlinePainter(): BitmapPainter {
    val bitmap = ImageBitmap.imageResource(R.drawable.rl_handdrawn_terracotta_underline)
    return remember(bitmap) { BitmapPainter(bitmap, IntOffset(0, 128), IntSize(2062, 190)) }
}
