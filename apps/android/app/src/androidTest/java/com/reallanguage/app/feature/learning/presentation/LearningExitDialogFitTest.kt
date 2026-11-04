package com.reallanguage.app.feature.learning.presentation

import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.ViewTreeObserver
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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Run only after the device owner sets the isolated emulator to 1280 x 720 px / density
 * 320 dpi / font scale 2. Dialog creates its own Android Owner, so an outer LocalDensity
 * does not prove the Dialog's actual font configuration. This test neither changes the
 * system nor resizes/recomposes a window after the text has already been laid out.
 * No test-added scrolling, custom error copy, account, database or request is used.
 */
class LearningExitDialogFitTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var dialogWindow: Window

    @Test
    fun shortLargeFontDialogShowsEveryChoiceTitleDescriptionAndButtonAndDispatchesExactly() {
        val actions = mutableListOf<LearningExitAction>()
        render(state(), actions)
        captureIfRequested("normal-initial")
        assertWholeText(compose.onNodeWithText(string(R.string.learning_exit_page_title)), string(R.string.learning_exit_page_title), expectedFontSizeSp = 34f)
        assertWholeText(compose.onNodeWithText(string(R.string.learning_exit_explanation)), string(R.string.learning_exit_explanation), expectedFontSizeSp = 15f)
        choices.forEach { choice ->
            assertChoiceReadable(choice, "normal")
            compose.onNodeWithTag(choice.tag).assertIsEnabled().performClick()
        }
        assertEquals(choices.map { it.action }, actions)
        assertActualShortDialogWindow()
    }

    @Test
    fun failedCheckpointKeepsTheWholeActualErrorAndAllThreeChoicesReadableAndUsable() {
        val actions = mutableListOf<LearningExitAction>()
        render(state().copy(saveFailed = true), actions)
        captureIfRequested("failed-initial")
        val error = string(R.string.learning_exit_save_failed)
        val errorNode = compose.onNodeWithText(error)
        val errorLayout = assertWholeText(errorNode, error)
        assertTrue("The actual production error must exercise wrapped text at this size", errorLayout.lineCount > 1)
        captureIfRequested("failed-error")
        choices.forEach { choice ->
            assertChoiceReadable(choice, "failed")
            compose.onNodeWithTag(choice.tag).assertIsEnabled().performClick()
        }
        // The page dispatches intent only: abandon must still open the existing confirmation
        // downstream. This isolated renderer never deletes a chapter or claims a saved result.
        assertEquals(choices.map { it.action }, actions)
        assertActualShortDialogWindow()
    }

    @Test
    fun savingKeepsAllThreeChoiceButtonsDisabledWithoutDispatchingAnExitIntent() {
        val actions = mutableListOf<LearningExitAction>()
        render(state().copy(saving = true), actions)
        captureIfRequested("saving-initial")
        choices.forEach { choice ->
            val button = compose.onNodeWithTag(choice.tag)
            makeReachable(button)
            button.assertIsDisplayed().assertIsNotEnabled()
            assertButtonText(choice)
            button.performClick()
        }
        assertTrue("A pending checkpoint must not dispatch continue, pause or abandon", actions.isEmpty())
        assertActualShortDialogWindow()
    }

    @Test
    fun shortLargeFontKeepsAllThreeChoiceTitleAndButtonBaselinesAlignedAtOneScrollPosition() {
        val actions = mutableListOf<LearningExitAction>()
        render(state(), actions)
        val titleNodes = choices.map { choice ->
            compose.onNode(
                hasText(string(choice.title)) and !hasTestTag(choice.tag) and !hasAnyAncestor(hasTestTag(choice.tag)),
                useUnmergedTree = true,
            )
        }
        val buttonLabelNodes = choices.map { choice ->
            compose.onNode(hasText(string(choice.title)) and hasAnyAncestor(hasTestTag(choice.tag)), useUnmergedTree = true)
        }
        val allNodes = titleNodes + buttonLabelNodes
        fun rawOrigins() = allNodes.map { it.fetchSemanticsNode().layoutInfo.coordinates.localToRoot(Offset.Zero) }
        val originsBefore = rawOrigins()
        // No scroll-to, clipping adjustment or separate viewport per column: an off-screen
        // node still has its real laid-out baseline in this same Dialog root coordinate space.
        val titleBaselines = titleNodes.mapIndexed { index, node ->
            rawTextBaseline(node, string(choices[index].title), expectedFontSizeSp = 21f)
        }
        val buttonBaselines = buttonLabelNodes.mapIndexed { index, node ->
            rawTextBaseline(node, string(choices[index].title), expectedFontSizeSp = 16f)
        }
        compose.waitForIdle()
        assertEquals("All six baselines must be compared at the same stable scroll position", originsBefore, rawOrigins())
        val titleSpread = titleBaselines.maxOrNull()!! - titleBaselines.minOrNull()!!
        val buttonSpread = buttonBaselines.maxOrNull()!! - buttonBaselines.minOrNull()!!
        assertTrue(
            "The three-column design requires aligned title and button rows, without hiding a mismatch by scrolling each column: " +
                "titleBaselines=$titleBaselines titleSpreadPx=$titleSpread buttonBaselines=$buttonBaselines buttonSpreadPx=$buttonSpread",
            titleSpread <= 1f && buttonSpread <= 1f,
        )
        assertTrue("Measuring alignment must not dispatch an exit intent", actions.isEmpty())
        assertActualShortDialogWindow()
    }

    private fun rawTextBaseline(node: SemanticsNodeInteraction, expected: String, expectedFontSizeSp: Float): Float {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue("Read the actual title/button text layout before comparing baselines", it(layouts))
        }
        assertEquals("One actual text layout is required for the baseline", 1, layouts.size)
        val layout = layouts.single()
        assertEquals(expected, layout.layoutInput.text.text)
        assertEquals("Use the real Dialog density", 2f, layout.layoutInput.density.density, .001f)
        assertEquals("Use the actual large-font configuration", 2f, layout.layoutInput.density.fontScale, .001f)
        assertEquals("Alignment must not shrink the existing title or button token", expectedFontSizeSp.sp, layout.layoutInput.style.fontSize)
        assertEquals("This fixture compares the existing single-line choice title/button labels", 1, layout.lineCount)
        assertFalse("The label must not be truncated to obtain alignment", layout.multiParagraph.didExceedMaxLines)
        assertEquals(expected.length, layout.getLineEnd(0))
        assertFalse(layout.isLineEllipsized(0))
        return node.fetchSemanticsNode().layoutInfo.coordinates.localToRoot(Offset.Zero).y + layout.firstBaseline
    }

    private fun render(state: LearningExitUiState, actions: MutableList<LearningExitAction>) {
        compose.setContent { RlTheme { LearningExitDialog(state = state, onAction = actions::add) } }
        compose.waitForIdle()
        compose.runOnIdle {
            fun windows(view: View): Sequence<Window> = sequence {
                if (view is DialogWindowProvider) yield(view.window)
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) yieldAll(windows(view.getChildAt(index)))
                }
            }
            val found = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::windows).distinct().toList()
            assertEquals("The isolated renderer must own exactly one real Compose Dialog", 1, found.size)
            dialogWindow = found.single()
            assertTrue("The inspected window must be the Dialog, not the Activity", dialogWindow !== compose.activity.window)
        }
        compose.waitUntil(5_000) {
            dialogWindow.decorView.width == WIDTH_PX && dialogWindow.decorView.height == HEIGHT_PX
        }
        compose.waitForIdle()
        assertActualShortDialogWindow()
    }

    private fun assertActualShortDialogWindow() {
        compose.runOnIdle {
            assertEquals("This must not silently use the taller Activity window", WIDTH_PX, dialogWindow.decorView.width)
            assertEquals("This must not silently use the taller Activity window", HEIGHT_PX, dialogWindow.decorView.height)
            val resources = dialogWindow.decorView.resources
            assertEquals("Real Dialog resources must use font scale 2, not only an outer CompositionLocal", 2f,
                resources.configuration.fontScale, .001f)
            assertEquals("Real Dialog density dpi", 320, resources.configuration.densityDpi)
            assertEquals("Real Dialog density", 2f, resources.displayMetrics.density, .001f)
        }
        val layout = dialogRoot().fetchSemanticsNode().layoutInfo
        assertEquals("Real Dialog content width", WIDTH_PX, layout.width)
        assertEquals("Real Dialog content height", HEIGHT_PX, layout.height)
    }

    private fun dialogRoot(): SemanticsNodeInteraction {
        var root = compose.onNodeWithTag(CONTINUE_TAG, useUnmergedTree = true).fetchSemanticsNode()
        while (root.parent != null) root = requireNotNull(root.parent)
        val id = root.id
        return compose.onNode(SemanticsMatcher("the real exit Dialog's own root") { it.id == id }, useUnmergedTree = true)
    }

    private fun assertChoiceReadable(choice: Choice, snapshot: String) {
        val title = string(choice.title)
        val titleNode = compose.onNode(
            hasText(title) and !hasTestTag(choice.tag) and !hasAnyAncestor(hasTestTag(choice.tag)),
            useUnmergedTree = true,
        )
        assertWholeText(titleNode, title, expectedFontSizeSp = 21f)
        val description = string(choice.description)
        assertWholeText(compose.onNodeWithText(description, useUnmergedTree = true), description, expectedFontSizeSp = 13f)
        val button = compose.onNodeWithTag(choice.tag)
        makeReachable(button)
        button.assertIsDisplayed()
        assertButtonText(choice)
        captureIfRequested("$snapshot-${choice.suffix}")
    }

    private fun assertButtonText(choice: Choice) {
        val label = string(choice.title)
        val text = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(choice.tag)), useUnmergedTree = true)
        assertWholeText(text, label, button = compose.onNodeWithTag(choice.tag), expectedFontSizeSp = 16f)
    }

    private fun makeReachable(node: SemanticsNodeInteraction) {
        // Use only a real production scroll ancestor when one exists. The old non-scrolling
        // layout reaches the glyph assertion directly rather than failing in a test helper.
        if (hasAnyAncestor(hasScrollAction()).matches(node.fetchSemanticsNode())) node.performScrollTo()
        compose.waitForIdle()
    }

    private fun assertWholeText(
        node: SemanticsNodeInteraction,
        expected: String,
        button: SemanticsNodeInteraction? = null,
        expectedFontSizeSp: Float? = null,
    ): TextLayoutResult {
        makeReachable(node)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue("Resolve the actual production text layout before making a clipping verdict", it(layouts))
        }
        assertEquals("One actual text layout is required", 1, layouts.size)
        val layout = layouts.single()
        assertEquals("The measured production text must inherit the real Dialog density", 2f, layout.layoutInput.density.density, .001f)
        assertEquals("The measured production text must use font scale 2", 2f, layout.layoutInput.density.fontScale, .001f)
        expectedFontSizeSp?.let {
            assertEquals("Fit must not be obtained by shrinking the existing type token", it.sp, layout.layoutInput.style.fontSize)
        }
        assertEquals(expected, layout.layoutInput.text.text)
        assertTrue("The paragraph must contain a real line", layout.lineCount > 0)
        assertFalse("The renderer must not discard lines", layout.multiParagraph.didExceedMaxLines)
        assertEquals("The final original character must remain in the layout", expected.length, layout.getLineEnd(layout.lineCount - 1))
        val measuredNode = node.fetchSemanticsNode()
        val coordinates = measuredNode.layoutInfo.coordinates
        val parent = measuredNode.parent
        val coordinateDiagnostic = "node=${measuredNode.id} size=${coordinates.size} " +
            "origin=${coordinates.localToRoot(Offset.Zero)} bounds=${measuredNode.boundsInRoot}; " +
            "parent=${parent?.id} parentBounds=${parent?.boundsInRoot} " +
            "parentSize=${parent?.layoutInfo?.coordinates?.size} " +
            "parentOrigin=${parent?.layoutInfo?.coordinates?.localToRoot(Offset.Zero)} " +
            "align=${layout.layoutInput.style.textAlign} constraints=${layout.layoutInput.constraints}"
        val origin = realParagraphOrigin(layout, coordinates.localToRoot(Offset.Zero), coordinates.size.width, coordinateDiagnostic)
        var clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(node.fetchSemanticsNode().boundsInRoot)
            .intersect(dialogRoot().fetchSemanticsNode().boundsInRoot)
        if (button != null) clip = clip.intersect(button.fetchSemanticsNode().boundsInRoot)
        repeat(layout.lineCount) { line ->
            assertFalse("No line may be ellipsized: line=$line", layout.isLineEllipsized(line))
            val glyphs = (layout.getLineStart(line) until layout.getLineEnd(line, visibleEnd = true))
                .filter { !expected[it].isWhitespace() }.map { layout.getBoundingBox(it).translate(origin) }
            assertTrue("An entire line must fit its real clipped area: line=$line size=${layout.size} origin=$origin clip=$clip glyphs=$glyphs; $coordinateDiagnostic",
                clip.width > 0f && clip.height > 0f && glyphs.isNotEmpty() && glyphs.all { fits(it, clip) })
        }
        return layout
    }

    private fun realParagraphOrigin(layout: TextLayoutResult, nodeOrigin: Offset, actualWidth: Int, coordinates: String): Offset {
        val semanticWidth = layout.multiParagraph.width
        if (abs(semanticWidth - actualWidth) <= 1f) return nodeOrigin
        // Confirmed in the project's Compose Foundation 1.11.4 implementation:
        // ParagraphLayoutCache.layoutText uses finalConstraints(maxIntrinsicWidth) for
        // the drawn Paragraph; slowCreateTextLayoutResultOrNull reconstructs a wider
        // MultiParagraph with prevConstraints, while retaining the actual layoutSize.
        // The semantic paragraph is wider for Left/Start as well, but those glyphs
        // already start at zero: the parent Column/Button has positioned the node.
        // Only a Center line adds an extra semantic-only offset. A width mismatch
        // alone is neither an offset nor a clipping verdict.
        val lineLeft = layout.getLineLeft(0)
        val lineRight = layout.getLineRight(0)
        val diagnostic = "semanticWidth=$semanticWidth actualWidth=$actualWidth size=${layout.size} " +
            "intrinsic=${layout.multiParagraph.intrinsics.maxIntrinsicWidth} lines=${layout.lineCount} " +
            "lineLeft=$lineLeft lineRight=$lineRight; $coordinates"
        assertTrue("Resolve the actual drawn paragraph before a product verdict: $diagnostic",
            layout.lineCount == 1 && layout.size.width == actualWidth && semanticWidth > actualWidth &&
                ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt() == actualWidth)
        val alignment = layout.layoutInput.style.textAlign
        val leftAligned = alignment == TextAlign.Left ||
            ((alignment == TextAlign.Start || alignment == TextAlign.Unspecified) &&
                layout.layoutInput.layoutDirection == LayoutDirection.Ltr)
        // Side bearings/rounding can make glyph advances slightly narrower than the
        // intrinsic width. Left/Start is anchored at zero, not forced to touch the right.
        if (leftAligned && abs(lineLeft) <= 1f && lineRight in 0f..(actualWidth + 1f)) return nodeOrigin
        val offset = (semanticWidth - actualWidth) / 2f
        assertTrue("Only the verified single-line Center offset may be removed: $diagnostic",
            layout.layoutInput.style.textAlign == TextAlign.Center &&
                abs(lineLeft - offset) <= 1f && abs(lineRight - (offset + actualWidth)) <= 1f)
        return nodeOrigin - Offset(offset, 0f)
    }

    private fun captureIfRequested(suffix: String) {
        val phase = InstrumentationRegistry.getArguments().getString("learningExitSnapshot") ?: return
        require(phase in setOf("before", "after"))
        require(suffix.matches(Regex("[a-z-]+")))
        compose.waitForIdle()
        assertActualShortDialogWindow()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun anchors() = choices.map { compose.onNodeWithTag(it.tag).fetchSemanticsNode().boundsInRoot } +
            compose.onNodeWithText(string(R.string.learning_exit_page_title)).fetchSemanticsNode().boundsInRoot
        val before = anchors()
        val drawn = CountDownLatch(1)
        val listener = ViewTreeObserver.OnDrawListener { drawn.countDown() }
        instrumentation.runOnMainSync {
            val focused = WindowInspector.getGlobalWindowViews().filter { it.hasWindowFocus() }
            assertEquals("Only the isolated Dialog may be foreground for this screenshot", listOf(dialogWindow.decorView), focused)
            dialogWindow.decorView.viewTreeObserver.addOnDrawListener(listener)
            dialogWindow.decorView.postInvalidateOnAnimation()
        }
        try {
            check(drawn.await(10, TimeUnit.SECONDS)) { "The actual Dialog decor did not draw" }
        } finally {
            instrumentation.runOnMainSync {
                if (dialogWindow.decorView.viewTreeObserver.isAlive) dialogWindow.decorView.viewTreeObserver.removeOnDrawListener(listener)
            }
        }
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        compose.waitForIdle()
        assertActualShortDialogWindow()
        assertEquals("Do not capture a moving or different Dialog state", before, anchors())
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(WIDTH_PX, bitmap.width)
            assertEquals(HEIGHT_PX, bitmap.height)
            assertEquals("The captured frame must retain its anchors", before, anchors())
            var visibleInkSamples = 0
            for (y in 0 until bitmap.height step 20) for (x in 0 until bitmap.width step 20) {
                val color = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(color) < 170 && android.graphics.Color.green(color) < 170 &&
                    android.graphics.Color.blue(color) < 170) visibleInkSamples += 1
            }
            assertTrue("Reject a blank frame instead of using it as visual evidence", visibleInkSamples > 8)
            val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "visual-qa")
            check(folder.isDirectory || folder.mkdirs())
            val output = File(folder, "31-learning-exit-$suffix-$phase.png")
            check(output.canonicalFile.parentFile == folder.canonicalFile)
            output.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun state() = LearningExitUiState(OrdinaryLearningCheckpoint("render-only-chapter", "LR-09", "round-2"))
    private fun string(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private data class Choice(val tag: String, val suffix: String, val title: Int, val description: Int, val action: LearningExitAction)
    private val choices = listOf(
        Choice(CONTINUE_TAG, "continue", R.string.learning_exit_continue, R.string.learning_exit_continue_explanation,
            LearningExitAction.ContinueLearningRequested),
        Choice("learning-exit-pause", "pause", R.string.learning_exit_pause, R.string.learning_exit_pause_explanation,
            LearningExitAction.PauseLearningRequested),
        Choice("learning-exit-abandon", "abandon", R.string.learning_exit_abandon, R.string.learning_exit_abandon_explanation,
            LearningExitAction.AbandonChapterRequested),
    )

    private companion object {
        const val CONTINUE_TAG = "learning-exit-continue"
        const val WIDTH_PX = 1280
        const val HEIGHT_PX = 720
        fun fits(box: Rect, clip: Rect) = box.top >= clip.top - 1f && box.bottom <= clip.bottom + 1f &&
            box.left >= clip.left - 1f && box.right <= clip.right + 1f
    }
}
