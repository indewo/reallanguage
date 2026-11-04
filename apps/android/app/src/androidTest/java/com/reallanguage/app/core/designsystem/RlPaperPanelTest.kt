package com.reallanguage.app.core.designsystem

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RlPaperPanelTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun defaultPanelKeepsTopAccent() {
        composeRule.setContent {
            RlTheme {
                RlPaperPanel(modifier = Modifier.size(300.dp, 180.dp).testTag("paper-panel")) {
                    Text("纸面正文")
                }
            }
        }
        composeRule.onNodeWithText("纸面正文").assertIsDisplayed()
        val image = composeRule.onNodeWithTag("paper-panel").captureToImage()
        assertTrue("Default paper panel must keep its existing terracotta accent", accentRedFraction(image) > .8f)
    }

    @Test fun disablingTopAccentRemovesOnlyItsPixelsAndKeepsContentInteractive() {
        val showTopAccent = mutableStateOf(true)
        var clicks = 0
        composeRule.setContent {
            RlTheme {
                RlPaperPanel(
                    modifier = Modifier.size(300.dp, 180.dp).testTag("paper-panel"),
                    showTopAccent = showTopAccent.value,
                ) {
                    Text("纸面正文")
                    Button(onClick = { clicks += 1 }) { Text("继续") }
                }
            }
        }
        val decorated = composeRule.onNodeWithTag("paper-panel").captureToImage()
        assertTrue("The starting state must actually include the accent", accentRedFraction(decorated) > .8f)

        composeRule.runOnIdle { showTopAccent.value = false }
        val undecorated = composeRule.onNodeWithTag("paper-panel").captureToImage()
        assertTrue("Disabling the accent must remove the red overlay from its exact region", accentRedFraction(undecorated) < .01f)
        assertOutsideAccentUnchanged(decorated, undecorated)
        composeRule.onNodeWithText("纸面正文").assertIsDisplayed()
        composeRule.onNodeWithText("继续").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    private fun accentRedFraction(image: ImageBitmap): Float {
        val pixels = image.toPixelMap()
        // The existing accent is 76dp wide, 18dp tall, and inset 32dp from the right.
        // Sample its interior only, leaving the torn edge and all text out of the assertion.
        val left = (image.width * 200f / 300f).toInt()
        val right = (image.width * 260f / 300f).toInt()
        val top = (image.height * 4f / 180f).toInt()
        val bottom = (image.height * 14f / 180f).toInt()
        var redPixels = 0
        var sampledPixels = 0
        for (y in top until bottom) for (x in left until right) {
            val pixel = pixels[x, y]
            if (pixel.red > .4f && pixel.red > pixel.green * 1.35f && pixel.red > pixel.blue * 1.35f) {
                redPixels += 1
            }
            sampledPixels += 1
        }
        assertTrue("The accent interior must have a non-empty sample", sampledPixels > 0)
        return redPixels.toFloat() / sampledPixels
    }

    private fun assertOutsideAccentUnchanged(before: ImageBitmap, after: ImageBitmap) {
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        val beforePixels = before.toPixelMap()
        val afterPixels = after.toPixelMap()
        // Allow one pixel around the decoration bounds for rasterization. Every other
        // pixel, including the existing spine, body, button, and built-in beige tape, stays.
        val left = (before.width * 192f / 300f).toInt() - 1
        val right = (before.width * 268f / 300f).toInt() + 1
        val bottom = (before.height * 18f / 180f).toInt() + 1
        for (y in 0 until before.height) for (x in 0 until before.width) {
            if (x in left..right && y <= bottom) continue
            assertEquals("Unexpected visual change outside the accent at $x,$y", beforePixels[x, y], afterPixels[x, y])
        }
    }
}
