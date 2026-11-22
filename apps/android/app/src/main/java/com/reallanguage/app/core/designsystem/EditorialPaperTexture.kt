package com.reallanguage.app.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import com.reallanguage.app.R

private val QuietPaperEdge = GenericShape { size, _ ->
    val edge = 1.8f
    fun rough(i: Int) = edge * ((i * 37 + i * i * 11) % 17) / 16f
    moveTo(edge, edge)
    for (i in 1..80) lineTo(size.width * i / 80, rough(i))
    for (i in 1..25) lineTo(size.width - rough(i + 80), size.height * i / 25)
    for (i in 80 downTo 0) lineTo(size.width * i / 80, size.height - rough(i + 105))
    for (i in 25 downTo 0) lineTo(rough(i + 185), size.height * i / 25)
    close()
}

@Composable
fun Modifier.editorialPaper(color: Color = Color(0xFFF3E7D8)): Modifier {
    val texture = painterResource(R.drawable.rl_paper_background)
    // Decoration must never impose the bitmap's intrinsic size on wrap-content rows.
    return clip(QuietPaperEdge).drawBehind {
        drawRect(color)
        with(texture) { draw(size, alpha = .32f, colorFilter = ColorFilter.tint(color, BlendMode.Modulate)) }
    }
}
