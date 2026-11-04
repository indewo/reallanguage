package com.reallanguage.app.feature.knowledge.presentation

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.VocabularyEntry
import com.reallanguage.app.feature.knowledge.domain.VocabularyExample
import com.reallanguage.app.feature.knowledge.domain.VocabularySenseRecord
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
 * FR-VOC-001A / KB-02 selected-detail visibility, not pixel reconstruction.
 * One English group has twenty distinct, synthetic stored-sense identities.
 * Real Screen/paper and real 1829x860/d320/system-font1 touch coordinates;
 * only expandedSenseId is controlled in memory. No extra scrolling host,
 * fake density, repository, database, account, HTTP, TTS or AI is involved.
 */
class VocabularyExpandedSelectionVisibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var state: MutableState<VocabularyBookUiState>
    private val actions = mutableListOf<VocabularyBookAction>()
    private var touchSlop = 0f
    private var gestures = 0

    @Test
    fun enteringTheTwentiethSenseShowsItsSelectedMeaningAndFirstExampleWithoutAnotherDrag() {
        val original = fixture(longFirstDetail = false, expanded = null)
        render(original)
        capture("last-entry-initial")
        val target = original.entries.single().senses.last()
        val meaning = meaning(target)
        readByTouch(meaning, target.meaningZh)
        assertTrue("A genuine same-group twentieth row must require scrolling", gestures > 0)
        capture("last-entry-ready-to-click")
        val beforeClickGestures = gestures
        clickMeaning(target)
        capture("last-entry-selected")
        assertSelectedDetailsVisible(target, original, beforeClickGestures)
    }

    @Test
    fun switchingFromLongFirstDetailShowsTheSecondSenseInsteadOfKeepingTheOldContentOffset() {
        val original = fixture(longFirstDetail = true, expanded = "run-sense-01")
        render(original)
        capture("long-first-initial")
        val first = original.entries.single().senses.first()
        val second = original.entries.single().senses[1]
        val oldExample = compose.onNodeWithText(first.examples.first().textEn, useUnmergedTree = true)
        val oldLayout = paragraph(oldExample, first.examples.first().textEn)
        assertTrue("The first detail really is multiline, not a large spacer fixture", oldLayout.lineCount > 8)
        val viewport = clip(compose.onNodeWithTag("vocabulary-detail-content").fetchSemanticsNode())
        val secondBefore = raw(row(second.senseId).fetchSemanticsNode())
        assertTrue("Natural first-detail content places the next sense more than a viewport below the fold: row=$secondBefore viewport=$viewport",
            secondBefore.top > viewport.bottom + viewport.height)
        readByTouch(meaning(second), second.meaningZh)
        val oldRange = compose.onNodeWithTag("vocabulary-detail-content").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("The old expanded detail has a real saved scroll offset", oldRange.value() > viewport.height)
        capture("long-first-second-ready-to-click")
        val beforeClickGestures = gestures
        clickMeaning(second)
        capture("long-first-second-selected")
        assertSelectedDetailsVisible(second, original, beforeClickGestures)
        compose.onNodeWithText(first.examples.first().textEn, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun assertSelectedDetailsVisible(
        target: VocabularySenseRecord,
        original: VocabularyBookUiState,
        beforeClickGestures: Int,
    ) {
        // No test drag, ScrollTo, or semantic scrolling is allowed after the
        // click. The production layout may use any suitable positioning method.
        assertEquals(beforeClickGestures, gestures)
        assertEquals(listOf(VocabularyBookAction.SenseSelected(target.senseId)), actions)
        compose.runOnIdle {
            assertEquals(target.senseId, state.value.expandedSenseId)
            assertEquals(original.entries, state.value.entries)
        }
        val selected = meaning(target)
        val selectedLayout = paragraph(selected, target.meaningZh)
        val example = compose.onNodeWithText(target.examples.first().textEn, useUnmergedTree = true)
        val exampleLayout = paragraph(example, target.examples.first().textEn)
        val selectedGlyphs = (0 until selectedLayout.lineCount).map { geometry(selected, selectedLayout, it) }
        val firstLine = geometry(example, exampleLayout, 0)
        val diagnostic = "sense=${target.senseId} selected=$selectedGlyphs firstExample=$firstLine scroll=${scrollPositions()}"
        assertTrue("The newly selected short meaning must be visible without compensating test scrolling: $diagnostic",
            selectedGlyphs.all { it.visible })
        assertTrue("The selected detail's first English line must actually enter the paper viewport: $diagnostic", firstLine.visible)
        assertTrue("A real click cannot be satisfied by another sense's example",
            raw(row(target.senseId).fetchSemanticsNode()).bottom <= firstLine.boxes.minOf { it.top } + 1f)
        compose.onNodeWithText(requireNotNull(target.representativeScene), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("vocabulary-representative-scene-image").assertDoesNotExist()
    }

    private fun render(original: VocabularyBookUiState) {
        state = mutableStateOf(original)
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.window.decorView.width == WIDTH && compose.activity.window.decorView.height == HEIGHT }
        compose.setContent { RlTheme { RlPaperBackground {
            touchSlop = LocalViewConfiguration.current.touchSlop
            VocabularyBookScreen(state.value) { action ->
                actions += action
                if (action is VocabularyBookAction.SenseSelected) {
                    state.value = state.value.copy(expandedSenseId = action.senseId)
                }
            }
        } } }
        compose.waitForIdle()
        assertWindow()
    }

    private fun rowMatcher(id: String) = hasTestTag("vocabulary-sense-$id") or hasTestTag("vocabulary-detail-sense-$id")
    private fun row(id: String) = compose.onNode(rowMatcher(id))
    private fun meaning(sense: VocabularySenseRecord) = compose.onNode(
        hasText(sense.meaningZh) and hasAnyAncestor(rowMatcher(sense.senseId)), useUnmergedTree = true)
    private fun byId(id: Int) = compose.onNode(SemanticsMatcher("Current scrolling node $id") { it.id == id }, useUnmergedTree = true)
    private fun raw(node: SemanticsNode): Rect {
        val coordinates = node.layoutInfo.coordinates
        val origin = coordinates.localToRoot(Offset.Zero)
        return Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
    }
    private fun clip(node: SemanticsNode): Rect {
        val coordinates = node.layoutInfo.coordinates
        return coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(node.boundsInRoot).intersect(Rect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()))
    }

    private fun paragraph(node: SemanticsNodeInteraction, value: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals("Resolve a real paragraph before a product visibility verdict", 1, layouts.size)
        return layouts.single().also {
            assertEquals(value, it.layoutInput.text.text)
            assertEquals(2f, it.layoutInput.density.density, .001f)
            assertEquals(1f, it.layoutInput.density.fontScale, .001f)
            assertTrue(it.lineCount > 0)
            assertFalse(it.multiParagraph.didExceedMaxLines)
            assertEquals(value.length, it.getLineEnd(it.lineCount - 1))
            repeat(it.lineCount) { line -> assertFalse(it.isLineEllipsized(line)) }
        }
    }

    private data class Glyphs(val clip: Rect, val boxes: List<Rect>) {
        val visible get() = clip.width > 0f && clip.height > 0f && boxes.isNotEmpty() && boxes.all {
            it.left >= clip.left - 1f && it.top >= clip.top - 1f && it.right <= clip.right + 1f && it.bottom <= clip.bottom + 1f
        }
    }
    private fun geometry(node: SemanticsNodeInteraction, layout: TextLayoutResult, line: Int): Glyphs {
        val semantic = node.fetchSemanticsNode()
        val coordinates = semantic.layoutInfo.coordinates
        var origin = coordinates.localToRoot(Offset.Zero)
        val actualWidth = coordinates.size.width
        val paragraphWidth = layout.multiParagraph.width
        if (abs(paragraphWidth - actualWidth) > 1f) {
            val left = layout.getLineLeft(0)
            val right = layout.getLineRight(0)
            assertTrue("Only resolve the known single-line paragraph cache alignment case",
                layout.lineCount == 1 && layout.size.width == actualWidth && paragraphWidth > actualWidth &&
                    ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt() == actualWidth)
            val align = layout.layoutInput.style.textAlign
            val isLeft = align == TextAlign.Left || ((align == TextAlign.Start || align == TextAlign.Unspecified) &&
                layout.layoutInput.layoutDirection == LayoutDirection.Ltr)
            if (!(isLeft && abs(left) <= 1f && right in 0f..(actualWidth + 1f))) {
                val offset = (paragraphWidth - actualWidth) / 2f
                assertTrue("Do not compensate clipping or change a vertical origin",
                    align == TextAlign.Center && abs(left - offset) <= 1f && abs(right - (offset + actualWidth)) <= 1f)
                origin -= Offset(offset, 0f)
            }
        }
        val text = layout.layoutInput.text.text
        val boxes = (layout.getLineStart(line) until layout.getLineEnd(line, visibleEnd = true))
            .filter { !text[it].isWhitespace() }.map { layout.getBoundingBox(it).translate(origin) }
        return Glyphs(clip(semantic), boxes)
    }

    /** Only reaches the meaning before selection; never called after the click. */
    private fun readByTouch(node: SemanticsNodeInteraction, value: String) {
        val layout = paragraph(node, value)
        repeat(layout.lineCount) { line ->
            var glyphs = geometry(node, layout, line)
            var attempts = 0
            while (!glyphs.visible && attempts < 30) {
                val centerY = glyphs.boxes.map { it.center.y }.average().toFloat()
                val scroll = generateSequence(node.fetchSemanticsNode().parent) { it.parent }.firstOrNull { ancestor ->
                    val range = ancestor.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) ?: return@firstOrNull false
                    val area = clip(ancestor)
                    area.height > touchSlop + 24f && area.width > 24f &&
                        if (centerY > area.center.y) range.value() < range.maxValue() - 1f else range.value() > 1f
                } ?: break
                val area = clip(scroll)
                val towardLater = centerY > area.center.y
                val distance = (abs(centerY - area.center.y) + touchSlop).coerceIn(touchSlop + 4f, area.height - 16f)
                val x = glyphs.boxes.first().center.x.coerceIn(area.left + 8f, area.right - 8f)
                val start = Offset(x, if (towardLater) area.bottom - 8f else area.top + 8f)
                val end = start + Offset(0f, if (towardLater) -distance else distance)
                val rootOrigin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
                val before = scroll.config[SemanticsProperties.VerticalScrollAxisRange].value()
                compose.onRoot().performTouchInput {
                    down(start - rootOrigin)
                    repeat(20) { step -> moveTo(start - rootOrigin + (end - start) * ((step + 1) / 20f), 60L) }
                    repeat(6) { advanceEventTime(40L); moveTo(end - rootOrigin, 0L) }
                    up()
                }
                compose.waitForIdle()
                val after = byId(scroll.id).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
                assertTrue("A drag alone is not evidence that the real viewport moved: before=$before after=$after", abs(after - before) > .5f)
                attempts++; gestures++
                glyphs = geometry(node, layout, line)
            }
            assertTrue("The target meaning's actual glyphs must be visible before physical selection: text=$value line=$line attempts=$attempts glyphs=$glyphs", glyphs.visible)
        }
        assertEquals("Dragging may not select a sense or request playback", emptyList<VocabularyBookAction>(), actions)
    }

    private fun clickMeaning(sense: VocabularySenseRecord) {
        val node = meaning(sense)
        val layout = paragraph(node, sense.meaningZh)
        val glyphs = geometry(node, layout, layout.lineCount - 1)
        assertTrue(glyphs.visible)
        val button = row(sense.senseId).assertIsEnabled().fetchSemanticsNode()
        assertTrue(button.config.contains(SemanticsActions.OnClick))
        assertTrue("Preserve the actual 48dp minimum control", button.layoutInfo.height >= 96)
        val point = glyphs.boxes.last().center
        assertTrue("The physical glyph must be inside the real control clip", clip(button).contains(point))
        val rootOrigin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
        compose.onRoot().performTouchInput { click(point - rootOrigin) }
        compose.waitForIdle()
    }

    private fun scrollPositions(): List<Pair<Int, Float>> = compose.onAllNodes(
        SemanticsMatcher("Actual scroll ranges") { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) },
        useUnmergedTree = true,
    ).fetchSemanticsNodes().map { it.id to it.config[SemanticsProperties.VerticalScrollAxisRange].value() }

    private fun assertWindow() {
        val resources = compose.activity.resources
        assertEquals(320, resources.configuration.densityDpi)
        assertEquals(2f, resources.displayMetrics.density, .001f)
        assertEquals(1f, resources.configuration.fontScale, .001f)
        assertEquals(WIDTH, compose.onRoot().fetchSemanticsNode().layoutInfo.width)
        assertEquals(HEIGHT, compose.onRoot().fetchSemanticsNode().layoutInfo.height)
    }

    private fun capture(label: String) {
        val phase = InstrumentationRegistry.getArguments().getString("vocabularySelectionSnapshot") ?: "before"
        require(phase == "before" || phase == "after")
        require(label in setOf("last-entry-initial", "last-entry-ready-to-click", "last-entry-selected",
            "long-first-initial", "long-first-second-ready-to-click", "long-first-second-selected"))
        compose.waitForIdle()
        assertWindow()
        val identity = state.value.expandedSenseId
        val position = scrollPositions()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val decor = compose.activity.window.decorView
        val latch = CountDownLatch(1)
        val listener = ViewTreeObserver.OnDrawListener { latch.countDown() }
        instrumentation.runOnMainSync {
            check(decor.hasWindowFocus())
            decor.viewTreeObserver.addOnDrawListener(listener)
            decor.postInvalidateOnAnimation()
        }
        try { check(latch.await(10, TimeUnit.SECONDS)) { "Isolated vocabulary window did not draw" } }
        finally { instrumentation.runOnMainSync { if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(listener) } }
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        compose.waitForIdle()
        assertEquals("Capture cannot reposition the inspected content", position, scrollPositions())
        assertEquals(identity, state.value.expandedSenseId)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(WIDTH, bitmap.width); assertEquals(HEIGHT, bitmap.height)
            var ink = 0
            for (y in 0 until HEIGHT step 16) for (x in 0 until WIDTH step 16) {
                val color = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(color) < 170 && android.graphics.Color.green(color) < 170 && android.graphics.Color.blue(color) < 170) ink++
            }
            assertTrue("Reject a blank capture", ink > 8)
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa")
            check(directory.isDirectory || directory.mkdirs())
            val output = File(directory, "41-vocabulary-selection-$label-$phase.png")
            check(output.canonicalFile.parentFile == directory.canonicalFile)
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun fixture(longFirstDetail: Boolean, expanded: String?): VocabularyBookUiState {
        val records = (1..20).map { number ->
            val id = "run-sense-${number.toString().padStart(2, '0')}"
            val meaning = when (number) { 1 -> "跑步"; 2 -> "经营"; 3 -> "程序运行"; 4 -> "机器运转"; else -> "隔离已存义项 $number" }
            val examples = (1..3).map { index ->
                val english = if (number == 1 && longFirstDetail) {
                    "Example $index. " + "The team checks the daily schedule and keeps the service running smoothly. ".repeat(6).trim()
                } else "Stored example $index for sense $number."
                val chinese = if (number == 1 && longFirstDetail) {
                    "第${index}条隔离已存例句。" + "团队检查每天的计划，并让服务继续正常开展。".repeat(6)
                } else "第${number}个词义的第${index}条已存例句。"
                VocabularyExample(english, chinese, english)
            }
            VocabularySenseRecord(id, "run", meaning, KnowledgeStatus.LEARNING, 0, setOf("CET_6"), number.toLong(),
                phonetic = "/rʌn/", pronunciationText = "run", representativeScene = "词义 $number 的已存场景",
                examples = examples, focusOccurrenceCount = 2, contextOccurrenceCount = 3)
        }
        return VocabularyBookUiState("六级", listOf(VocabularyEntry("run", records)), expandedSenseId = expanded, loading = false)
    }

    private companion object { const val WIDTH = 1829; const val HEIGHT = 860 }
}
