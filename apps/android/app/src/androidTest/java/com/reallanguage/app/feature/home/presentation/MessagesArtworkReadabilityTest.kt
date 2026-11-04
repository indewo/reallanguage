package com.reallanguage.app.feature.home.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.home.domain.AppMessage
import com.reallanguage.app.feature.home.domain.AppMessageTarget
import com.reallanguage.app.feature.home.domain.MessageKind
import com.reallanguage.app.feature.home.domain.MessageTargetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The real system bars/cutout remain present; a clipped half-character is not readable scrolling. */
class MessagesArtworkReadabilityTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before fun landscapeWithRealBars() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            WindowCompat.setDecorFitsSystemWindows(it.window, false)
            WindowCompat.getInsetsController(it.window, it.window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
        composeRule.waitUntil(5_000) {
            composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    @Test fun normalFontInboxLabelsAreWholeWhenRealSystemInsetsShrinkTheAvailableArtwork() {
        render(915.5.dp, 429.5.dp, 1f)
        composeRule.waitForIdle()
        captureMessagesVisualEvidence("57-artwork-normal-real-bars")
        listOf("我的收件箱", "2 条新消息").forEach { text ->
            val interaction = composeRule.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
            val layout = layout(text)
            val visible = interaction.fetchSemanticsNode().boundsInRoot
            assertTrue("Normal-font [$text] must be fully visible, not half a line behind the envelope: " +
                "visible=$visible, textSize=${layout.size}, bottom=${layout.getLineBottom(layout.lineCount - 1)}",
                visible.height + 1f >= layout.getLineBottom(layout.lineCount - 1))
        }
    }

    @Test fun doubleFontArtworkCanExposeAtLeastOneWholeLineAndEachLabelWithoutMovingTheRightActions() {
        render(640.dp, 360.dp, 2f)
        val homeBefore = composeRule.onNodeWithTag("messages-return-home").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        listOf("我的收件箱", "2 条新消息").forEach { text ->
            val interaction = composeRule.onNodeWithText(text, useUnmergedTree = true)
            val layout = layout(text)
            val scroll = verticalScrollAncestor(interaction.fetchSemanticsNode())
            val maximumLineHeight = (0 until layout.lineCount).maxOf { layout.getLineBottom(it) - layout.getLineTop(it) }
            assertTrue("[$text] cannot be made readable by scrolling the top/bottom halves of characters: " +
                "viewport=${scroll.boundsInRoot}, requiredLine=$maximumLineHeight, textSize=${layout.size}",
                scroll.boundsInRoot.height + 1f >= maximumLineHeight)
            interaction.performScrollTo().assertIsDisplayed()
        }
        composeRule.waitForIdle()
        captureMessagesVisualEvidence("57-artwork-short-font2-real-bars")
        assertEquals("Scrolling the left artwork must not move the persistent right action", homeBefore,
            composeRule.onNodeWithTag("messages-return-home").assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
    }

    @Test fun doubleFontArtworkEachSemanticAxisConsumesOneBoundedScrollAction() {
        render(640.dp, 360.dp, 2f)
        composeRule.waitForIdle()
        val homeBefore = composeRule.onNodeWithTag("messages-return-home").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        composeRule.mainClock.autoAdvance = false
        try {
            val before = artworkScrollSnapshot()
            assertTrue("Fixture must overflow both axes: $before", before.horizontalMaximum > 80f && before.verticalMaximum > 80f)

            // Exactly two actions, with a finite clock advance after each. Do not
            // use performScrollTo here: it can retry forever if an axis advertises
            // a range but its merged ScrollBy action does not consume that axis.
            performOneArtworkScroll(SemanticsProperties.VerticalScrollAxisRange, 0f, 80f)
            val afterVertical = artworkScrollSnapshot()
            performOneArtworkScroll(SemanticsProperties.HorizontalScrollAxisRange, 80f, 0f)
            val afterHorizontal = artworkScrollSnapshot()

            val verticalConsumed = afterVertical.vertical > before.vertical &&
                afterVertical.canvasPosition.y < before.canvasPosition.y &&
                kotlin.math.abs(afterVertical.horizontal - before.horizontal) <= 1f
            val horizontalConsumed = afterHorizontal.horizontal > afterVertical.horizontal &&
                afterHorizontal.canvasPosition.x < afterVertical.canvasPosition.x &&
                kotlin.math.abs(afterHorizontal.vertical - afterVertical.vertical) <= 1f
            assertTrue("Each advertised axis must actually move its own content: before=$before, " +
                "afterVertical=$afterVertical, afterHorizontal=$afterHorizontal", verticalConsumed && horizontalConsumed)
            assertEquals("Local accessibility scrolling must not move the right action", homeBefore,
                composeRule.onNodeWithTag("messages-return-home").assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun performOneArtworkScroll(key: SemanticsPropertyKey<ScrollAxisRange>, x: Float, y: Float) {
        val ancestor = artworkAxisAncestor(key)
        composeRule.runOnIdle {
            assertTrue("The existing axis must accept its scroll action", requireNotNull(ancestor.config[SemanticsActions.ScrollBy].action)(x, y))
        }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
    }

    private fun artworkScrollSnapshot(): ArtworkScrollSnapshot {
        val horizontal = artworkAxisAncestor(SemanticsProperties.HorizontalScrollAxisRange)
        val vertical = artworkAxisAncestor(SemanticsProperties.VerticalScrollAxisRange)
        val canvas = composeRule.onNodeWithTag("messages-inbox-canvas", useUnmergedTree = true).fetchSemanticsNode()
        return composeRule.runOnIdle {
            val horizontalRange = horizontal.config[SemanticsProperties.HorizontalScrollAxisRange]
            val verticalRange = vertical.config[SemanticsProperties.VerticalScrollAxisRange]
            ArtworkScrollSnapshot(horizontalRange.value(), horizontalRange.maxValue(),
                verticalRange.value(), verticalRange.maxValue(), canvas.positionInRoot)
        }
    }

    private fun artworkAxisAncestor(key: SemanticsPropertyKey<ScrollAxisRange>): SemanticsNode {
        var current = composeRule.onNodeWithText("我的收件箱", useUnmergedTree = true).fetchSemanticsNode().parent
        while (current != null) {
            if (current.config.contains(key)) return current
            current = current.parent
        }
        error("The enlarged artwork must expose $key")
    }

    private data class ArtworkScrollSnapshot(
        val horizontal: Float,
        val horizontalMaximum: Float,
        val vertical: Float,
        val verticalMaximum: Float,
        val canvasPosition: Offset,
    )

    private fun layout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        val result = results.single()
        assertTrue(result.lineCount > 0)
        assertEquals(text.length, result.getLineEnd(result.lineCount - 1))
        (0 until result.lineCount).forEach { assertTrue("[$text] must never be ellipsized", !result.isLineEllipsized(it)) }
        return result
    }

    private fun verticalScrollAncestor(node: SemanticsNode): SemanticsNode {
        var current = node.parent
        while (current != null) {
            if (current.config.contains(SemanticsProperties.VerticalScrollAxisRange)) return current
            current = current.parent
        }
        error("The artwork requires a local scrolling container for enlarged text")
    }

    private fun render(width: Dp, height: Dp, fontScale: Float) {
        val messages = (1..2).map { id -> AppMessage("message-$id", MessageKind.SYSTEM, "已有的第 $id 条消息", null,
            id.toLong(), false, AppMessageTarget("review", "chapter-$id"), MessageTargetState.AVAILABLE) }
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale)) {
                RlTheme {
                    Box(Modifier.requiredSize(width, height)) {
                        MessagesScreen(MessagesUiState(messages = messages, loading = false), {})
                    }
                }
            }
        }
    }
}
