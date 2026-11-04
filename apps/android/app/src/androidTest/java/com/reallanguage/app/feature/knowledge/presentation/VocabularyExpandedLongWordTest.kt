package com.reallanguage.app.feature.knowledge.presentation

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.VocabularyEntry
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
 * Existing expanded Screen/paper at real 1280x720/d320/system font 2.
 * Only the left merged English/IPA/word-action is measured. No fake density,
 * scroll wrapper, repository, account, HTTP, TTS, AI or original artwork changes.
 * The fixed IPA/text fixtures are not a pronunciation-quality certification.
 */
class VocabularyExpandedLongWordTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var state: VocabularyBookUiState
    private lateinit var record: VocabularySenseRecord
    private val actions = mutableListOf<VocabularyBookAction>()
    private var touchSlop = 0f
    private var ipaSize = TextUnit.Unspecified
    private var actionSize = TextUnit.Unspecified
    private var physicalDrags = 0
    private var enforceFixedPaperInsets = false
    private var fixedPaperBounds: Rect? = null

    @Test
    fun expandedLongPhraseAndStoredPhoneticRemainLineReadableAndExactlyPlayableAtSystemDoubleFont() {
        exercise("long", "take it with a grain of salt", "/teɪk ɪt wɪð ə ɡreɪn əv sɔlt/", "对信息持保留态度")
    }

    @Test
    fun expandedShortWordKeepsItsCompleteStoredPhoneticAndExactPhysicalSpeakerAtSystemDoubleFont() {
        exercise("short", "run", "/rʌn/", "经营")
    }

    @Test
    fun expandedLeftPaperKeepsOriginalFixedInsetsWhileLongWordPhoneticAndSpeakerAreTouchRead() {
        exercise("paper-inset", "take it with a grain of salt", "/teɪk ɪt wɪð ə ɡreɪn əv sɔlt/", "对信息持保留态度")
    }

    private fun exercise(label: String, english: String, ipa: String, meaning: String) {
        record = VocabularySenseRecord("word-$label", english, meaning, KnowledgeStatus.LEARNING, 0,
            setOf("CET_4"), 1, phonetic = ipa, pronunciationText = english,
            representativeScene = "隔离显示夹具", focusOccurrenceCount = 2, contextOccurrenceCount = 1)
        state = VocabularyBookUiState("CET_4", listOf(VocabularyEntry(english, listOf(record))),
            expandedSenseId = record.senseId, loading = false)
        val before = state.copy()
        render()
        // Capture the actual isolated initial frame before any layout verdict.
        capture(label, "initial")
        if (enforceFixedPaperInsets) {
            fixedPaperBounds = outerLeftPaperBounds()
            assertFixedPaperViewport()
        }
        compose.onAllNodesWithText(english, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText(ipa, useUnmergedTree = true).assertCountEquals(1)
        val englishLayout = paragraph(text(english), english, expectedSize = null)
        assertEquals("A vocabulary headword or phrase must remain one recognisable line", 1, englishLayout.lineCount)
        readEveryLine(text(english), englishLayout)
        capture(label, "word-read")
        val ipaLayout = paragraph(text(ipa), ipa, 15.sp)
        readEveryLine(text(ipa), ipaLayout)
        capture(label, "ipa-read")

        val action = speaker().assertIsEnabled().fetchSemanticsNode()
        assertTrue("The existing word speaker must retain its 48dp minimum touch height", action.layoutInfo.height >= 96)
        assertTrue(action.config.contains(SemanticsActions.OnClick))
        assertTrue("Reading and scrolling cannot itself play or change a sense", actions.isEmpty())
        capture(label, "action-ready")
        speaker().performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(listOf(VocabularyBookAction.SensePronunciationRequested(record.senseId)), actions)
        assertEquals("The render/action test must not mutate stored fixture fields or selection", before, state)
    }

    private fun render() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.window.decorView.width == WIDTH && compose.activity.window.decorView.height == HEIGHT }
        compose.setContent { RlTheme { RlPaperBackground {
            touchSlop = LocalViewConfiguration.current.touchSlop
            ipaSize = LocalTextStyle.current.fontSize
            actionSize = MaterialTheme.typography.labelLarge.fontSize
            VocabularyBookScreen(state, actions::add)
        } } }
        compose.waitForIdle()
        assertActualWindow()
    }

    private fun assertActualWindow() {
        val resources = compose.activity.resources
        assertEquals(320, resources.configuration.densityDpi)
        assertEquals(2f, resources.displayMetrics.density, .001f)
        assertEquals("Use the real system font, not a LocalDensity substitution", 2f, resources.configuration.fontScale, .001f)
        assertEquals(WIDTH, compose.activity.window.decorView.width)
        assertEquals(HEIGHT, compose.activity.window.decorView.height)
        val root = compose.onRoot().fetchSemanticsNode().layoutInfo
        assertEquals(WIDTH, root.width)
        assertEquals(HEIGHT, root.height)
    }

    private fun text(value: String) = compose.onNodeWithText(value, useUnmergedTree = true)
    private fun speaker() = compose.onNodeWithTag("vocabulary-group-pronunciation-${record.senseId}")

    private fun paragraph(node: SemanticsNodeInteraction, value: String, expectedSize: TextUnit?): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals("Resolve one actual paragraph before a product verdict", 1, layouts.size)
        return layouts.single().also { layout ->
            assertEquals(value, layout.layoutInput.text.text)
            if (expectedSize != null) {
                assertEquals("Do not shrink the existing left-cell typography", expectedSize, layout.layoutInput.style.fontSize)
            }
            assertEquals(2f, layout.layoutInput.density.density, .001f)
            assertEquals(2f, layout.layoutInput.density.fontScale, .001f)
            assertTrue(layout.lineCount > 0)
            assertFalse("No line may be dropped to fit the card", layout.multiParagraph.didExceedMaxLines)
            assertEquals(value.length, layout.getLineEnd(layout.lineCount - 1))
            repeat(layout.lineCount) { assertFalse(layout.isLineEllipsized(it)) }
        }
    }

    private fun belongsTo(child: LayoutCoordinates, ancestor: LayoutCoordinates): Boolean =
        generateSequence(child) { it.parentLayoutCoordinates }.any { it === ancestor }

    private fun leftGroupClip(): Rect {
        val word = text(record.englishForm).fetchSemanticsNode().layoutInfo.coordinates
        val ipa = text(requireNotNull(record.phonetic)).fetchSemanticsNode().layoutInfo.coordinates
        val button = speaker().fetchSemanticsNode().layoutInfo.coordinates
        val common = generateSequence(word) { it.parentLayoutCoordinates }
            .first { belongsTo(ipa, it) && belongsTo(button, it) }
        // The smallest real shared ancestor belongs only to the left cell. A
        // child overflowing that cell cannot pass merely by fitting the screen.
        val detail = compose.onNodeWithTag("vocabulary-detail-sheet").fetchSemanticsNode().layoutInfo.coordinates
        assertFalse("The pronunciation group cannot borrow the right detail's area", belongsTo(detail, common))
        val origin = common.localToRoot(Offset.Zero)
        val raw = Rect(origin.x, origin.y, origin.x + common.size.width, origin.y + common.size.height)
        return common.findRootCoordinates().localBoundingBoxOf(common, clipBounds = true).intersect(raw).intersect(windowBounds)
    }

    private fun outerLeftPaperBounds(): Rect {
        val word = text(record.englishForm).fetchSemanticsNode().layoutInfo.coordinates
        val ipa = text(requireNotNull(record.phonetic)).fetchSemanticsNode().layoutInfo.coordinates
        val button = speaker().fetchSemanticsNode().layoutInfo.coordinates
        val detail = compose.onNodeWithTag("vocabulary-detail-sheet").fetchSemanticsNode().layoutInfo.coordinates
        val common = generateSequence(word) { it.parentLayoutCoordinates }
            .first { belongsTo(ipa, it) && belongsTo(button, it) }
        // Ascend through the left-only slot, stopping before the row that owns
        // the right paper too. Unlike the natural scroll content, this outer
        // paper frame must stay fixed while English/IPA/actions move within it.
        val outer = generateSequence(common) { it.parentLayoutCoordinates }
            .takeWhile { !belongsTo(detail, it) }.last()
        assertFalse(belongsTo(detail, outer))
        val origin = outer.localToRoot(Offset.Zero)
        return Rect(origin.x, origin.y, origin.x + outer.size.width, origin.y + outer.size.height)
    }

    private fun fixedPaperReadingArea(): Rect {
        val paper = requireNotNull(fixedPaperBounds)
        val density = compose.activity.resources.displayMetrics.density
        // Frozen pre-fix layout invariant, not values imported from the current
        // component: 12dp content + 8dp paper vertically; 12dp + 12dp horizontally.
        val horizontal = 24f * density
        val vertical = 20f * density
        return Rect(paper.left + horizontal, paper.top + vertical, paper.right - horizontal, paper.bottom - vertical)
    }

    private fun assertFixedPaperViewport() {
        val paper = outerLeftPaperBounds()
        val original = requireNotNull(fixedPaperBounds)
        assertEquals("The left paper background cannot scroll with its contents", original, paper)
        val expected = fixedPaperReadingArea()
        val node = text(record.englishForm).fetchSemanticsNode()
        val scroll = generateSequence(node.parent) { it.parent }.firstOrNull {
            it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        } ?: error("Expanded long-word content requires its own actual scroll viewport")
        val coordinates = scroll.layoutInfo.coordinates
        val detail = compose.onNodeWithTag("vocabulary-detail-sheet").fetchSemanticsNode().layoutInfo.coordinates
        assertFalse("The left scroll cannot borrow the right paper", belongsTo(detail, coordinates))
        val viewport = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(scroll.boundsInRoot).intersect(paper).intersect(windowBounds)
        val diagnostic = "paper=$paper expected=$expected actualScroll=$viewport; padding must be outside scrolling content"
        assertEquals("Fixed left 24dp inset: $diagnostic", expected.left, viewport.left, 1f)
        assertEquals("Fixed right 24dp inset: $diagnostic", expected.right, viewport.right, 1f)
        assertEquals("Fixed top 20dp inset: $diagnostic", expected.top, viewport.top, 1f)
        assertEquals("Fixed bottom 20dp inset: $diagnostic", expected.bottom, viewport.bottom, 1f)
    }

    private data class LineGeometry(val boxes: List<Rect>, val clip: Rect, val diagnostic: String) {
        val visible get() = boxes.isNotEmpty() && clip.width > 0f && clip.height > 0f && boxes.all {
            it.left >= clip.left - 1f && it.top >= clip.top - 1f && it.right <= clip.right + 1f && it.bottom <= clip.bottom + 1f
        }
    }

    private fun geometry(node: SemanticsNodeInteraction, layout: TextLayoutResult, line: Int): LineGeometry {
        val semantic = node.fetchSemanticsNode()
        val coordinates = semantic.layoutInfo.coordinates
        val origin = realParagraphOrigin(layout, coordinates.localToRoot(Offset.Zero), coordinates.size.width)
        var clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(semantic.boundsInRoot).intersect(leftGroupClip()).intersect(windowBounds)
        if (enforceFixedPaperInsets) {
            assertFixedPaperViewport()
            clip = clip.intersect(fixedPaperReadingArea())
        }
        val source = layout.layoutInput.text.text
        val boxes = (layout.getLineStart(line) until layout.getLineEnd(line, visibleEnd = true))
            .filter { !source[it].isWhitespace() }.map { layout.getBoundingBox(it).translate(origin) }
        return LineGeometry(boxes, clip, "text=$source line=$line/${layout.lineCount} paragraph=${layout.size} origin=$origin glyphs=$boxes clip=$clip drags=$physicalDrags")
    }

    private fun realParagraphOrigin(layout: TextLayoutResult, origin: Offset, width: Int): Offset {
        val semanticWidth = layout.multiParagraph.width
        if (abs(semanticWidth - width) <= 1f) return origin
        val left = layout.getLineLeft(0)
        val right = layout.getLineRight(0)
        val diagnostic = "semanticWidth=$semanticWidth width=$width size=${layout.size} lines=${layout.lineCount} left=$left right=$right"
        assertTrue("Resolve actual paragraph placement, not didOverflowWidth alone: $diagnostic",
            layout.lineCount == 1 && layout.size.width == width && semanticWidth > width &&
                ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt() == width)
        val align = layout.layoutInput.style.textAlign
        val isLeft = align == TextAlign.Left || ((align == TextAlign.Start || align == TextAlign.Unspecified) &&
            layout.layoutInput.layoutDirection == LayoutDirection.Ltr)
        if (isLeft && abs(left) <= 1f && right in 0f..(width + 1f)) return origin
        val offset = (semanticWidth - width) / 2f
        assertTrue("Only the verified single-line Center cache offset may be removed: $diagnostic",
            align == TextAlign.Center && abs(left - offset) <= 1f && abs(right - (offset + width)) <= 1f)
        return origin - Offset(offset, 0f)
    }

    private fun readEveryLine(node: SemanticsNodeInteraction, layout: TextLayoutResult) {
        repeat(layout.lineCount) { line ->
            var current = geometry(node, layout, line)
            var attempts = 0
            while (!current.visible && attempts < 32) {
                val semantic = node.fetchSemanticsNode()
                val ancestor = generateSequence(semantic.parent) { it.parent }.firstOrNull {
                    it.config.contains(SemanticsProperties.VerticalScrollAxisRange) &&
                        it.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f
                }
                assertTrue("Clipped left-cell content needs a real local scroll route: ${current.diagnostic}", ancestor != null)
                val actual = requireNotNull(ancestor)
                val scroll = compose.onNode(SemanticsMatcher("Actual left-cell scroll ${actual.id}") { it.id == actual.id }, useUnmergedTree = true)
                val coordinates = actual.layoutInfo.coordinates
                val viewport = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
                    .intersect(actual.boundsInRoot).intersect(leftGroupClip()).intersect(windowBounds)
                val top = current.boxes.minOf { it.top }
                val bottom = current.boxes.maxOf { it.bottom }
                assertTrue("A complete line needs enough local viewport height: ${current.diagnostic} viewport=$viewport",
                    viewport.height >= bottom - top - 1f && viewport.height > touchSlop + 12f)
                val upward = bottom > viewport.bottom || top >= viewport.top
                val range = actual.config[SemanticsProperties.VerticalScrollAxisRange]
                val before = range.value()
                if ((upward && before >= range.maxValue() - 1f) || (!upward && before <= 1f)) break
                val slack = (viewport.height - (bottom - top)).coerceAtLeast(0f)
                val delta = if (upward) bottom - viewport.bottom else viewport.top - top
                val distance = (delta.coerceAtLeast(0f) + slack / 2f + touchSlop).coerceIn(touchSlop + 2f, viewport.height - 8f)
                val from = Offset(viewport.center.x, if (upward) viewport.bottom - 4f else viewport.top + 4f)
                val to = from + Offset(0f, if (upward) -distance else distance)
                val rootOrigin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
                compose.onRoot().performTouchInput {
                    down(from - rootOrigin)
                    repeat(20) { step -> moveTo(from + (to - from) * ((step + 1) / 20f) - rootOrigin, 60L) }
                    repeat(6) { advanceEventTime(40L); moveTo(to - rootOrigin, 0L) }
                    up()
                }
                compose.waitForIdle()
                physicalDrags++; attempts++
                val after = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
                assertTrue("Physical scrolling must move toward the line: before=$before after=$after ${current.diagnostic}",
                    if (upward) after > before else after < before)
                current = geometry(node, layout, line)
            }
            assertTrue("Every original glyph must be readable, not just semantically present: ${current.diagnostic}", current.visible)
        }
    }

    private fun capture(label: String, step: String) {
        val phase = InstrumentationRegistry.getArguments().getString("vocabularyLongWordCapture") ?: return
        require(phase in setOf("before", "after") && label in setOf("long", "short", "paper-inset") &&
            step in setOf("initial", "word-read", "ipa-read", "action-ready"))
        compose.waitForIdle()
        assertActualWindow()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val decor = compose.activity.window.decorView
        val drawn = CountDownLatch(1)
        val listener = ViewTreeObserver.OnDrawListener { drawn.countDown() }
        instrumentation.runOnMainSync {
            assertTrue("Capture only the foreground isolated test Screen", decor.hasWindowFocus())
            decor.viewTreeObserver.addOnDrawListener(listener)
            decor.postInvalidateOnAnimation()
        }
        try { check(drawn.await(10, TimeUnit.SECONDS)) { "The isolated vocabulary window did not draw" } }
        finally { instrumentation.runOnMainSync { if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(listener) } }
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        compose.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(WIDTH, bitmap.width); assertEquals(HEIGHT, bitmap.height)
            var inkSamples = 0
            for (y in 0 until bitmap.height step 16) for (x in 0 until bitmap.width step 16) {
                val color = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(color) < 170 && android.graphics.Color.green(color) < 170 &&
                    android.graphics.Color.blue(color) < 170) inkSamples++
            }
            assertTrue("A blank frame is not visual evidence", inkSamples > 8)
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa")
            check(directory.isDirectory || directory.mkdirs())
            val output = File(directory, "41-vocabulary-expanded-word-$label-$step-$phase.png")
            check(output.canonicalFile.parentFile == directory.canonicalFile)
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        val windowBounds = Rect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())
    }
}
