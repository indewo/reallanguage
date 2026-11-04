package com.reallanguage.app.feature.safety.presentation

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Rendered Screen and real button touch only; no account, administrator, HTTP, or AI.
 * Baseline matches the supplied current 1831 x 859 screenshot, not the 1774 x 887 original.
 * The short viewport is a real clipped 1280 x 720 region, with no test-added outer scroll.
 * The current business label remains "进入全量拆句"; no reference-image copy is substituted.
 */
class ManualReviewResultActionVisibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun focusedLandscapeHost() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
    }

    @Test fun approvedBaselineReturnAndContinueLabelsAreCompleteAndDispatchTheirOwnActions() {
        verifyActions(approved = true, short = false)
    }

    @Test fun rejectedBaselineReturnAndSafetyLabelsAreCompleteAndDispatchTheirOwnActions() {
        verifyActions(approved = false, short = false)
    }

    @Test fun approvedShortDoubleFontReturnAndContinueLabelsRemainReadableAndTouchable() {
        verifyActions(approved = true, short = true)
    }

    @Test fun rejectedShortDoubleFontReturnAndSafetyLabelsRemainReadableAndTouchable() {
        verifyActions(approved = false, short = true)
    }

    private fun verifyActions(approved: Boolean, short: Boolean) {
        val width = if (short) 1280 else 1831
        val height = if (short) 720 else 859
        val scale = if (short) 2f else 1f
        val actions = mutableListOf<ManualReviewResultAction>()
        var actualDensity: Density? = null
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, scale)) {
                val density = LocalDensity.current
                SideEffect { actualDensity = density }
                RlTheme {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.requiredSize((width / 2f).dp, (height / 2f).dp).clipToBounds().testTag(VIEWPORT)) {
                            ManualReviewResultScreen(
                                state = ManualReviewResultUiState(
                                    loading = false,
                                    outcome = if (approved) ManualReviewOutcome.APPROVED else ManualReviewOutcome.NOT_APPROVED,
                                    summaryZh = if (approved) "本次内容已通过人工复核。" else "本次内容未通过人工复核。",
                                    reasonZh = if (approved) null else "内容仍包含需要处理的具体信息。",
                                ),
                                onAction = actions::add,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(2f, requireNotNull(actualDensity).density, .001f)
            assertEquals(scale, requireNotNull(actualDensity).fontScale, .001f)
        }
        val viewport = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode()
        assertEquals(width, viewport.layoutInfo.width)
        assertEquals(height, viewport.layoutInfo.height)
        val viewportCoordinates = viewport.layoutInfo.coordinates
        val actualClip = viewportCoordinates.findRootCoordinates()
            .localBoundingBoxOf(viewportCoordinates, clipBounds = true)
        assertEquals("The real host must not clip the test viewport horizontally", width.toFloat(), actualClip.width, 1f)
        assertEquals("The real host must not clip the test viewport vertically", height.toFloat(), actualClip.height, 1f)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val primary = context.getString(if (approved) R.string.review_enter_split else R.string.review_return_safety)
        val opposite = context.getString(if (approved) R.string.review_return_safety else R.string.review_enter_split)
        val back = context.getString(R.string.review_return_messages)
        compose.onNodeWithText(opposite).assertDoesNotExist()
        assertEquals("Displaying a result must not navigate automatically", emptyList<ManualReviewResultAction>(), actions)
        // The supplied screenshot's defect is the secondary label, so inspect it first.
        assertCompleteAction(back)
        compose.onNodeWithText(back).performTouchInput { click() }
        assertEquals(listOf(ManualReviewResultAction.ReturnMessagesRequested), actions)
        assertCompleteAction(primary)
        compose.onNodeWithText(primary).performTouchInput { click() }
        assertEquals(listOf(
            ManualReviewResultAction.ReturnMessagesRequested,
            if (approved) ManualReviewResultAction.OpenFullSentenceSplitRequested
            else ManualReviewResultAction.ReturnToSafetyHandlingRequested,
        ), actions)
    }

    private fun assertCompleteAction(label: String) {
        val button = compose.onNodeWithText(label)
        // Use production scrolling only if the action is outside the current paper viewport.
        // A screen without such a scroll must still pass the actual glyph/clip assertions below.
        if (!button.isDisplayed()) runCatching { button.performScrollTo() }
        button.assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        val text = compose.onNodeWithText(label, useUnmergedTree = true)
        val results = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue("The real label must expose its paragraph", it(results))
        }
        assertEquals(1, results.size)
        val layout = results.single()
        assertEquals(label, layout.layoutInput.text.text)
        assertFalse("The full label must not be truncated", layout.multiParagraph.didExceedMaxLines)
        assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1))
        repeat(layout.lineCount) { line ->
            assertFalse("No button-label ellipsis is allowed", layout.isLineEllipsized(line))
        }
        val coordinates = text.fetchSemanticsNode().layoutInfo.coordinates
        val origin = coordinates.localToRoot(Offset.Zero)
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(button.fetchSemanticsNode().boundsInRoot)
            .intersect(compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot)
        val glyphs = label.indices.filter { !label[it].isWhitespace() }
            .map { layout.getBoundingBox(it).translate(origin) }
        assertTrue("Every glyph of '$label' must fit its real button and viewport: " +
            "lines=${layout.lineCount}, paragraph=${layout.size}, clip=$clip, glyphs=$glyphs",
            clip.width > 0f && clip.height > 0f && glyphs.isNotEmpty() && glyphs.all { fits(it, clip) })
    }

    private companion object {
        const val VIEWPORT = "manual-review-result-action-viewport"
        fun fits(glyph: Rect, clip: Rect) = glyph.left >= clip.left - 1f && glyph.right <= clip.right + 1f &&
            glyph.top >= clip.top - 1f && glyph.bottom <= clip.bottom + 1f
    }
}
