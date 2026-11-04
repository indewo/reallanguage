package com.reallanguage.app.feature.learning.presentation

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Isolated renderer for original page 31's actual 1832 x 859 px / 320 dpi / font 1
 * baseline. The device owner supplies that configuration; this test never changes it.
 * Optional argument learningExitBaselineSnapshot=before|after captures the real Dialog
 * after an actual decor draw. No scroll, click, account, database or network is used.
 */
class LearningExitDialogBaselineSnapshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var dialogWindow: Window
    private var expectedWidthPx = WIDTH_PX
    private var expectedHeightPx = HEIGHT_PX

    @Test
    fun baselineDialogShowsAllThreeChoicesWithoutScrollingAndCapturesTheRealWindow() {
        val actions = renderBaseline()
        val initialOrigins = anchors()
        assertWholeInitialText(compose.onNodeWithText(string(R.string.learning_exit_page_title)), string(R.string.learning_exit_page_title), 34f)
        assertWholeInitialText(compose.onNodeWithText(string(R.string.learning_exit_explanation)), string(R.string.learning_exit_explanation), 15f)
        choices.forEach { choice ->
            val label = string(choice.title)
            assertWholeInitialText(
                compose.onNode(hasText(label) and !hasTestTag(choice.tag) and !hasAnyAncestor(hasTestTag(choice.tag)), useUnmergedTree = true),
                label, 21f,
            )
            assertWholeInitialText(compose.onNodeWithText(string(choice.description), useUnmergedTree = true), string(choice.description), 13f)
            val button = compose.onNodeWithTag(choice.tag).assertIsDisplayed().assertIsEnabled()
            assertWholeInitialText(
                compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(choice.tag)), useUnmergedTree = true), label, 16f, button,
            )
        }
        assertEquals("All choices must be readable at the same initial position, not after separate scrolling", initialOrigins, anchors())
        assertTrue("A visual baseline must not execute learning-exit business actions", actions.isEmpty())
        captureIfRequested()
    }

    @Test
    fun baselineContentWidthThreeColumnsButtonsAndHeadingFollowTheFrozenReferenceGeometry() {
        val actions = renderBaseline()
        val before = anchors()
        val buttons = choices.map { compose.onNodeWithTag(it.tag).assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot }
        val errors = mutableListOf<String>()
        fun near(label: String, actual: Float, expected: Float, tolerance: Float) {
            if (abs(actual - expected) > tolerance) errors += "$label actual=$actual expected=$expected tolerance=$tolerance px"
        }
        captureIfRequested { bitmap ->
            // The frozen image's fills were measured with the same color predicates:
            // 327 / 346 / 347 px wide, separated by 96 / 113 px. Inspect the actual
            // painted body within each real action's region, not its potentially
            // larger accessibility touch area or the hand-drawn 4-5 px outer frame.
            // A 4 px edge/width tolerance covers antialiasing, not an equal-column
            // replacement. Passing this does not assert texture or color fidelity.
            val expected = listOf(Rect(296f, 704f, 623f, 779f), Rect(719f, 704f, 1065f, 779f), Rect(1178f, 704f, 1525f, 779f))
            val painted = buttons.mapIndexed { index, bounds -> paintedButtonBounds(bitmap, bounds, index) }
            painted.forEachIndexed { index, actual ->
                val reference = expected[index]
                near("button ${index + 1} painted left", actual.left, reference.left, 4f)
                near("button ${index + 1} painted top", actual.top, reference.top, 4f)
                near("button ${index + 1} painted right", actual.right, reference.right, 4f)
                near("button ${index + 1} painted bottom", actual.bottom, reference.bottom, 4f)
                near("button ${index + 1} painted width", actual.width, reference.width, 4f)
                near("button ${index + 1} painted height", actual.height, reference.height, 4f)
            }
            val envelope = Rect(painted.minOf { it.left }, painted.minOf { it.top }, painted.maxOf { it.right }, painted.maxOf { it.bottom })
            near("three-choice painted envelope width", envelope.width, 1229f, 4f)
            near("three-choice painted envelope center x", envelope.center.x, 910.5f, 4f)
            near("first painted column gap", painted[1].left - painted[0].right, 96f, 4f)
            near("second painted column gap", painted[2].left - painted[1].right, 113f, 4f)
            val ink = titleInkBounds(bitmap)
            // Reference dark-ink bbox [745,93..1114,140], measured with the same
            // predicate. Use actual ink, not a TextLayout line box containing font
            // ascent/descent space. Only position is checked: its different typeface
            // and ink dimensions remain an explicit, separately unaccepted difference.
            near("heading ink center x", ink.center.x, 929.5f, 16f)
            near("heading ink center y", ink.center.y, 116.5f, 10f)
        }
        assertEquals("Compare all regions at the same untouched baseline position", before, anchors())
        assertTrue("Geometry inspection must not submit any learning action", actions.isEmpty())
        assertTrue("Frozen page 31 geometry mismatch:\n${errors.joinToString("\n")}", errors.isEmpty())
    }

    @Test
    fun normalWidth1672DialogKeepsTheFrozenReferenceUnequalPaintedColumnRatios() {
        // This is the real device configuration used by the production Graph journey,
        // not a LocalDensity wrapper or resized screenshot. 1672 / 2 = 836 dp must
        // not silently discard the reference proportions at a nearby width threshold.
        val actions = renderBaseline(widthPx = 1672, heightPx = 941)
        val before = anchors()
        val buttons = choices.map { compose.onNodeWithTag(it.tag).assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot }
        val scaleX = 1672f / WIDTH_PX
        val errors = mutableListOf<String>()
        fun near(label: String, actual: Float, sourcePixels: Float) {
            val expected = sourcePixels * scaleX
            // Exactly the existing painted-edge tolerance; no widening to make the
            // narrower window pass. Text, touch area, color and texture are separate.
            if (abs(actual - expected) > 4f) errors += "$label actual=$actual expected=$expected tolerance=4.0 px"
        }
        captureIfRequested(snapshotLabel = "1672-baseline") { bitmap ->
            val painted = buttons.mapIndexed { index, bounds -> paintedButtonBounds(bitmap, bounds, index) }
            val reference = listOf(Rect(296f, 704f, 623f, 779f), Rect(719f, 704f, 1065f, 779f), Rect(1178f, 704f, 1525f, 779f))
            painted.forEachIndexed { index, actual ->
                near("button ${index + 1} painted width", actual.width, reference[index].width)
                near("button ${index + 1} painted left", actual.left, reference[index].left)
                near("button ${index + 1} painted right", actual.right, reference[index].right)
            }
            near("first painted column gap", painted[1].left - painted[0].right, 96f)
            near("second painted column gap", painted[2].left - painted[1].right, 113f)
            near("three-choice painted envelope width", painted.maxOf { it.right } - painted.minOf { it.left }, 1229f)
        }
        assertEquals("All three original ratios must be inspected at the same initial scroll position", before, anchors())
        assertTrue("The proportional layout test must not execute an exit intent", actions.isEmpty())
        assertTrue("Real 1672 x 941 Dialog must preserve frozen page 31 ratios:\n${errors.joinToString("\n")}", errors.isEmpty())
    }

    @Test
    fun normalWidth1672KeepsChoiceTitlesAndDescriptionStartsOnSharedBaselines() {
        val actions = renderBaseline(widthPx = 1672, heightPx = 941)
        val before = anchors()
        val titleNodes = choices.map { choice ->
            compose.onNode(
                hasText(string(choice.title)) and !hasTestTag(choice.tag) and !hasAnyAncestor(hasTestTag(choice.tag)),
                useUnmergedTree = true,
            )
        }
        val descriptionNodes = choices.map { compose.onNodeWithText(string(it.description), useUnmergedTree = true) }
        val descriptionLineCounts = mutableListOf<Int>()
        fun baseline(node: SemanticsNodeInteraction, expected: String, fontSp: Float, description: Boolean = false): Float {
            assertWholeInitialText(node, expected, fontSp)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertEquals(1, layouts.size)
            val layout = layouts.single()
            if (description) descriptionLineCounts += layout.lineCount else assertEquals(1, layout.lineCount)
            // Compare actual draw coordinates at one untouched scroll position. X-only
            // ParagraphLayoutCache corrections cannot change a real first baseline.
            return node.fetchSemanticsNode().layoutInfo.coordinates.localToRoot(Offset.Zero).y + layout.firstBaseline
        }
        val titles = titleNodes.mapIndexed { index, node -> baseline(node, string(choices[index].title), 21f) }
        val descriptions = descriptionNodes.mapIndexed { index, node ->
            baseline(node, string(choices[index].description), 13f, description = true)
        }
        val buttons = choices.map { choice ->
            compose.onNodeWithTag(choice.tag).assertIsDisplayed().assertIsEnabled()
            baseline(
                compose.onNode(hasText(string(choice.title)) and hasAnyAncestor(hasTestTag(choice.tag)), useUnmergedTree = true),
                string(choice.title), 16f,
            )
        }
        assertTrue("The actual unmodified copy must exercise unequal description line counts: $descriptionLineCounts",
            descriptionLineCounts.distinct().size > 1)
        compose.waitForIdle()
        assertEquals("Titles, description starts and buttons must share one untouched initial viewport", before, anchors())
        fun spread(values: List<Float>) = values.maxOrNull()!! - values.minOrNull()!!
        assertTrue("Reference columns must align despite one-line versus wrapped descriptions: " +
            "titleBaselines=$titles descriptionFirstBaselines=$descriptions buttonBaselines=$buttons",
            spread(titles) <= 1f && spread(descriptions) <= 1f && spread(buttons) <= 1f)
        assertTrue("Alignment inspection must not execute an exit intent", actions.isEmpty())
        assertBaselineWindow()
    }

    private fun renderBaseline(widthPx: Int = WIDTH_PX, heightPx: Int = HEIGHT_PX): MutableList<LearningExitAction> {
        require((widthPx == WIDTH_PX && heightPx == HEIGHT_PX) || (widthPx == 1672 && heightPx == 941))
        expectedWidthPx = widthPx
        expectedHeightPx = heightPx
        val actions = mutableListOf<LearningExitAction>()
        compose.setContent {
            RlTheme {
                LearningExitDialog(
                    state = LearningExitUiState(OrdinaryLearningCheckpoint("render-only-baseline-chapter", "LR-09", "round-2")),
                    onAction = actions::add,
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            fun windows(view: View): Sequence<Window> = sequence {
                if (view is DialogWindowProvider) yield(view.window)
                if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(windows(view.getChildAt(index)))
            }
            val found = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::windows).distinct().toList()
            assertEquals("Exactly one real Dialog must supply the baseline", 1, found.size)
            dialogWindow = found.single()
            assertTrue(dialogWindow !== compose.activity.window)
        }
        compose.waitUntil(5_000) { dialogWindow.decorView.width == expectedWidthPx && dialogWindow.decorView.height == expectedHeightPx }
        compose.waitForIdle()
        assertBaselineWindow()
        return actions
    }

    private fun assertBaselineWindow() {
        compose.runOnIdle {
            assertEquals("Actual Dialog width, not a resized screenshot", expectedWidthPx, dialogWindow.decorView.width)
            assertEquals("Actual Dialog height, not a taller test host", expectedHeightPx, dialogWindow.decorView.height)
            val resources = dialogWindow.decorView.resources
            assertEquals(320, resources.configuration.densityDpi)
            assertEquals(2f, resources.displayMetrics.density, .001f)
            assertEquals("The Dialog must really use the original font scale", 1f, resources.configuration.fontScale, .001f)
        }
        val root = dialogRoot().fetchSemanticsNode().layoutInfo
        assertEquals(expectedWidthPx, root.width)
        assertEquals(expectedHeightPx, root.height)
    }

    private fun dialogRoot(): SemanticsNodeInteraction {
        var root = compose.onNodeWithTag(choices.first().tag, useUnmergedTree = true).fetchSemanticsNode()
        while (root.parent != null) root = requireNotNull(root.parent)
        val id = root.id
        return compose.onNode(SemanticsMatcher("the baseline Dialog's own root") { it.id == id }, useUnmergedTree = true)
    }

    private fun assertWholeInitialText(node: SemanticsNodeInteraction, expected: String, expectedFontSp: Float, button: SemanticsNodeInteraction? = null) {
        // Intentionally never performScrollTo: this test proves the initial baseline.
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals(1, layouts.size)
        val layout = layouts.single()
        assertEquals(expected, layout.layoutInput.text.text)
        assertEquals(2f, layout.layoutInput.density.density, .001f)
        assertEquals(1f, layout.layoutInput.density.fontScale, .001f)
        assertEquals("Keep the existing font token", expectedFontSp.sp, layout.layoutInput.style.fontSize)
        assertFalse(layout.multiParagraph.didExceedMaxLines)
        assertTrue(layout.lineCount > 0)
        assertEquals(expected.length, layout.getLineEnd(layout.lineCount - 1))
        val coordinates = node.fetchSemanticsNode().layoutInfo.coordinates
        val origin = actualParagraphOrigin(layout, coordinates.localToRoot(Offset.Zero), coordinates.size.width)
        var clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(node.fetchSemanticsNode().boundsInRoot).intersect(dialogRoot().fetchSemanticsNode().boundsInRoot)
        if (button != null) clip = clip.intersect(button.fetchSemanticsNode().boundsInRoot)
        repeat(layout.lineCount) { line ->
            assertFalse(layout.isLineEllipsized(line))
            val glyphs = (layout.getLineStart(line) until layout.getLineEnd(line, visibleEnd = true))
                .filter { !expected[it].isWhitespace() }.map { layout.getBoundingBox(it).translate(origin) }
            assertTrue("Initial baseline text must be wholly visible: line=$line clip=$clip glyphs=$glyphs",
                clip.width > 0f && clip.height > 0f && glyphs.isNotEmpty() && glyphs.all { fits(it, clip) })
        }
    }

    private fun actualParagraphOrigin(layout: TextLayoutResult, origin: Offset, actualWidth: Int): Offset {
        val semanticWidth = layout.multiParagraph.width
        if (abs(semanticWidth - actualWidth) <= 1f) return origin
        // Same verified Compose 1.11.4 single-line ParagraphLayoutCache measurement
        // distinction as the short-screen fit test; only a proven Center x-offset is
        // removed. Left/Start side bearings are not treated as additional placement.
        val left = layout.getLineLeft(0)
        val right = layout.getLineRight(0)
        val diagnostic = "semanticWidth=$semanticWidth actualWidth=$actualWidth size=${layout.size} " +
            "intrinsic=${layout.multiParagraph.intrinsics.maxIntrinsicWidth} lines=${layout.lineCount} left=$left right=$right"
        assertTrue("Resolve the drawn paragraph before a clipping verdict: $diagnostic",
            layout.lineCount == 1 && layout.size.width == actualWidth && semanticWidth > actualWidth &&
                ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt() == actualWidth)
        val alignment = layout.layoutInput.style.textAlign
        val isLeft = alignment == TextAlign.Left ||
            ((alignment == TextAlign.Start || alignment == TextAlign.Unspecified) && layout.layoutInput.layoutDirection == LayoutDirection.Ltr)
        if (isLeft && abs(left) <= 1f && right in 0f..(actualWidth + 1f)) return origin
        val offset = (semanticWidth - actualWidth) / 2f
        assertTrue("Only the confirmed Center offset may be removed: $diagnostic",
            alignment == TextAlign.Center && abs(left - offset) <= 1f && abs(right - (offset + actualWidth)) <= 1f)
        return origin - Offset(offset, 0f)
    }

    private fun anchors(): List<Offset> = choices.map {
        compose.onNodeWithTag(it.tag).fetchSemanticsNode().layoutInfo.coordinates.localToRoot(Offset.Zero)
    } + compose.onNodeWithText(string(R.string.learning_exit_page_title)).fetchSemanticsNode().layoutInfo.coordinates.localToRoot(Offset.Zero)

    private fun captureIfRequested(snapshotLabel: String = "baseline", inspect: ((android.graphics.Bitmap) -> Unit)? = null) {
        require(snapshotLabel in setOf("baseline", "1672-baseline"))
        val phase = InstrumentationRegistry.getArguments().getString("learningExitBaselineSnapshot")
        if (phase == null && inspect == null) return
        if (phase != null) require(phase in setOf("before", "after"))
        compose.waitForIdle()
        assertBaselineWindow()
        val before = anchors()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val drawn = CountDownLatch(1)
        val listener = ViewTreeObserver.OnDrawListener { drawn.countDown() }
        instrumentation.runOnMainSync {
            assertEquals("The actual isolated Dialog must be the only foreground window",
                listOf(dialogWindow.decorView), WindowInspector.getGlobalWindowViews().filter { it.hasWindowFocus() })
            dialogWindow.decorView.viewTreeObserver.addOnDrawListener(listener)
            dialogWindow.decorView.postInvalidateOnAnimation()
        }
        try { check(drawn.await(10, TimeUnit.SECONDS)) { "Actual baseline Dialog decor did not draw" } }
        finally {
            instrumentation.runOnMainSync {
                if (dialogWindow.decorView.viewTreeObserver.isAlive) dialogWindow.decorView.viewTreeObserver.removeOnDrawListener(listener)
            }
        }
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        compose.waitForIdle()
        assertBaselineWindow()
        assertEquals("Capture the same stable initial state", before, anchors())
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(expectedWidthPx, bitmap.width)
            assertEquals(expectedHeightPx, bitmap.height)
            assertEquals(before, anchors())
            var inkSamples = 0
            for (y in 0 until bitmap.height step 20) for (x in 0 until bitmap.width step 20) {
                val color = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(color) < 170 && android.graphics.Color.green(color) < 170 &&
                    android.graphics.Color.blue(color) < 170) inkSamples += 1
            }
            assertTrue("Reject a blank screenshot as evidence", inkSamples > 8)
            inspect?.invoke(bitmap)
            if (phase != null) {
                val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa")
                check(folder.isDirectory || folder.mkdirs())
                val output = File(folder, "31-learning-exit-$snapshotLabel-$phase.png")
                check(output.canonicalFile.parentFile == folder.canonicalFile)
                output.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            }
        } finally { bitmap.recycle() }
    }

    private fun titleInkBounds(bitmap: android.graphics.Bitmap): Rect {
        var left = 1200
        var right = 600
        var top = 170
        var bottom = 40
        var samples = 0
        // Fixed reference heading region only; the dark-blue predicate excludes its
        // brown underline and the lighter actual business explanation. No threshold
        // is derived from a desired geometry verdict.
        for (y in 40 until 170) for (x in 600 until 1200) {
            val color = bitmap.getPixel(x, y)
            val red = android.graphics.Color.red(color)
            val green = android.graphics.Color.green(color)
            val blue = android.graphics.Color.blue(color)
            if (maxOf(red, green, blue) < 90 && blue > red + 8) {
                samples += 1
                left = minOf(left, x)
                right = maxOf(right, x + 1)
                top = minOf(top, y)
                bottom = maxOf(bottom, y + 1)
            }
        }
        assertTrue("The real isolated heading must supply substantial ink before a geometry verdict", samples > 100)
        return Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    private fun paintedButtonBounds(bitmap: android.graphics.Bitmap, actionBounds: Rect, index: Int): Rect {
        val x0 = floor(actionBounds.left).toInt().coerceAtLeast(0)
        val y0 = floor(actionBounds.top).toInt().coerceAtLeast(0)
        val x1 = ceil(actionBounds.right).toInt().coerceAtMost(bitmap.width)
        val y1 = ceil(actionBounds.bottom).toInt().coerceAtMost(bitmap.height)
        val width = x1 - x0
        val height = y1 - y0
        assertTrue("The real action must provide a nonempty bounded region for paint inspection", width > 0 && height > 0)
        val mask = BooleanArray(width * height) { offset ->
            val color = bitmap.getPixel(x0 + offset % width, y0 + offset / width)
            val red = android.graphics.Color.red(color)
            val green = android.graphics.Color.green(color)
            val blue = android.graphics.Color.blue(color)
            when (index) {
                0 -> red < 160 && green < 175 && blue < 155 && green > blue
                1 -> red < 145 && green < 170 && blue < 190 && blue > red + 5
                2 -> red > 110 && red < 225 && green < 155 && blue < 140 && red > green + 35
                else -> error("Only the three frozen-reference actions belong to this renderer")
            }
        }
        // Largest connected fill excludes disconnected white lettering, paper texture
        // and decorative pixels. Restricting to the actual action prevents neighboring
        // page artwork from being mistaken for the button body.
        val queue = IntArray(mask.size)
        var largestCount = 0
        var largest = Rect.Zero
        for (seed in mask.indices) {
            if (!mask[seed]) continue
            var head = 0
            var tail = 1
            queue[0] = seed
            mask[seed] = false
            var left = width
            var right = 0
            var top = height
            var bottom = 0
            fun enqueue(offset: Int) {
                if (mask[offset]) {
                    mask[offset] = false
                    queue[tail++] = offset
                }
            }
            while (head < tail) {
                val point = queue[head++]
                val x = point % width
                val y = point / width
                left = minOf(left, x)
                right = maxOf(right, x + 1)
                top = minOf(top, y)
                bottom = maxOf(bottom, y + 1)
                if (x > 0) enqueue(point - 1)
                if (x + 1 < width) enqueue(point + 1)
                if (y > 0) enqueue(point - width)
                if (y + 1 < height) enqueue(point + width)
            }
            if (tail > largestCount) {
                largestCount = tail
                largest = Rect((x0 + left).toFloat(), (y0 + top).toFloat(), (x0 + right).toFloat(), (y0 + bottom).toFloat())
            }
        }
        assertTrue("Resolve a substantial connected painted action before judging geometry: action=$index pixels=$largestCount", largestCount > 1_000)
        return largest
    }

    private fun string(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private data class Choice(val tag: String, val title: Int, val description: Int)
    private val choices = listOf(
        Choice("learning-exit-continue", R.string.learning_exit_continue, R.string.learning_exit_continue_explanation),
        Choice("learning-exit-pause", R.string.learning_exit_pause, R.string.learning_exit_pause_explanation),
        Choice("learning-exit-abandon", R.string.learning_exit_abandon, R.string.learning_exit_abandon_explanation),
    )

    private companion object {
        const val WIDTH_PX = 1832
        const val HEIGHT_PX = 859
        fun fits(box: Rect, clip: Rect) = box.top >= clip.top - 1f && box.bottom <= clip.bottom + 1f &&
            box.left >= clip.left - 1f && box.right <= clip.right + 1f
    }
}
