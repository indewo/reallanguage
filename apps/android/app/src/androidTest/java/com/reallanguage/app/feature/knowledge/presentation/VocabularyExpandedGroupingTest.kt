package com.reallanguage.app.feature.knowledge.presentation

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
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
 * FR-VOC-001A / AC-E2E-046 / KB-02: one English group, detail immediately
 * after the selected sense. Real 1829x860/d320/system font1 window and existing
 * Screen/paper only. All records and state transitions below are render fixtures;
 * no repository, account, database, HTTP, TTS or AI is created or invoked.
 * Original paper/typography fidelity and the 70px-reference/48dp-touch conflict
 * are deliberately not asserted. No test scroll wrapper or fake density is used.
 */
class VocabularyExpandedGroupingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val initial = fixture()
    private val shown = mutableStateOf(initial)
    private val actions = mutableListOf<VocabularyBookAction>()
    private var touchSlop = 0f
    private var drags = 0

    @Test
    fun expandedGroupHasOneEnglishCellWithoutDuplicatedFeaturedSummaryAndKeepsHeaderReturn() {
        render()
        capture("identity-initial")
        compose.onAllNodesWithText("run", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("/rʌn/", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("本期词汇", substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText(compose.activity.getString(R.string.review_return_home)).assertDoesNotExist()
        initial.entries.single().senses.forEach { sense ->
            compose.onAllNodesWithText(sense.meaningZh, useUnmergedTree = true).assertCountEquals(1)
            row(sense.senseId).assertIsEnabled()
        }
        listOf("已学会 3 次", "已学会 1 次", "学习中", "待学习").forEach {
            compose.onAllNodesWithText(it, useUnmergedTree = true).assertCountEquals(1)
        }
        assertNoInventedSceneImage()
        // The header's real return remains; removing the extra aside CTA must
        // not accidentally remove the only way back to the home page.
        val back = compose.onNodeWithTag("vocabulary-book-back").assertIsEnabled()
        val node = back.fetchSemanticsNode()
        val point = raw(node).center
        assertTrue(clip(node).contains(point))
        physicalClick(point)
        assertEquals(listOf(VocabularyBookAction.ReturnHomeRequested), actions)
    }

    @Test
    fun selectedSensePrecedesItsActualDetailsAndBothFollowingRowsInTheSameScrollFrame() {
        render()
        capture("order-initial")
        val selected = raw(row("run-business").fetchSemanticsNode())
        val next = raw(row("run-program").fetchSemanticsNode())
        val last = raw(row("run-machine").fetchSemanticsNode())
        val business = requireNotNull(initial.expandedSense)
        val detailTexts = listOf("代表场景", "重点例句", "学习统计") +
            business.examples.flatMap { listOf(it.textEn, it.textZh) } +
            listOf("重点句出现 2 次", "非重点句出现 3 次")
        // All positions are collected without any scroll between rows. Merely
        // finding semantic text below the viewport is not a visibility claim.
        val details = detailTexts.map { value ->
            val node = text(value)
            paragraph(node, value)
            value to raw(node.fetchSemanticsNode())
        }
        assertTrue("The first sense remains before the selected second sense",
            raw(row("run-motion").fetchSemanticsNode()).bottom <= selected.top + 1f)
        details.forEach { (value, bounds) ->
            assertTrue("Selected row must precede its own detail: text=$value selected=$selected detail=$bounds next=$next",
                selected.bottom <= bounds.top + 1f)
            assertTrue("Detail belongs before the following sense, not after all senses: text=$value selected=$selected detail=$bounds next=$next",
                bounds.bottom <= next.top + 1f)
        }
        assertTrue("The later two independent meanings retain their order", next.bottom <= last.top + 1f)
        assertEquals("Initial expansion has no playback or selection side effect", emptyList<VocabularyBookAction>(), actions)
        assertNoInventedSceneImage()
    }

    @Test
    fun laterMeaningRowsCanBeTouchReadAndSwitchExactIdentityThenCollapseWithoutChangingRecords() {
        render()
        capture("switch-initial")
        // Read every actual bilingual example and statistic before returning to
        // the later rows. No requirement to squeeze all content into one frame.
        requireNotNull(initial.expandedSense).examples.forEach { example ->
            readAllLines(text(example.textEn), example.textEn)
            readAllLines(text(example.textZh), example.textZh)
        }
        listOf("重点句出现 2 次", "非重点句出现 3 次").forEach { readAllLines(text(it), it) }
        capture("switch-details-read")
        listOf("run-program", "run-machine").forEachIndexed { index, id ->
            val sense = initial.entries.single().senses.single { it.senseId == id }
            val meaning = compose.onNode(hasText(sense.meaningZh) and hasAnyAncestor(rowMatcher(id)), useUnmergedTree = true)
            val layout = readAllLines(meaning, sense.meaningZh)
            val button = row(id).assertIsEnabled().fetchSemanticsNode()
            assertTrue("Existing 48dp minimum may not be reduced to fit the reference", button.layoutInfo.height >= 96)
            val point = geometry(meaning, layout, layout.lineCount - 1).boxes.last().center
            assertTrue("The visible final glyph is inside the actual sense control", clip(button).contains(point))
            capture(if (index == 0) "switch-program-touch" else "switch-machine-touch")
            physicalClick(point)
            assertEquals(listOf("run-program", "run-machine").take(index + 1).map {
                VocabularyBookAction.SenseSelected(it)
            }, actions)
            compose.runOnIdle {
                assertEquals(id, shown.value.expandedSenseId)
                assertEquals(initial.entries, shown.value.entries)
            }
            // Same Screen receives the exact selected record. This is not a
            // repository persistence claim and no synthetic audio is evaluated.
            compose.onNodeWithText(sense.examples.first().textEn, useUnmergedTree = true).assertExists()
            compose.onNodeWithText(requireNotNull(initial.expandedSense).examples.first().textEn, useUnmergedTree = true).assertDoesNotExist()
            compose.onAllNodesWithText("重点例句", useUnmergedTree = true).assertCountEquals(1)
            assertNoInventedSceneImage()
        }
        assertTrue("The full expanded fixture must be read using genuine local scrolling", drags > 0)
        val collapse = text(compose.activity.getString(R.string.vocabulary_detail_collapse))
        val layout = readAllLines(collapse, compose.activity.getString(R.string.vocabulary_detail_collapse))
        val point = geometry(collapse, layout, layout.lineCount - 1).boxes.last().center
        assertTrue(clip(compose.onNodeWithTag("vocabulary-detail-collapse").assertIsEnabled().fetchSemanticsNode()).contains(point))
        capture("switch-collapse-touch")
        physicalClick(point)
        assertEquals(listOf(VocabularyBookAction.SenseSelected("run-program"),
            VocabularyBookAction.SenseSelected("run-machine"), VocabularyBookAction.CollapseRequested), actions)
        compose.runOnIdle {
            assertEquals(null, shown.value.expandedSenseId)
            assertEquals(initial.entries, shown.value.entries)
        }
        compose.onNodeWithText("重点例句").assertDoesNotExist()
        capture("switch-collapsed")
    }

    private fun render() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.window.decorView.width == WIDTH && compose.activity.window.decorView.height == HEIGHT }
        compose.setContent { RlTheme { RlPaperBackground {
            touchSlop = LocalViewConfiguration.current.touchSlop
            VocabularyBookScreen(shown.value) { action ->
                actions += action
                shown.value = when (action) {
                    is VocabularyBookAction.SenseSelected -> shown.value.copy(expandedSenseId = action.senseId)
                    VocabularyBookAction.CollapseRequested -> shown.value.copy(expandedSenseId = null)
                    else -> shown.value
                }
            }
        } } }
        compose.waitForIdle()
        assertWindow()
    }

    private fun assertWindow() {
        val resources = compose.activity.resources
        assertEquals(320, resources.configuration.densityDpi)
        assertEquals(2f, resources.displayMetrics.density, .001f)
        assertEquals(1f, resources.configuration.fontScale, .001f)
        val root = compose.onRoot().fetchSemanticsNode().layoutInfo
        assertEquals(WIDTH, root.width); assertEquals(HEIGHT, root.height)
    }

    private fun rowMatcher(id: String) = hasTestTag("vocabulary-detail-sense-$id") or hasTestTag("vocabulary-sense-$id")
    private fun row(id: String) = compose.onNode(rowMatcher(id))
    private fun text(value: String) = compose.onNodeWithText(value, useUnmergedTree = true)
    private fun byId(id: Int) = compose.onNode(SemanticsMatcher("Actual scroll node $id") { it.id == id }, useUnmergedTree = true)
    private fun windowBounds() = Rect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())
    private fun raw(node: SemanticsNode): Rect {
        val coordinates = node.layoutInfo.coordinates
        val origin = coordinates.localToRoot(Offset.Zero)
        return Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
    }
    private fun clip(node: SemanticsNode): Rect {
        val coordinates = node.layoutInfo.coordinates
        return coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(node.boundsInRoot).intersect(windowBounds())
    }

    private fun paragraph(node: SemanticsNodeInteraction, value: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals("Resolve the real Text layout before giving a geometry verdict", 1, layouts.size)
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
        val measuredWidth = layout.multiParagraph.width
        if (abs(measuredWidth - actualWidth) > 1f) {
            // Proven ParagraphLayoutCache single-line alignment adjustment only;
            // never enlarge the clip or compensate a vertical/layout overflow.
            val left = layout.getLineLeft(0)
            val right = layout.getLineRight(0)
            assertTrue("Resolve actual paragraph width first: measured=$measuredWidth actual=$actualWidth lines=${layout.lineCount}",
                layout.lineCount == 1 && layout.size.width == actualWidth && measuredWidth > actualWidth &&
                    ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt() == actualWidth)
            val align = layout.layoutInput.style.textAlign
            val isLeft = align == TextAlign.Left || ((align == TextAlign.Start || align == TextAlign.Unspecified) &&
                layout.layoutInput.layoutDirection == LayoutDirection.Ltr)
            if (!(isLeft && abs(left) <= 1f && right in 0f..(actualWidth + 1f))) {
                val offset = (measuredWidth - actualWidth) / 2f
                assertTrue("Only a verified Center alignment cache offset is removable",
                    align == TextAlign.Center && abs(left - offset) <= 1f && abs(right - (offset + actualWidth)) <= 1f)
                origin -= Offset(offset, 0f)
            }
        }
        val value = layout.layoutInput.text.text
        val boxes = (layout.getLineStart(line) until layout.getLineEnd(line, visibleEnd = true))
            .filter { !value[it].isWhitespace() }.map { layout.getBoundingBox(it).translate(origin) }
        return Glyphs(clip(semantic), boxes)
    }

    private fun readAllLines(node: SemanticsNodeInteraction, value: String): TextLayoutResult {
        val layout = paragraph(node, value)
        repeat(layout.lineCount) { line ->
            var seen = geometry(node, layout, line)
            var attempts = 0
            while (!seen.visible && attempts < 24) {
                val glyphCenter = seen.boxes.map { it.center.y }.average().toFloat()
                val scroll = generateSequence(node.fetchSemanticsNode().parent) { it.parent }.firstOrNull { ancestor ->
                    val range = ancestor.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) ?: return@firstOrNull false
                    val viewport = clip(ancestor)
                    viewport.height > touchSlop + 24f && viewport.width > 24f &&
                        if (glyphCenter > viewport.center.y) range.value() < range.maxValue() - 1f else range.value() > 1f
                } ?: break
                val viewport = clip(scroll)
                val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
                val before = range.value()
                val towardLaterContent = glyphCenter > viewport.center.y
                val distance = (abs(glyphCenter - viewport.center.y) + touchSlop)
                    .coerceIn(touchSlop + 4f, viewport.height - 16f)
                val x = seen.boxes.first().center.x.coerceIn(viewport.left + 8f, viewport.right - 8f)
                val start = Offset(x, if (towardLaterContent) viewport.bottom - 8f else viewport.top + 8f)
                val end = start + Offset(0f, if (towardLaterContent) -distance else distance)
                val rootOrigin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
                compose.onRoot().performTouchInput {
                    down(start - rootOrigin)
                    repeat(20) { step -> moveTo(start - rootOrigin + (end - start) * ((step + 1) / 20f), 60L) }
                    repeat(6) { advanceEventTime(40L); moveTo(end - rootOrigin, 0L) }
                    up()
                }
                compose.waitForIdle()
                val after = byId(scroll.id).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
                assertTrue("A physical drag must move the actual local viewport: before=$before after=$after", abs(after - before) > .5f)
                attempts++; drags++
                seen = geometry(node, layout, line)
            }
            assertTrue("Every real glyph must fit its current clip after bounded local touch: text=$value line=$line layout=${layout.size} attempts=$attempts geometry=$seen",
                seen.visible)
        }
        return layout
    }

    private fun physicalClick(point: Offset) {
        val origin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
        compose.onRoot().performTouchInput { click(point - origin) }
        compose.waitForIdle()
    }

    private fun assertNoInventedSceneImage() {
        compose.onNodeWithTag("vocabulary-representative-scene-image").assertDoesNotExist()
    }

    private fun capture(label: String) {
        val phase = InstrumentationRegistry.getArguments().getString("vocabularyExpandedSnapshot") ?: "before"
        require(phase == "before" || phase == "after")
        require(label in setOf("identity-initial", "order-initial", "switch-initial", "switch-details-read",
            "switch-program-touch", "switch-machine-touch", "switch-collapse-touch", "switch-collapsed"))
        compose.waitForIdle()
        assertWindow()
        val identity = shown.value
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val decor = compose.activity.window.decorView
        val latch = CountDownLatch(1)
        val listener = ViewTreeObserver.OnDrawListener { latch.countDown() }
        instrumentation.runOnMainSync {
            check(decor.hasWindowFocus())
            decor.viewTreeObserver.addOnDrawListener(listener)
            decor.postInvalidateOnAnimation()
        }
        try { check(latch.await(10, TimeUnit.SECONDS)) { "The actual isolated vocabulary window did not draw" } }
        finally { instrumentation.runOnMainSync { if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(listener) } }
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        compose.waitForIdle()
        assertEquals("Capture must not switch the inspected sense", identity, shown.value)
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(WIDTH, screenshot.width); assertEquals(HEIGHT, screenshot.height)
            var ink = 0
            for (y in 0 until screenshot.height step 16) for (x in 0 until screenshot.width step 16) {
                val color = screenshot.getPixel(x, y)
                if (android.graphics.Color.red(color) < 170 && android.graphics.Color.green(color) < 170 && android.graphics.Color.blue(color) < 170) ink++
            }
            assertTrue("Reject blank capture instead of claiming visual evidence", ink > 8)
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa")
            check(directory.isDirectory || directory.mkdirs())
            val output = File(directory, "41-vocabulary-expanded-$label-$phase.png")
            check(output.canonicalFile.parentFile == directory.canonicalFile)
            output.outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { screenshot.recycle() }
    }

    private fun fixture(): VocabularyBookUiState {
        val english = listOf("She runs a small café.", "He runs the business with his sister.", "They run the shop together.")
        val chinese = listOf("她经营一家小咖啡店。", "他和姐姐一起经营这家公司。", "他们一起经营这家店。")
        fun sense(id: String, meaning: String, status: KnowledgeStatus, count: Int, order: Long) = VocabularySenseRecord(
            id, "run", meaning, status, count, setOf("CET_6", "BUSINESS_ENGLISH"), order,
            phonetic = "/rʌn/", pronunciationText = "run",
            representativeScene = if (id == "run-business") "经营一家小咖啡店" else "已保存的${meaning}场景",
            examples = if (id == "run-business") english.indices.map { VocabularyExample(english[it], chinese[it], english[it]) }
                else (1..3).map { VocabularyExample("Stored $id example $it.", "${meaning}的第${it}条已存例句。", "Stored $id example $it.") },
            focusOccurrenceCount = if (id == "run-business") 2 else 0,
            contextOccurrenceCount = if (id == "run-business") 3 else 0,
        )
        return VocabularyBookUiState("六级 + 商务英语", listOf(VocabularyEntry("run", listOf(
            sense("run-motion", "跑步", KnowledgeStatus.MASTERED, 3, 1),
            sense("run-business", "经营", KnowledgeStatus.MASTERED, 1, 2),
            sense("run-program", "程序运行", KnowledgeStatus.LEARNING, 0, 3),
            sense("run-machine", "机器运转", KnowledgeStatus.NOT_STARTED, 0, 4),
        ))), expandedSenseId = "run-business", loading = false)
    }

    private companion object { const val WIDTH = 1829; const val HEIGHT = 860 }
}
