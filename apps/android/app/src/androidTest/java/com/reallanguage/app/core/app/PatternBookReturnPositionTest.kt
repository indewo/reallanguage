package com.reallanguage.app.core.app

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.PatternBookRepository
import com.reallanguage.app.feature.knowledge.domain.PatternExample
import com.reallanguage.app.feature.knowledge.domain.PatternLoadResult
import com.reallanguage.app.feature.knowledge.domain.PatternRecord
import com.reallanguage.app.feature.knowledge.domain.PatternRelatedVocabulary
import com.reallanguage.app.feature.knowledge.presentation.PatternBookViewModel
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * FR-NAV-004: real Entry/VM/Screen return to the list position from before p8
 * was opened, not scroll offset zero or the deep expanded-detail offset.
 * Real 1774x887/d320/system-font1; only the 24-record repository is isolated.
 * No production database, account, HTTP, TTS, AI, extra scroll host or fake density.
 * The original page42/43 paper/illustration differences are not pixel acceptance.
 */
class PatternBookReturnPositionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var touchSlop = 0f
    private var drags = 0

    @Test
    fun realEntryRestoresThePreExpansionListAnchorAfterReadingLongEighthDetailAndCollapsing() {
        val records = records()
        val original = records.map { record -> record.copy(
            examples = record.examples.map { it.copy() },
            structureParts = record.structureParts.toList(),
            relatedVocabulary = record.relatedVocabulary?.map { it.copy() },
        ) }
        val loads = AtomicInteger()
        val creations = AtomicInteger()
        val playbackCalls = AtomicInteger()
        val removalCalls = AtomicInteger()
        val homeCalls = AtomicInteger()
        var model: PatternBookViewModel? = null
        val repository = object : PatternBookRepository {
            override suspend fun loadAllPatterns(): PatternLoadResult {
                loads.incrementAndGet()
                return PatternLoadResult.Ready(records)
            }
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                check(modelClass == PatternBookViewModel::class.java)
                creations.incrementAndGet()
                return PatternBookViewModel(repository).also { model = it } as T
            }
        }
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.window.decorView.width == WIDTH && compose.activity.window.decorView.height == HEIGHT }
        compose.setContent { RlTheme {
            touchSlop = LocalViewConfiguration.current.touchSlop
            PatternBookEntry(factory,
                removalFactory = { _, _ -> removalCalls.incrementAndGet(); error("No removal in this return-position fixture") },
                onHome = { homeCalls.incrementAndGet() },
                onPronunciation = { playbackCalls.incrementAndGet(); error("No audio in this return-position fixture") },
            )
        } }
        compose.waitUntil(5_000) { model?.uiState?.value?.patterns?.size == 24 && model?.uiState?.value?.loading == false }
        compose.waitForIdle()
        assertWindow()
        capture("list-initial")
        val target = records.single { it.patternId == "p8" }
        val targetText = compose.onNode(hasText(target.structureEn) and
            hasAnyAncestor(hasTestTag("pattern-row-p8")), useUnmergedTree = true)
        reachByPhysicalTouch(targetText, target.structureEn)
        val before = position()
        val visibleBefore = visibleRows(records)
        val originsBefore = visibleBefore.associateWith(::rowOrigin)
        val targetBefore = rowOrigin("p8")
        assertTrue("Open a genuine intermediate list position, not zero or the final row", before.first > 0f && before.first < before.second - 1f)
        assertTrue(visibleBefore.contains("p8"))
        capture("list-before-open")
        clickVisibleText(targetText, target.structureEn)
        compose.waitUntil(5_000) { model?.uiState?.value?.expandedPatternId == "p8" }
        compose.waitForIdle()
        capture("detail-open")
        val first = compose.onNodeWithText(target.examples.first().textEn, useUnmergedTree = true)
        val firstLayout = paragraph(first, target.examples.first().textEn)
        assertTrue("Long detail height is real wrapped content rather than test padding", firstLayout.lineCount > 8)
        val detail = raw(compose.onNodeWithTag("pattern-detail-sheet").fetchSemanticsNode())
        assertTrue("Reading this detail requires more than one real viewport", detail.height > clip(viewport().fetchSemanticsNode()).height)
        val collapse = compose.onNodeWithText("收回", useUnmergedTree = true)
        reachByPhysicalTouch(collapse, "收回")
        val deepPosition = position()
        assertTrue("The user actually read down the expanded content before collapsing",
            deepPosition.first > before.first + clip(viewport().fetchSemanticsNode()).height)
        compose.runOnIdle {
            assertEquals(1, loads.get()); assertEquals(1, creations.get())
            assertEquals(original, model?.uiState?.value?.patterns)
            assertEquals(0, playbackCalls.get()); assertEquals(0, removalCalls.get()); assertEquals(0, homeCalls.get())
        }
        capture("detail-before-collapse")
        val dragsBeforeCollapse = drags
        clickVisibleText(collapse, "收回")
        compose.waitUntil(5_000) { model?.uiState?.value?.expandedPatternId == null }
        compose.waitForIdle()
        capture("list-after-collapse")
        // No further touch, ScrollTo, VM replacement or manual state restoration.
        // Observe the actual returned list and compare against its pre-open state.
        val after = position()
        val visibleAfter = visibleRows(records)
        val targetAfter = rowOrigin("p8")
        val diagnostic = "before=$before expanded=$deepPosition after=$after visibleBefore=$visibleBefore visibleAfter=$visibleAfter " +
            "p8Before=$targetBefore p8After=$targetAfter"
        assertEquals(dragsBeforeCollapse, drags)
        compose.onNodeWithTag("pattern-detail-sheet").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, loads.get()); assertEquals(1, creations.get())
            assertEquals(0, playbackCalls.get()); assertEquals(0, removalCalls.get()); assertEquals(0, homeCalls.get())
            assertEquals("All record fields, ordering, examples, references and counts remain unchanged", original, model?.uiState?.value?.patterns)
            assertEquals("", model?.uiState?.value?.query)
            assertFalse(requireNotNull(model).uiState.value.pronunciationBusy)
            assertFalse(requireNotNull(model).uiState.value.pronunciationFailed)
        }
        assertEquals("Restore the real pre-expansion offset, not a guessed zero; $diagnostic", before.first, after.first, 1f)
        assertEquals("Unchanged rows retain their original scroll extent", before.second, after.second, 1f)
        assertEquals("The same list rows must still be in view; $diagnostic", visibleBefore, visibleAfter)
        visibleBefore.forEach { id ->
            val restored = rowOrigin(id)
            assertEquals("$id keeps its original horizontal position", originsBefore.getValue(id).x, restored.x, 1f)
            assertEquals("$id keeps its original vertical position; $diagnostic", originsBefore.getValue(id).y, restored.y, 1f)
        }
        val restoredLayout = paragraph(targetText, target.structureEn)
        repeat(restoredLayout.lineCount) { line ->
            assertTrue("Returned p8 text must really be readable, not only present in semantics", geometry(targetText, restoredLayout, line).visible)
        }
    }

    private fun viewport() = compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("pattern-book-list")))
    private fun position(): Pair<Float, Float> {
        val range = viewport().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        return range.value() to range.maxValue()
    }
    private fun rowOrigin(id: String) = compose.onNodeWithTag("pattern-row-$id").fetchSemanticsNode().layoutInfo.coordinates.localToRoot(Offset.Zero)
    private fun visibleRows(records: List<PatternRecord>) = records.mapNotNull { record ->
        val bounds = clip(compose.onNodeWithTag("pattern-row-${record.patternId}").fetchSemanticsNode())
        record.patternId.takeIf { bounds.width > 1f && bounds.height > 1f }
    }
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
        assertEquals(1, layouts.size)
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
        val width = coordinates.size.width
        if (abs(layout.multiParagraph.width - width) > 1f) {
            assertTrue("Resolve only the established single-line ParagraphLayoutCache width mismatch",
                layout.lineCount == 1 && layout.size.width == width && layout.multiParagraph.width > width &&
                    ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt() == width)
            val left = layout.getLineLeft(0)
            val right = layout.getLineRight(0)
            val align = layout.layoutInput.style.textAlign
            val isLeft = align == TextAlign.Left || ((align == TextAlign.Start || align == TextAlign.Unspecified) &&
                layout.layoutInput.layoutDirection == LayoutDirection.Ltr)
            if (!(isLeft && abs(left) <= 1f && right in 0f..(width + 1f))) {
                val offset = (layout.multiParagraph.width - width) / 2f
                assertTrue("Never compensate vertical clipping", align == TextAlign.Center && abs(left - offset) <= 1f && abs(right - offset - width) <= 1f)
                origin -= Offset(offset, 0f)
            }
        }
        val value = layout.layoutInput.text.text
        return Glyphs(clip(semantic), (layout.getLineStart(line) until layout.getLineEnd(line, visibleEnd = true))
            .filter { !value[it].isWhitespace() }.map { layout.getBoundingBox(it).translate(origin) })
    }
    private fun reachByPhysicalTouch(node: SemanticsNodeInteraction, value: String) {
        val layout = paragraph(node, value)
        repeat(layout.lineCount) { line ->
            var glyphs = geometry(node, layout, line)
            var attempts = 0
            while (!glyphs.visible && attempts < 30) {
                val area = clip(viewport().fetchSemanticsNode())
                assertTrue(area.height > touchSlop + 24f && area.width > 24f)
                val centerY = glyphs.boxes.map { it.center.y }.average().toFloat()
                val later = centerY > area.center.y
                val before = position()
                if ((later && before.first >= before.second - 1f) || (!later && before.first <= 1f)) break
                val distance = (abs(centerY - area.center.y) + touchSlop).coerceIn(touchSlop + 4f, area.height - 16f)
                val x = glyphs.boxes.first().center.x.coerceIn(area.left + 8f, area.right - 8f)
                val from = Offset(x, if (later) area.bottom - 8f else area.top + 8f)
                val to = from + Offset(0f, if (later) -distance else distance)
                val origin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
                compose.onRoot().performTouchInput {
                    down(from - origin)
                    repeat(20) { step -> moveTo(from - origin + (to - from) * ((step + 1) / 20f), 60L) }
                    repeat(6) { advanceEventTime(40L); moveTo(to - origin, 0L) }
                    up()
                }
                compose.waitForIdle()
                assertTrue("A drag must move the actual list", abs(position().first - before.first) > .5f)
                attempts++; drags++
                glyphs = geometry(node, layout, line)
            }
            assertTrue("The requested glyphs must be reached through real touch: text=$value line=$line attempts=$attempts glyphs=$glyphs", glyphs.visible)
        }
    }
    private fun clickVisibleText(node: SemanticsNodeInteraction, value: String) {
        val layout = paragraph(node, value)
        val glyphs = geometry(node, layout, layout.lineCount - 1)
        assertTrue(glyphs.visible)
        val button = generateSequence(node.fetchSemanticsNode()) { it.parent }
            .first { it.config.contains(SemanticsActions.OnClick) }
        val point = glyphs.boxes.last().center
        assertTrue(clip(button).contains(point))
        val origin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
        compose.onRoot().performTouchInput { click(point - origin) }
        compose.waitForIdle()
    }
    private fun assertWindow() {
        assertEquals(320, compose.activity.resources.configuration.densityDpi)
        assertEquals(2f, compose.activity.resources.displayMetrics.density, .001f)
        assertEquals(1f, compose.activity.resources.configuration.fontScale, .001f)
        assertEquals(WIDTH, compose.onRoot().fetchSemanticsNode().layoutInfo.width)
        assertEquals(HEIGHT, compose.onRoot().fetchSemanticsNode().layoutInfo.height)
    }
    private fun capture(label: String) {
        val phase = InstrumentationRegistry.getArguments().getString("patternReturnSnapshot") ?: "before"
        require(phase == "before" || phase == "after")
        require(label in setOf("list-initial", "list-before-open", "detail-open", "detail-before-collapse", "list-after-collapse"))
        compose.waitForIdle(); assertWindow()
        val before = position()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val decor = compose.activity.window.decorView
        val latch = CountDownLatch(1)
        val listener = ViewTreeObserver.OnDrawListener { latch.countDown() }
        instrumentation.runOnMainSync {
            check(decor.hasWindowFocus())
            decor.viewTreeObserver.addOnDrawListener(listener)
            decor.postInvalidateOnAnimation()
        }
        try { check(latch.await(10, TimeUnit.SECONDS)) { "The actual pattern-book window did not draw" } }
        finally { instrumentation.runOnMainSync { if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(listener) } }
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        compose.waitForIdle()
        assertEquals("Capture must not move the inspected list", before, position())
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(WIDTH, bitmap.width); assertEquals(HEIGHT, bitmap.height)
            var ink = 0
            for (y in 0 until HEIGHT step 16) for (x in 0 until WIDTH step 16) {
                val color = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(color) < 170 && android.graphics.Color.green(color) < 170 && android.graphics.Color.blue(color) < 170) ink++
            }
            assertTrue("A blank capture is not evidence", ink > 8)
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa")
            check(directory.isDirectory || directory.mkdirs())
            val output = File(directory, "43-pattern-return-$label-$phase.png")
            check(output.canonicalFile.parentFile == directory.canonicalFile)
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
    private fun records() = (1..24).map { index ->
        val examples = (1..3).map { example ->
            val en = if (index == 8) "Example $example. " +
                "The team checks the daily schedule and keeps the service running smoothly. ".repeat(10).trim()
                else "Stored pattern $index example $example."
            val zh = if (index == 8) "第${example}条隔离已存例句。" + "团队检查每天的计划，并让服务继续正常开展。".repeat(10)
                else "句式${index}的第${example}条已存例句。"
            PatternExample(en, zh, en)
        }
        PatternRecord("p$index", "work up the courage to + verb ($index)", "鼓起勇气做第${index}件事",
            KnowledgeStatus.MASTERED, KnowledgeStatus.LEARNING, 2, index,
            representativeScene = "句式 $index 的已存代表场景", examples = examples,
            writingConsecutiveSuccessCount = 2, speakingConsecutiveSuccessCount = 1,
            naturalRevalidation = index % 2 == 0,
            structureParts = listOf("work", "up", "the courage", "to", "+ verb"),
            relatedVocabulary = listOf(PatternRelatedVocabulary("courage", "勇气")),
        )
    }
    private companion object { const val WIDTH = 1774; const val HEIGHT = 887 }
}
