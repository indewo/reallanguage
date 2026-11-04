package com.reallanguage.app.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RlLedgerWorkspaceViewportTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun optedInPaperAndAccentStayFixedWhileLongContentScrollsAndRemainsInteractive() {
        var clicks = 0
        composeRule.setContent {
            RlTheme {
                RlLedgerWorkspace("设置", "", "分组", "", fixedPaperViewport = true,
                    footer = { Text("固定操作区", Modifier.testTag("fixed-footer")) }) {
                    // Transparent, real layout content leaves the paper visible. No raster mock or AI.
                    Box(Modifier.height(1400.dp))
                    Button(onClick = { clicks++ }, modifier = Modifier.testTag("last-content-action")) { Text("确认") }
                }
            }
        }
        val scroll = composeRule.onNode(hasScrollAction())
        val before = scroll.captureToImage()
        val footerBefore = composeRule.onNodeWithTag("fixed-footer").fetchSemanticsNode().boundsInRoot
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 300f) }
        composeRule.waitForIdle()
        assertPaperRegionUnchanged(before, scroll.captureToImage())
        composeRule.onNodeWithTag("last-content-action").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, clicks)
        assertEquals(footerBefore, composeRule.onNodeWithTag("fixed-footer").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun existingCallersKeepTheirPreviousPaperScrollingUntilTheyOptIn() {
        composeRule.setContent {
            RlTheme {
                RlLedgerWorkspace("设置", "", "分组", "") { Box(Modifier.height(1400.dp)) }
            }
        }
        val scroll = composeRule.onNode(hasScrollAction())
        val before = scroll.captureToImage()
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 300f) }
        composeRule.waitForIdle()
        assertTrue("Default must preserve the previous moving paper until a page is explicitly migrated",
            changedPixels(before, scroll.captureToImage()) > 20)
    }

    private fun assertPaperRegionUnchanged(before: ImageBitmap, after: ImageBitmap) {
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        assertEquals("The exact paper/decoration pixels must not travel with blank body content", 0, changedPixels(before, after))
    }

    private fun changedPixels(before: ImageBitmap, after: ImageBitmap): Int {
        val a = before.toPixelMap(); val b = after.toPixelMap()
        var changed = 0
        // Interior band excludes viewport clipping/rounding pixels; no text or button intersects it.
        for (y in 4 until minOf(80, before.height - 4)) for (x in 4 until before.width - 4) {
            if (a[x, y] != b[x, y]) changed++
        }
        return changed
    }
}
