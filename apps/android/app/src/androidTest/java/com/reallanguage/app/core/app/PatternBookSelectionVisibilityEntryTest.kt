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
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
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
 * KB-04 / FR-NAV-004: actual Entry/VM switches p1 to p2; the user must see
 * the newly selected row and the first line of its own example without another
 * drag. Separate from restoring the old list position after CollapseRequested.
 * Real 1774x887/d320/font1 window; only a neutral 24-record repository is fake.
 * No SQL, HTTP, TTS, AI, private data, extra scroll wrapper or fake density.
 */
class PatternBookSelectionVisibilityEntryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var touchSlop = 0f
    private var drags = 0

    @Test
    fun realEntryRevealsSecondPatternAfterLeavingLongFirstDetailWithoutAnotherDrag() {
        val records = records()
        val original = records.map { it.copy(examples = it.examples.map { example -> example.copy() },
            structureParts = it.structureParts.toList(), relatedVocabulary = it.relatedVocabulary?.map { item -> item.copy() }) }
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
                removalFactory = { _, _ -> removalCalls.incrementAndGet(); error("No removal in this selection fixture") },
                onHome = { homeCalls.incrementAndGet() },
                onPronunciation = { playbackCalls.incrementAndGet(); error("No playback in this selection fixture") },
            )
        } }
        compose.waitUntil(5_000) { model?.uiState?.value?.patterns?.size == 24 && model?.uiState?.value?.loading == false }
        compose.waitForIdle()
        assertWindow()
        capture("list-initial")
        val first = records.first()
        val second = records[1]
        clickVisibleText(rowTitle(first), first.structureEn)
        compose.waitUntil(5_000) { model?.uiState?.value?.expandedPatternId == first.patternId }
        compose.waitForIdle()
        capture("first-expanded")
        val firstExample = compose.onNodeWithText(first.examples.first().textEn, useUnmergedTree = true)
        val firstLayout = paragraph(firstExample, first.examples.first().textEn)
        assertTrue("The old detail is naturally multiline, not made tall with test padding", firstLayout.lineCount > 8)
        val viewportBefore = clip(viewport().fetchSemanticsNode())
        val secondBefore = raw(compose.onNodeWithTag("pattern-row-p2").fetchSemanticsNode())
        assertTrue("The next real row is initially more than one viewport below the fold: row=$secondBefore viewport=$viewportBefore",
            secondBefore.top > viewportBefore.bottom + viewportBefore.height)
        reachByTouch(rowTitle(second), second.structureEn)
        val oldPosition = position()
        assertTrue("Reach p2 by actually scrolling through the first detail", drags > 0 && oldPosition.first > viewportBefore.height)
        compose.runOnIdle {
            assertEquals("p1", model?.uiState?.value?.expandedPatternId)
            assertEquals(original, model?.uiState?.value?.patterns)
        }
        capture("second-ready-to-click")
        val dragsBeforeSelection = drags
        clickVisibleText(rowTitle(second), second.structureEn)
        compose.waitUntil(5_000) { model?.uiState?.value?.expandedPatternId == second.patternId }
        compose.waitForIdle()
        capture("second-selected")
        // No test ScrollTo, VM direct action or compensating drag after the click.
        // Production can choose any positioning mechanism that reveals its result.
        assertEquals(dragsBeforeSelection, drags)
        compose.runOnIdle {
            assertEquals("p2", model?.uiState?.value?.expandedPattern?.patternId)
            assertEquals(original, model?.uiState?.value?.patterns)
            assertEquals("", model?.uiState?.value?.query)
            assertEquals(1, loads.get()); assertEquals(1, creations.get())
            assertEquals(0, playbackCalls.get()); assertEquals(0, removalCalls.get()); assertEquals(0, homeCalls.get())
            assertFalse(requireNotNull(model).uiState.value.pronunciationBusy)
            assertFalse(requireNotNull(model).uiState.value.pronunciationFailed)
        }
        compose.onNodeWithText(first.examples.first().textEn, useUnmergedTree = true).assertDoesNotExist()
        val title = rowTitle(second)
        val titleLayout = paragraph(title, second.structureEn)
        val example = compose.onNodeWithText(second.examples.first().textEn, useUnmergedTree = true)
        val exampleLayout = paragraph(example, second.examples.first().textEn)
        val titleGlyphs = (0 until titleLayout.lineCount).map { geometry(title, titleLayout, it) }
        val exampleFirstLine = geometry(example, exampleLayout, 0)
        val diagnostic = "oldScroll=$oldPosition newScroll=${position()} title=$titleGlyphs exampleFirstLine=$exampleFirstLine"
        assertTrue("The newly selected p2 row title must be physically readable without another drag: $diagnostic",
            titleGlyphs.all { it.visible })
        assertTrue("The new detail's own first example line must enter the actual viewport: $diagnostic", exampleFirstLine.visible)
        assertTrue("The revealed example belongs immediately after its own selected row",
            raw(compose.onNodeWithTag("pattern-row-p2").fetchSemanticsNode()).bottom <= exampleFirstLine.boxes.minOf { it.top } + 1f)
    }

    @Test
    fun clearingSearchKeepsTheMatchingExpandedPatternAndItsFirstExampleVisibleWithoutAnotherDrag() {
        val records = records()
        val original = records.map { it.copy(examples = it.examples.map { example -> example.copy() },
            structureParts = it.structureParts.toList(), relatedVocabulary = it.relatedVocabulary?.map { item -> item.copy() }) }
        val loads = AtomicInteger()
        val creations = AtomicInteger()
        val unrelatedActions = AtomicInteger()
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
            PatternBookEntry(factory,
                removalFactory = { _, _ -> unrelatedActions.incrementAndGet(); error("No removal in this query fixture") },
                onHome = { unrelatedActions.incrementAndGet() },
                onPronunciation = { unrelatedActions.incrementAndGet(); error("No playback in this query fixture") },
            )
        } }
        compose.waitUntil(5_000) { model?.uiState?.value?.patterns?.size == 24 && model?.uiState?.value?.loading == false }
        compose.waitForIdle()
        assertWindow()
        val selected = records[19]
        val search = compose.onNodeWithTag("pattern-book-search")
        // Drive the real BasicTextField -> QueryChanged -> Entry/VM path.
        // This is not a replacement UiState or a direct ViewModel action.
        search.performTextReplacement("(20)")
        search.performImeAction()
        compose.waitUntil(5_000) { model?.uiState?.value?.visiblePatterns?.map { it.patternId } == listOf("p20") }
        compose.waitForIdle()
        clickVisibleText(rowTitle(selected), selected.structureEn)
        compose.waitUntil(5_000) { model?.uiState?.value?.expandedPatternId == "p20" }
        compose.waitForIdle()
        capture("query-expanded")
        val selectedTitle = rowTitle(selected)
        val titleLayout = paragraph(selectedTitle, selected.structureEn)
        val example = compose.onNodeWithText(selected.examples.first().textEn, useUnmergedTree = true)
        val exampleLayout = paragraph(example, selected.examples.first().textEn)
        assertTrue("The filtered current selection is readable before clearing the query",
            (0 until titleLayout.lineCount).all { geometry(selectedTitle, titleLayout, it).visible } &&
                geometry(example, exampleLayout, 0).visible)
        val filteredPosition = position()

        search.performTextReplacement("")
        search.performImeAction()
        compose.waitUntil(5_000) { model?.uiState?.value?.query == "" && model?.uiState?.value?.visiblePatterns?.size == 24 }
        compose.waitForIdle()
        // Capture the actual cleared-query viewport before the regression
        // assertion; no test scroll, drag or compensating row selection.
        capture("query-cleared")
        compose.runOnIdle {
            val state = requireNotNull(model).uiState.value
            assertEquals("QueryChanged keeps a still-matching expanded identity", "p20", state.expandedPatternId)
            assertEquals("p20", state.expandedPattern?.patternId)
            assertEquals("", state.query)
            assertEquals(original, state.patterns)
            assertEquals(original, state.visiblePatterns)
            assertFalse(state.loading); assertFalse(state.loadFailed)
            assertFalse(state.pronunciationBusy); assertFalse(state.pronunciationFailed)
            assertEquals(1, loads.get()); assertEquals(1, creations.get())
            assertEquals(0, unrelatedActions.get()); assertEquals(0, drags)
        }
        val revealedTitle = rowTitle(selected)
        val revealedTitleLayout = paragraph(revealedTitle, selected.structureEn)
        val revealedExample = compose.onNodeWithText(selected.examples.first().textEn, useUnmergedTree = true)
        val revealedExampleLayout = paragraph(revealedExample, selected.examples.first().textEn)
        val titleGlyphs = (0 until revealedTitleLayout.lineCount).map { geometry(revealedTitle, revealedTitleLayout, it) }
        val exampleFirstLine = geometry(revealedExample, revealedExampleLayout, 0)
        val diagnostic = "filteredScroll=$filteredPosition clearedScroll=${position()} title=$titleGlyphs exampleFirstLine=$exampleFirstLine"
        assertTrue("Clearing search must not retain p20 as a hidden expanded selection: $diagnostic", titleGlyphs.all { it.visible })
        assertTrue("The retained selection's own first example line must remain accessible without another drag: $diagnostic",
            exampleFirstLine.visible)
    }

    private fun rowTitle(record: PatternRecord) = compose.onNode(hasText(record.structureEn) and
        hasAnyAncestor(hasTestTag("pattern-row-${record.patternId}")), useUnmergedTree = true)
    private fun viewport() = compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("pattern-book-list")))
    private fun position(): Pair<Float, Float> {
        val range = viewport().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        return range.value() to range.maxValue()
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
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        assertEquals("Resolve the real Text before a visibility verdict", 1, results.size)
        return results.single().also {
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
            assertTrue("Only the established single-line paragraph cache adjustment is allowed",
                layout.lineCount == 1 && layout.size.width == width && layout.multiParagraph.width > width &&
                    ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt() == width)
            val left = layout.getLineLeft(0)
            val right = layout.getLineRight(0)
            val align = layout.layoutInput.style.textAlign
            val isLeft = align == TextAlign.Left || ((align == TextAlign.Start || align == TextAlign.Unspecified) &&
                layout.layoutInput.layoutDirection == LayoutDirection.Ltr)
            if (!(isLeft && abs(left) <= 1f && right in 0f..(width + 1f))) {
                val offset = (layout.multiParagraph.width - width) / 2f
                assertTrue("Never compensate a vertical clipping defect",
                    align == TextAlign.Center && abs(left - offset) <= 1f && abs(right - offset - width) <= 1f)
                origin -= Offset(offset, 0f)
            }
        }
        val value = layout.layoutInput.text.text
        return Glyphs(clip(semantic), (layout.getLineStart(line) until layout.getLineEnd(line, visibleEnd = true))
            .filter { !value[it].isWhitespace() }.map { layout.getBoundingBox(it).translate(origin) })
    }
    private fun reachByTouch(node: SemanticsNodeInteraction, value: String) {
        val layout = paragraph(node, value)
        repeat(layout.lineCount) { line ->
            var glyphs = geometry(node, layout, line)
            var attempts = 0
            while (!glyphs.visible && attempts < 30) {
                val area = clip(viewport().fetchSemanticsNode())
                assertTrue(area.height > touchSlop + 24f && area.width > 24f)
                val center = glyphs.boxes.map { it.center.y }.average().toFloat()
                val later = center > area.center.y
                val before = position()
                if ((later && before.first >= before.second - 1f) || (!later && before.first <= 1f)) break
                val distance = (abs(center - area.center.y) + touchSlop).coerceIn(touchSlop + 4f, area.height - 16f)
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
                assertTrue("A drag must actually move the current list", abs(position().first - before.first) > .5f)
                drags++; attempts++
                glyphs = geometry(node, layout, line)
            }
            assertTrue("Reach the actual second row before selecting it: value=$value line=$line attempts=$attempts glyphs=$glyphs", glyphs.visible)
        }
    }
    private fun clickVisibleText(node: SemanticsNodeInteraction, value: String) {
        val layout = paragraph(node, value)
        val glyphs = geometry(node, layout, layout.lineCount - 1)
        assertTrue(glyphs.visible)
        val button = generateSequence(node.fetchSemanticsNode()) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
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
        val phase = InstrumentationRegistry.getArguments().getString("patternSelectionSnapshot") ?: "before"
        require(phase == "before" || phase == "after")
        require(label in setOf("list-initial", "first-expanded", "second-ready-to-click", "second-selected", "query-expanded", "query-cleared"))
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
        try { check(latch.await(10, TimeUnit.SECONDS)) { "The real pattern selection window did not draw" } }
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
            assertTrue("Reject a blank frame", ink > 8)
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa")
            check(directory.isDirectory || directory.mkdirs())
            val output = File(directory, "43-pattern-selection-$label-$phase.png")
            check(output.canonicalFile.parentFile == directory.canonicalFile)
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
    private fun records() = (1..24).map { index ->
        val examples = (1..3).map { number ->
            val english = if (index == 1) "Example $number. " +
                "The team checks the daily schedule and keeps the service running smoothly. ".repeat(10).trim()
                else "Stored pattern $index example $number."
            val chinese = if (index == 1) "第${number}条隔离已存例句。" + "团队检查每天的计划，并让服务继续正常开展。".repeat(10)
                else "句式${index}的第${number}条已存例句。"
            PatternExample(english, chinese, english)
        }
        PatternRecord("p$index", when (index) { 1 -> "work up the courage to + verb"; 2 -> "I'd like to + verb"; else -> "It takes time to + verb ($index)" },
            when (index) { 1 -> "鼓起勇气做某事"; 2 -> "礼貌表达意愿"; else -> "隔离已存功能 $index" },
            KnowledgeStatus.MASTERED, KnowledgeStatus.LEARNING, 2, index,
            representativeScene = "句式 $index 的已存场景", examples = examples,
            writingConsecutiveSuccessCount = 2, speakingConsecutiveSuccessCount = 1,
            naturalRevalidation = true, structureParts = emptyList(),
            relatedVocabulary = listOf(PatternRelatedVocabulary("time", "时间")),
        )
    }
    private companion object { const val WIDTH = 1774; const val HEIGHT = 887 }
}
