package com.reallanguage.app.feature.knowledge.presentation

import android.content.pm.ActivityInfo
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.PatternExample
import com.reallanguage.app.feature.knowledge.domain.PatternRecord
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
 * Render-only page 43 fixtures. The device owner supplies real 1774x887/d320/font1
 * or 1280x720/d320/system-font2; no fake density, additional scroll host, HTTP,
 * stored learning data, speech engine or knowledge mutation is used here.
 */
class PatternBookTextVisibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var widthPx = 1774
    private var heightPx = 887
    private var fontScale = 1f
    private var touchSlop = 0f
    private var physicalGestures = 0
    private val touchDiagnostics = mutableListOf<String>()
    private var actionCount: () -> Int = { 0 }

    @Test
    fun referenceExamplesPlaceEnglishAboveTheirOwnChineseTranslation() {
        val state = referenceState()
        val actions = render(state)
        val before = scrollPosition()
        captureIfRequested("order")
        state.patterns.first().examples.forEachIndexed { index, example ->
            val english = text(example.textEn)
            val chinese = text(example.textZh)
            val englishLayout = layout(english, example.textEn, 11f)
            val chineseLayout = layout(chinese, example.textZh, 11f)
            val englishGeometry = geometry(english, englishLayout)
            val chineseGeometry = geometry(chinese, chineseLayout)
            // Compare real paragraph placement, not matching Text semantics in two
            // horizontal columns. No clipping assumption is needed for this relation.
            assertTrue("Example $index must put Chinese below the complete English paragraph: " +
                "englishOrigin=${englishGeometry.origin} englishSize=${englishLayout.size} chineseOrigin=${chineseGeometry.origin}",
                englishGeometry.origin.y + englishLayout.size.height <= chineseGeometry.origin.y + 1f)
            assertEquals("Example $index bilingual paragraphs must start on the same text track",
                englishGeometry.origin.x, chineseGeometry.origin.x, 1f)
        }
        assertEquals("Inspect the initial state without scrolling", before, scrollPosition())
        assertTrue(actions.isEmpty())
    }

    @Test
    fun referenceInitialThreeExamplesAndDetailActionsHaveFullyVisibleGlyphs() {
        val state = referenceState()
        val actions = render(state)
        val before = scrollPosition()
        assertEquals(0f, before.first, .001f)
        captureIfRequested("initial")
        state.patterns.first().examples.forEachIndexed { index, example ->
            assertCompleteVisible(text(example.textEn), example.textEn, 11f)
            assertCompleteVisible(text(example.textZh), example.textZh, 11f)
            val button = compose.onNodeWithTag("pattern-example-p1-$index")
            button.assertIsEnabled()
            assertTrue("Do not fit the reference by shrinking the 48dp example touch target",
                button.fetchSemanticsNode().layoutInfo.height >= 96)
        }
        listOf("收回" to "pattern-detail-collapse", "移出句式本" to "pattern-detail-remove").forEach { (label, tag) ->
            compose.onNodeWithTag(tag).assertIsEnabled()
            assertCompleteVisible(text(label), label, 16f)
        }
        assertEquals("No per-item ScrollTo may hide an initial clipping defect", before, scrollPosition())
        assertTrue(actions.isEmpty())
        // Do not require the following three collapsed rows to fit this screen by
        // reducing font sizes or the existing minimum touch size to the raster's rows.
    }

    @Test
    fun shortSystemDoubleFontCanTouchScrollEveryExampleAndStatsThenUseExactActions() {
        val state = referenceState(longExamples = true)
        val actions = render(state, short = true)
        val initialScroll = scrollPosition()
        state.patterns.first().examples.forEachIndexed { index, example ->
            for (value in listOf(example.textEn, example.textZh)) {
                val node = text(value)
                val paragraph = layout(node, value, 11f)
                assertTrue("The real system-font2 fixture must wrap", paragraph.lineCount > 3)
                // Each actual line must become readable, not merely the final string
                // exist in semantics. Gestures only use the production list viewport.
                repeat(paragraph.lineCount) { line -> reachLineByTouch(node, paragraph, line) }
            }
            val button = compose.onNodeWithTag("pattern-example-p1-$index").assertIsEnabled()
            assertTrue("The long example retains its 48dp minimum touch size", button.fetchSemanticsNode().layoutInfo.height >= 96)
            touchVisibleTextInsideAction(text(example.textZh), example.textZh, 11f, button)
        }
        for (value in listOf("连续会写 2 次", "连续会说 1 次", "共出现 2 次")) {
            val node = text(value)
            val paragraph = layout(node, value, 13f)
            repeat(paragraph.lineCount) { line -> reachLineByTouch(node, paragraph, line) }
        }
        listOf("收回" to "pattern-detail-collapse", "移出句式本" to "pattern-detail-remove").forEach { (label, tag) ->
            val node = text(label)
            val paragraph = layout(node, label, 16f)
            repeat(paragraph.lineCount) { line -> reachLineByTouch(node, paragraph, line) }
            assertCompleteVisible(node, label, 16f)
            touchVisibleTextInsideAction(node, label, 16f, compose.onNodeWithTag(tag).assertIsEnabled())
        }
        captureIfRequested("font2-actions")
        assertTrue("Actual touch must move the production list", physicalGestures > 0 && scrollPosition().first > initialScroll.first)
        assertEquals("Physical action diagnostics:\n${touchDiagnostics.joinToString("\n")}", listOf(
            PatternBookAction.ExamplePronunciationRequested("p1", 0),
            PatternBookAction.ExamplePronunciationRequested("p1", 1),
            PatternBookAction.ExamplePronunciationRequested("p1", 2),
            PatternBookAction.CollapseRequested,
            PatternBookAction.RemoveRequested,
        ), actions)
        // The collector intentionally does not apply state transitions: these are
        // Screen action/reading tests, not VM, speech, removal or database evidence.
    }

    @Test
    fun shortSystemDoubleFontLongPatternRowCanReadAndOpenExactPattern() {
        val pattern = referenceState().patterns.first().copy(
            structureEn = "The more time someone spends preparing for an unfamiliar situation, " +
                "the more confidently they can + 动词原形",
            functionZh = "用于说明为尚不熟悉的情境投入更多准备时间后，完成相关行动时会表现得更从容。" +
                "前半部分描述准备程度的变化，后半部分表达与之相关的结果，不能省略它们之间的关系。" +
                "当前句式的完整功能说明到这里结束。",
        )
        val state = referenceState().copy(
            patterns = listOf(pattern) + referenceState().patterns.drop(1),
            expandedPatternId = null,
        )
        val actions = render(state, short = true)
        val initialScroll = scrollPosition()
        captureIfRequested("font2-long-row-initial")
        for ((value, fontSp) in listOf(pattern.structureEn to 15f, pattern.functionZh to 12f)) {
            val node = text(value)
            val paragraph = layout(node, value, fontSp)
            assertTrue("The real system-font2 row must contain multiple complete lines", paragraph.lineCount > 3)
            repeat(paragraph.lineCount) { line -> reachLineByTouch(node, paragraph, line) }
        }
        val rowAction = compose.onNode(
            hasClickAction() and hasAnyDescendant(hasText(pattern.functionZh)),
            useUnmergedTree = true,
        ).assertIsEnabled()
        assertTrue("The existing row retains its 48dp minimum touch target",
            rowAction.fetchSemanticsNode().layoutInfo.height >= 96)
        assertTrue("Reading the long row requires actual production-list movement",
            physicalGestures > 0 && scrollPosition().first > initialScroll.first)
        // Click the first glyph of the final visible meaning line, not a semantics
        // action or the button's center, which could conceal a curved hit-test gap.
        touchVisibleTextInsideAction(text(pattern.functionZh), pattern.functionZh, 12f, rowAction,
            firstTouchCaptureLabel = "font2-long-row-touch")
        assertEquals("Physical row action diagnostics:\n${touchDiagnostics.joinToString("\n")}",
            listOf(PatternBookAction.PatternSelected("p1")), actions)
        // This fixture only receives the exact open action; no stored pattern,
        // pronunciation request, expanded detail or database mutation is created.
    }

    private fun render(state: PatternBookUiState, short: Boolean = false): MutableList<PatternBookAction> {
        widthPx = if (short) 1280 else 1774
        heightPx = if (short) 720 else 887
        fontScale = if (short) 2f else 1f
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.window.decorView.width == widthPx && compose.activity.window.decorView.height == heightPx }
        val actions = mutableListOf<PatternBookAction>()
        actionCount = { actions.size }
        compose.setContent { RlTheme {
            touchSlop = LocalViewConfiguration.current.touchSlop
            PatternBookScreen(state, actions::add)
        } }
        compose.waitForIdle()
        assertActualWindow()
        viewport().assertIsDisplayed()
        return actions
    }

    private fun assertActualWindow() {
        val resources = compose.activity.resources
        assertEquals(320, resources.configuration.densityDpi)
        assertEquals(2f, resources.displayMetrics.density, .001f)
        assertEquals("System font scale, never a density wrapper", fontScale, resources.configuration.fontScale, .001f)
        assertEquals(widthPx, compose.activity.window.decorView.width)
        assertEquals(heightPx, compose.activity.window.decorView.height)
        val root = compose.onRoot().fetchSemanticsNode().layoutInfo
        assertEquals(widthPx, root.width)
        assertEquals(heightPx, root.height)
    }

    private fun viewport() = compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("pattern-book-list")))
    private fun text(value: String) = compose.onNodeWithText(value, useUnmergedTree = true)
    private fun windowBounds() = Rect(0f, 0f, widthPx.toFloat(), heightPx.toFloat())
    private fun scrollPosition(): Pair<Float, Float> {
        val range = viewport().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        return range.value() to range.maxValue()
    }

    private fun layout(node: SemanticsNodeInteraction, value: String, fontSp: Float): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals("One real paragraph is required", 1, layouts.size)
        val paragraph = layouts.single()
        assertEquals(value, paragraph.layoutInput.text.text)
        assertEquals(2f, paragraph.layoutInput.density.density, .001f)
        assertEquals(fontScale, paragraph.layoutInput.density.fontScale, .001f)
        assertTrue("Do not shrink the existing text to pass visibility: minimum=$fontSp, actual=${paragraph.layoutInput.style.fontSize}",
            paragraph.layoutInput.style.fontSize.value >= fontSp)
        assertFalse(paragraph.multiParagraph.didExceedMaxLines)
        assertTrue(paragraph.lineCount > 0)
        assertEquals(value.length, paragraph.getLineEnd(paragraph.lineCount - 1))
        repeat(paragraph.lineCount) { assertFalse(paragraph.isLineEllipsized(it)) }
        return paragraph
    }

    private data class GlyphGeometry(val origin: Offset, val clip: Rect, val boxes: List<Rect>) {
        val visible get() = clip.width > 0 && clip.height > 0 && boxes.isNotEmpty() && boxes.all { box ->
            box.left >= clip.left - 1f && box.top >= clip.top - 1f && box.right <= clip.right + 1f && box.bottom <= clip.bottom + 1f
        }
    }

    private fun geometry(node: SemanticsNodeInteraction, paragraph: TextLayoutResult, line: Int? = null): GlyphGeometry {
        val semantic = node.fetchSemanticsNode()
        val coordinates = semantic.layoutInfo.coordinates
        val origin = paragraphOrigin(paragraph, coordinates.localToRoot(Offset.Zero), coordinates.size.width)
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(semantic.boundsInRoot).intersect(viewport().fetchSemanticsNode().boundsInRoot).intersect(windowBounds())
        val source = paragraph.layoutInput.text.text
        val lines = if (line == null) 0 until paragraph.lineCount else line..line
        val boxes = lines.flatMap { row ->
            (paragraph.getLineStart(row) until paragraph.getLineEnd(row, visibleEnd = true))
                .filter { !source[it].isWhitespace() }.map { paragraph.getBoundingBox(it).translate(origin) }
        }
        return GlyphGeometry(origin, clip, boxes)
    }

    private fun paragraphOrigin(layout: TextLayoutResult, origin: Offset, actualWidth: Int): Offset {
        val semanticWidth = layout.multiParagraph.width
        if (abs(semanticWidth - actualWidth) <= 1f) return origin
        // Existing Compose 1.11.4 ParagraphLayoutCache discrepancy, proven by page31:
        // a reconstructed semantic paragraph may retain old wider constraints. Only
        // an established single-line Center offset is removed; never a y/clip offset.
        val left = layout.getLineLeft(0)
        val right = layout.getLineRight(0)
        val diagnostic = "semanticWidth=$semanticWidth actualWidth=$actualWidth size=${layout.size} lines=${layout.lineCount} left=$left right=$right"
        assertTrue("Resolve real paragraph geometry before a product verdict: $diagnostic",
            layout.lineCount == 1 && layout.size.width == actualWidth && semanticWidth > actualWidth &&
                ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt() == actualWidth)
        val align = layout.layoutInput.style.textAlign
        val isLeft = align == TextAlign.Left || ((align == TextAlign.Start || align == TextAlign.Unspecified) &&
            layout.layoutInput.layoutDirection == LayoutDirection.Ltr)
        if (isLeft && abs(left) <= 1f && right in 0f..(actualWidth + 1f)) return origin
        val offset = (semanticWidth - actualWidth) / 2f
        assertTrue("Only the verified Center offset may be removed: $diagnostic",
            align == TextAlign.Center && abs(left - offset) <= 1f && abs(right - (offset + actualWidth)) <= 1f)
        return origin - Offset(offset, 0f)
    }

    private fun assertCompleteVisible(node: SemanticsNodeInteraction, value: String, fontSp: Float) {
        val paragraph = layout(node, value, fontSp)
        val actual = geometry(node, paragraph)
        assertTrue("Every actual glyph must fit the current clipped viewport: size=${paragraph.size} lines=${paragraph.lineCount} geometry=$actual scroll=${scrollPosition()}", actual.visible)
    }

    private fun reachLineByTouch(node: SemanticsNodeInteraction, paragraph: TextLayoutResult, line: Int) {
        var current = geometry(node, paragraph, line)
        val initiallyVisible = current.visible
        val before = scrollPosition()
        var gestures = 0
        var unchanged = 0
        while (!current.visible && gestures < 100 && unchanged < 4) {
            val bounds = viewport().fetchSemanticsNode().boundsInRoot.intersect(windowBounds())
            assertTrue("Production viewport must permit a real drag", bounds.height > touchSlop + 12f)
            val top = current.boxes.minOf { it.top }
            val bottom = current.boxes.maxOf { it.bottom }
            val directionDown = bottom > bounds.bottom || top >= bounds.top
            val position = scrollPosition()
            if ((directionDown && position.first >= position.second - 1f) || (!directionDown && position.first <= 1f)) break
            val previous = current
            val delta = if (directionDown) bottom - bounds.bottom else bounds.top - top
            val slack = (bounds.height - (bottom - top)).coerceAtLeast(0f)
            viewport().performTouchInput {
                val distance = (delta.coerceAtLeast(0f) + slack / 2f + touchSlop).coerceIn(touchSlop + 2f, bounds.height - 8f)
                val start = if (directionDown) bounds.height - 4f else 4f
                swipe(Offset(bounds.width / 2f, start), Offset(bounds.width / 2f, start + if (directionDown) -distance else distance), 300)
            }
            compose.waitForIdle()
            physicalGestures++
            gestures++
            current = geometry(node, paragraph, line)
            unchanged = if (current == previous && scrollPosition() == position) unchanged + 1 else 0
        }
        val diagnostic = "line=$line size=${paragraph.size} geometry=$current gestures=$gestures scroll=${scrollPosition()}"
        if (!current.visible && gestures == 100 && unchanged < 4) {
            val position = scrollPosition()
            assertTrue("Gesture budget exhausted before a product verdict: $diagnostic", position.first <= 1f || position.first >= position.second - 1f)
        }
        assertTrue("The complete line's glyphs must become visible by real touch: $diagnostic", current.visible)
        if (!initiallyVisible) assertTrue("A dispatched swipe alone is not evidence of movement", gestures > 0 && scrollPosition() != before)
    }

    private fun touchVisibleTextInsideAction(
        node: SemanticsNodeInteraction,
        value: String,
        fontSp: Float,
        action: SemanticsNodeInteraction,
        firstTouchCaptureLabel: String = "font2-first-example-touch",
    ) {
        val paragraph = layout(node, value, fontSp)
        val visible = geometry(node, paragraph, paragraph.lineCount - 1)
        assertTrue(visible.visible)
        val point = visible.boxes.first().center
        val actionNode = action.fetchSemanticsNode()
        assertTrue("Click an actually visible glyph inside the promised action", actionNode.boundsInRoot.contains(point))
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val ancestors = generateSequence(node.fetchSemanticsNode()) { it.parent }.joinToString(";") { ancestor ->
            val coordinates = ancestor.layoutInfo.coordinates
            val shape = ancestor.config.firstOrNull { it.key.name == "Shape" }?.value
            "id=${ancestor.id},size=${coordinates.size},origin=${coordinates.localToRoot(Offset.Zero)}," +
                "bounds=${ancestor.boundsInRoot},clip=${coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)}," +
                "click=${ancestor.config.contains(SemanticsActions.OnClick)},shape=$shape"
        }
        val decor = compose.activity.window.decorView
        val screenOrigin = IntArray(2)
        val visibleWindow = android.graphics.Rect()
        decor.getLocationOnScreen(screenOrigin)
        decor.getWindowVisibleDisplayFrame(visibleWindow)
        val beforeScroll = scrollPosition()
        val beforeCount = actionCount()
        val hasAction = actionNode.config.contains(SemanticsActions.OnClick) &&
            actionNode.config[SemanticsActions.OnClick].action != null
        // A rectangular Semantics bound cannot establish that this pixel is inside
        // a rounded outline. Capture the unchanged first actual hit point's frame.
        if (touchDiagnostics.isEmpty()) captureIfRequested(firstTouchCaptureLabel)
        compose.onRoot().performTouchInput { click(point - rootBounds.topLeft) }
        compose.waitForIdle()
        touchDiagnostics += "tap=${touchDiagnostics.size},point=$point,inputPoint=${point - rootBounds.topLeft}," +
            "paragraphOrigin=${visible.origin},glyphClip=${visible.clip},lastGlyph=${visible.boxes.first()}," +
            "actionBounds=${actionNode.boundsInRoot},hasOnClick=$hasAction," +
            "disabled=${actionNode.config.contains(SemanticsProperties.Disabled)}," +
            "scrollBefore=$beforeScroll,scrollAfter=${scrollPosition()},actionsBefore=$beforeCount,actionsAfter=${actionCount()}," +
            "window=${decor.width}x${decor.height},screenOrigin=${screenOrigin.toList()},visibleWindow=$visibleWindow," +
            "focused=${decor.hasWindowFocus()},ime=${androidx.core.view.ViewCompat.getRootWindowInsets(decor)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())}," +
            "ancestors=[$ancestors]"
    }

    private fun captureIfRequested(label: String) {
        val phase = InstrumentationRegistry.getArguments().getString("patternTextSnapshot") ?: return
        require(phase in setOf("before", "after") && label in setOf("order", "initial", "font2-actions", "font2-first-example-touch",
            "font2-long-row-initial", "font2-long-row-touch"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.waitForIdle()
        assertActualWindow()
        val before = scrollPosition()
        val decor = compose.activity.window.decorView
        val drawn = CountDownLatch(1)
        val listener = ViewTreeObserver.OnDrawListener { drawn.countDown() }
        instrumentation.runOnMainSync {
            assertTrue("Capture only the foreground isolated page43 window", decor.hasWindowFocus())
            decor.viewTreeObserver.addOnDrawListener(listener)
            decor.postInvalidateOnAnimation()
        }
        try { check(drawn.await(10, TimeUnit.SECONDS)) { "Actual page43 window did not draw" } }
        finally { instrumentation.runOnMainSync { if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(listener) } }
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        compose.waitForIdle()
        assertEquals(before, scrollPosition())
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(widthPx, image.width); assertEquals(heightPx, image.height)
            var inkSamples = 0
            for (y in 0 until image.height step 16) for (x in 0 until image.width step 16) {
                val color = image.getPixel(x, y)
                if (android.graphics.Color.red(color) < 170 && android.graphics.Color.green(color) < 170 &&
                    android.graphics.Color.blue(color) < 170) inkSamples++
            }
            assertTrue("A blank frame is not visual evidence", inkSamples > 8)
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa")
            check(directory.isDirectory || directory.mkdirs())
            val output = File(directory, "43-text-$label-$phase.png")
            check(output.canonicalFile.parentFile == directory.canonicalFile)
            output.outputStream().use { check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }

    private fun referenceState(longExamples: Boolean = false): PatternBookUiState {
        val originals = listOf(
            PatternExample("I worked up the courage to apply for the scholarship.", "我鼓起勇气申请了那项奖学金。", "I worked up the courage to apply for the scholarship."),
            PatternExample("It took me weeks to work up the courage to talk to him.", "我花了好几周才鼓起勇气和他说话。", "It took me weeks to work up the courage to talk to him."),
            PatternExample("She finally worked up the courage to leave her old job.", "她终于鼓起勇气离开了那份旧工作。", "She finally worked up the courage to leave her old job."),
        )
        val examples = if (!longExamples) originals else originals.mapIndexed { index, example ->
            val english = example.textEn + " After careful preparation and several conversations with trusted friends, the next step felt possible. " +
                "This is the complete final sentence of example ${index + 1}."
            val chinese = example.textZh + "经过认真准备，并与可信赖的朋友多次交流后，接下来要做的事情终于变得可行。".repeat(3) + "这是第${index + 1}个例句的最后一行。"
            PatternExample(english, chinese, english)
        }
        val detail = PatternRecord("p1", "work up the courage to + 动词", "鼓起勇气做某事", KnowledgeStatus.MASTERED,
            KnowledgeStatus.LEARNING, 2, 2, "终于决定去做一直犹豫的事", examples,
            writingConsecutiveSuccessCount = 2, speakingConsecutiveSuccessCount = 1,
            structureParts = listOf("work", "up", "the courage", "to", "+ 动词"))
        return PatternBookUiState(listOf(detail,
            PatternRecord("p2", "I'd like to…", "礼貌表达意愿", KnowledgeStatus.MASTERED, KnowledgeStatus.MASTERED, 3, 3),
            PatternRecord("p3", "It took me…to…", "表达完成某事所花时间", KnowledgeStatus.LEARNING, KnowledgeStatus.TENTATIVE, 0, 1),
            PatternRecord("p4", "The reason is that…", "说明原因", KnowledgeStatus.TENTATIVE, KnowledgeStatus.TENTATIVE, 0, 1),
        ), "p1", loading = false)
    }
}
