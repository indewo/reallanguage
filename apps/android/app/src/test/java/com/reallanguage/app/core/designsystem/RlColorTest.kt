package com.reallanguage.app.core.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class RlColorTest {
    @Test
    fun approvedMorandiPaletteIsTheSingleThemeSource() {
        assertEquals(Color(0xFFF8F5EF), RlColor.Background)
        assertEquals(Color(0xFF58758F), RlColor.Primary)
        assertEquals(Color(0xFFB95A47), RlColor.ActionVivid)
        assertEquals(Color(0xFF607961), RlColor.Secondary)
        assertEquals(Color(0xFFB89A62), RlColor.NoteAccent)
        assertEquals(Color(0xFF253038), RlColor.TextPrimary)
    }

    @Test
    fun approvedForegroundPairsMeetNormalTextContrast() {
        listOf(
            RlColor.OnPrimary to RlColor.Primary,
            RlColor.OnActionVivid to RlColor.ActionVivid,
            RlColor.OnSecondary to RlColor.Secondary,
            RlColor.TextPrimary to RlColor.Background,
            RlColor.TextSecondary to RlColor.Background,
            RlColor.TextMuted to RlColor.Background,
            RlColor.TextPrimary to RlColor.NoteAccent,
        ).forEach { (foreground, background) ->
            assertTrue(
                "Expected contrast >= 4.5 for $foreground on $background",
                contrast(foreground, background) >= 4.5,
            )
        }
    }

    private fun contrast(first: Color, second: Color): Double {
        val lighter = maxOf(luminance(first), luminance(second))
        val darker = minOf(luminance(first), luminance(second))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Color): Double =
        0.2126 * linear(color.red) +
            0.7152 * linear(color.green) +
            0.0722 * linear(color.blue)

    private fun linear(channel: Float): Double =
        channel.toDouble().let {
            if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4)
        }
}
