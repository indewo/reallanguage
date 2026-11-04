package com.reallanguage.app.feature.learning.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.FixedSceneResponseTask
import com.reallanguage.app.feature.learning.domain.SceneDialogueLine
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Pure production-screen rendering, grounded in original 30 and the 1672 x 941 Graph capture.
 * Third-round fixed answers remain hidden. No Graph, account, database, audio or HTTP is used.
 * The image tag must be on the actual microphone Image, not its smaller decorative container.
 */
class SceneResponseHeaderAndArtFitTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun useTheProductionWindowPolicy() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    @Test fun referenceHeaderCentersItsActualTitleOnTheCanvasWithoutCoveringRoundOrExit() {
        render()
        val title = assertHeaderReadableAndDisjoint()
        assertEquals("The baseline title must remain one complete line", 1, title.layout.lineCount)
        val canvas = canvasBounds()
        assertTrue("Center the actual title on the canvas, not the space left after unequal side controls: " +
            "title=${title.ink} canvas=$canvas",
            abs(title.ink.center.x - canvas.center.x) <= 4f)
        compose.onNodeWithText(HIDDEN_ANSWER).assertDoesNotExist()
    }

    @Test fun referenceMicrophonePaintedBaseIsInsideTheRealBodyClipAtInitialScrollPosition() {
        render()
        val image = compose.onNodeWithTag("scene-response-microphone-art", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode()
        val coordinates = image.layoutInfo.coordinates
        val origin = coordinates.localToRoot(Offset.Zero)
        val visibleBody = compose.onNodeWithTag("scene-content-scroll-viewport")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot.intersect(canvasBounds())
        val actualImageClip = coordinates.findRootCoordinates()
            .localBoundingBoxOf(coordinates, clipBounds = true).intersect(visibleBody)

        // Match production Image's ContentScale.Fit and centered alignment using the real PNG.
        // Transparent resource padding is not the microphone base; find its last painted row.
        val bitmap = requireNotNull(BitmapFactory.decodeResource(
            compose.activity.resources, R.drawable.rl_scene_response_microphone,
            BitmapFactory.Options().apply { inScaled = false },
        ))
        val paintedBottom = try {
            val row = IntArray(bitmap.width)
            var lastPaintedRow = -1
            for (y in bitmap.height - 1 downTo 0) {
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                if (row.any { Color.alpha(it) > 0 }) {
                    lastPaintedRow = y
                    break
                }
            }
            assertTrue("Use the real nonempty microphone artwork", lastPaintedRow >= 0)
            val scale = min(coordinates.size.width.toFloat() / bitmap.width,
                coordinates.size.height.toFloat() / bitmap.height)
            assertTrue("The actual Image must retain a nonzero drawing area", scale > 0f)
            val fittedTop = origin.y + (coordinates.size.height - bitmap.height * scale) / 2f
            fittedTop + (lastPaintedRow + 1) * scale
        } finally {
            bitmap.recycle()
        }
        assertTrue("The microphone base may not be cut off by the real body scroll clip: " +
            "paintedBottom=$paintedBottom imageOrigin=$origin imageSize=${coordinates.size} " +
            "imageClip=$actualImageClip visibleBody=$visibleBody",
            actualImageClip.height > 0f && paintedBottom <= actualImageClip.bottom + 1f &&
                paintedBottom <= visibleBody.bottom + 1f)
        compose.onNodeWithText("开始回应").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(HIDDEN_ANSWER).assertDoesNotExist()
    }

    @Test fun shortDoubleFontKeepsEveryHeaderGlyphReadableAndExitIndependentOfRoundStatus() {
        var exits = 0
        val actions = mutableListOf<SceneResponseAction>()
        render(short = true, onExit = { exits++ }, onAction = actions::add)
        // A short viewport may reflow. Do not require baseline centering or shrink the text.
        assertHeaderReadableAndDisjoint()
        compose.onNodeWithText(EXIT).assertIsEnabled().performClick()
        assertEquals(1, exits)
        assertEquals(emptyList<SceneResponseAction>(), actions)
        compose.onNodeWithText(HIDDEN_ANSWER).assertDoesNotExist()
    }

    private fun render(
        short: Boolean = false,
        onExit: () -> Unit = {},
        onAction: (SceneResponseAction) -> Unit = {},
    ) {
        val state = SceneResponseUiState(
            chapter = FixedLearningChapter("render-only-chapter", 1,
                listOf(FixedLearningSentence("render-only-focus", HIDDEN_ANSWER, "我感觉更平静了。", true))),
            tasks = listOf(FixedSceneResponseTask("render-only-task", "render-only-focus",
                "At the end of a quiet afternoon.", listOf(SceneDialogueLine("AI", "How did you feel?")), HIDDEN_ANSWER)),
            roundIndex = 2,
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, if (short) 2f else 1f)) {
                RlTheme {
                    Box(Modifier.requiredSize(if (short) 640.dp else 836.dp, if (short) 360.dp else 470.5.dp)
                        .testTag(VIEWPORT)) {
                        SceneResponseScreen(state = state, onExit = onExit, onAction = onAction)
                    }
                }
            }
        }
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().layoutInfo
        assertEquals(if (short) 1280 else 1672, viewport.width)
        assertEquals(if (short) 720 else 941, viewport.height)
    }

    private data class TextGeometry(val layout: TextLayoutResult, val ink: Rect)

    private fun assertHeaderReadableAndDisjoint(): TextGeometry {
        val title = textGeometry(TITLE)
        val round = textGeometry(ROUND)
        val exit = textGeometry(EXIT)
        assertEquals("Do not shrink the established title font", 21.sp, title.layout.layoutInput.style.fontSize)
        val exitButton = compose.onNodeWithText(EXIT).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertFalse("Round text must not lie beneath the exit button: round=${round.ink} exit=$exitButton",
            overlaps(round.ink, exitButton))
        assertFalse("The title and round must have separate readable space", overlaps(title.ink, round.ink))
        assertFalse("The title and exit must have separate readable space", overlaps(title.ink, exit.ink))
        return title
    }

    private fun textGeometry(text: String): TextGeometry {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        val layout = layout(node)
        assertEquals(text, layout.layoutInput.text.text)
        assertTrue("The header must contain a real line: $text", layout.lineCount > 0)
        assertFalse("Header text cannot be removed or ellipsized: $text", layout.multiParagraph.didExceedMaxLines)
        assertEquals("The final header character must still be present", text.length,
            layout.getLineEnd(layout.lineCount - 1))
        repeat(layout.lineCount) { line ->
            assertFalse("No header line may be ellipsized: $text line=$line", layout.isLineEllipsized(line))
        }
        val measuredNode = node.fetchSemanticsNode()
        val coordinates = measuredNode.layoutInfo.coordinates
        val diagnostic = "text=$text node=${measuredNode.id} size=${coordinates.size} " +
            "origin=${coordinates.localToRoot(Offset.Zero)} bounds=${measuredNode.boundsInRoot} " +
            "align=${layout.layoutInput.style.textAlign} constraints=${layout.layoutInput.constraints}"
        val origin = realParagraphOrigin(layout, coordinates.localToRoot(Offset.Zero),
            coordinates.size.width, diagnostic)
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(canvasBounds())
        val glyphs = text.indices.filter { !text[it].isWhitespace() }
            .map { layout.getBoundingBox(it).translate(origin) }
        assertTrue("Every actual header glyph must remain complete: text=$text clip=$clip glyphs=$glyphs",
            glyphs.isNotEmpty() && glyphs.all { fits(it, clip) })
        return TextGeometry(layout, Rect(glyphs.minOf { it.left }, glyphs.minOf { it.top },
            glyphs.maxOf { it.right }, glyphs.maxOf { it.bottom }))
    }

    private fun realParagraphOrigin(
        layout: TextLayoutResult,
        nodeOrigin: Offset,
        actualWidth: Int,
        coordinates: String,
    ): Offset {
        val semanticWidth = layout.multiParagraph.width
        if (abs(semanticWidth - actualWidth) <= 1f) return nodeOrigin
        // Compose 1.11.4 reconstructs a MultiParagraph using the earlier constraints
        // for semantics, while the actual Paragraph may use maxIntrinsicWidth. Thus
        // didOverflowWidth alone does not prove clipping. Only resolve the verified
        // single-line/intrinsic case; retain the actual node and ancestor clip bounds.
        val left = layout.getLineLeft(0)
        val right = layout.getLineRight(0)
        val diagnostic = "semanticWidth=$semanticWidth actualWidth=$actualWidth size=${layout.size} " +
            "intrinsic=${layout.multiParagraph.intrinsics.maxIntrinsicWidth} lines=${layout.lineCount} " +
            "lineLeft=$left lineRight=$right; $coordinates"
        assertTrue("Resolve the real drawn paragraph before a clipping verdict: $diagnostic",
            layout.lineCount == 1 && layout.size.width == actualWidth && semanticWidth > actualWidth &&
                ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt() == actualWidth)
        // Start/Left glyphs already begin at the node origin. Their last painted
        // glyph need not touch the measured right edge (rounding/letter spacing).
        if (abs(left) <= 1f) return nodeOrigin
        val offset = (semanticWidth - actualWidth) / 2f
        assertTrue("Only the verified single-line Center semantic offset may be removed: $diagnostic",
            layout.layoutInput.style.textAlign == TextAlign.Center &&
                abs(left - offset) <= 1f && abs(right - (offset + actualWidth)) <= 1f)
        return nodeOrigin - Offset(offset, 0f)
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        return results.single()
    }

    private fun canvasBounds() = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot

    private companion object {
        const val VIEWPORT = "scene-header-art-fit-viewport"
        const val TITLE = "第 1 章 · 场景回应"
        const val ROUND = "第 3 轮 / 共 3 轮"
        const val EXIT = "退出学习"
        const val HIDDEN_ANSWER = "I felt calmer."
        fun fits(content: Rect, clip: Rect) = clip.width > 0f && clip.height > 0f &&
            content.left >= clip.left - 1f && content.right <= clip.right + 1f &&
            content.top >= clip.top - 1f && content.bottom <= clip.bottom + 1f
        fun overlaps(first: Rect, second: Rect) = first.left < second.right && first.right > second.left &&
            first.top < second.bottom && first.bottom > second.top
    }
}
