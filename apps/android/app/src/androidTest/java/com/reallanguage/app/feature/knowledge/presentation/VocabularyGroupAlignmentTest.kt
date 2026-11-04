package com.reallanguage.app.feature.knowledge.presentation

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.compose.ui.test.swipeUp
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * FR-VOC-001A / AC-E2E-046, root-controlled 1829x860 / d320 / system font 1.
 * Two stored English forms, each with two distinct sense fixtures and reliable
 * shared pronunciation metadata. Rendering only: no Graph, repository, TTS, AI,
 * account or data access. No additional scroll wrapper or production test tags.
 */
class VocabularyGroupAlignmentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun defaultTwoEnglishGroupsHaveOneSharedLeftLabelBesideTheirOwnSenseRows() {
        val state = fixture()
        val actions = render(state)
        captureIfRequested("default-initial")
        val pronunciation = compose.activity.getString(R.string.vocabulary_detail_pronunciation)
        state.entries.forEach { entry ->
            compose.onAllNodesWithText(entry.englishForm, useUnmergedTree = true).assertCountEquals(1)
        }
        compose.onAllNodesWithText(pronunciation, useUnmergedTree = true).assertCountEquals(2)
        state.entries.forEach { entry ->
            val phonetic = requireNotNull(entry.senses.first().phonetic)
            compose.onAllNodesWithText(phonetic, useUnmergedTree = true).assertCountEquals(1)
            val english = compose.onNodeWithText(entry.englishForm, useUnmergedTree = true)
            val sound = compose.onNodeWithText(phonetic, useUnmergedTree = true)
            assertWholeText(english, entry.englishForm)
            assertWholeText(sound, phonetic)
            assertInList(english)
            assertInList(sound)
            val rows = entry.senses.map { sense ->
                compose.onAllNodesWithText(sense.meaningZh, useUnmergedTree = true).assertCountEquals(1)
                val meaning = compose.onNode(hasText(sense.meaningZh) and
                    hasAnyAncestor(hasTestTag(senseTag(sense.senseId))), useUnmergedTree = true)
                assertWholeText(meaning, sense.meaningZh)
                senseRow(sense.senseId).assertIsDisplayed()
                rawBounds(senseRow(sense.senseId))
            }
            assertTrue("Each meaning occupies its own row", rows[0].bottom <= rows[1].top + 1f)
            val englishCoordinates = english.fetchSemanticsNode().layoutInfo.coordinates
            val rowCoordinates = entry.senses.map { senseRow(it.senseId).fetchSemanticsNode().layoutInfo.coordinates }
            val group = generateSequence(englishCoordinates) { it.parentLayoutCoordinates }
                .first { ancestor -> rowCoordinates.all { belongsTo(it, ancestor) } }
            // Use the real merged group's layout, not an invented group tag or
            // a demand that two short right rows must be taller than all audio
            // controls in the left cell. It must not include the other group.
            state.entries.filter { it.englishForm != entry.englishForm }.flatMap { it.senses }.forEach {
                assertFalse("A merged English cell cannot borrow another English group's vertical region",
                    belongsTo(senseRow(it.senseId).fetchSemanticsNode().layoutInfo.coordinates, group))
            }
            val groupBounds = rawBounds(group)
            val groupTop = groupBounds.top
            val groupBottom = groupBounds.bottom
            val meaningsLeft = rows.minOf { it.left }
            listOf(english, sound).forEach { label ->
                assertTrue(belongsTo(label.fetchSemanticsNode().layoutInfo.coordinates, group))
                val bounds = rawBounds(label)
                assertTrue("${entry.englishForm} shared label is left of its own meaning rows: label=$bounds rows=$rows",
                    bounds.right <= meaningsLeft + 1f)
                assertTrue("${entry.englishForm} shared label belongs to this group's vertical span: label=$bounds rows=$rows",
                    bounds.top >= groupTop - 1f && bounds.bottom <= groupBottom + 1f)
            }
            val speakers = compose.onAllNodesWithText(pronunciation, useUnmergedTree = true)
                .fetchSemanticsNodes().filter { node ->
                    val coordinates = node.layoutInfo.coordinates
                    val origin = coordinates.localToRoot(Offset.Zero)
                    origin.x + coordinates.size.width <= meaningsLeft + 1f &&
                        origin.y >= groupTop - 1f && origin.y + coordinates.size.height <= groupBottom + 1f
                }
            assertEquals("Reliable same-reading senses share one speaker in their own English cell", 1, speakers.size)
            val speaker = byId(speakers.single().id)
            assertWholeText(speaker, pronunciation)
            assertInList(speaker)
            assertTrue(belongsTo(speaker.fetchSemanticsNode().layoutInfo.coordinates, group))
            assertTrue("The visible speaker label belongs to a real clickable control",
                generateSequence(speakers.single()) { it.parent }.any { it.config.contains(SemanticsActions.OnClick) })
        }
        assertEquals("Initial rendering neither selects a sense nor starts audio", emptyList<VocabularyBookAction>(), actions)
    }

    @Test
    fun defaultListDoesNotRevealRepresentativeScenesExamplesOrLearningDetail() {
        val state = fixture()
        val actions = render(state)
        captureIfRequested("default-details")
        state.entries.flatMap { it.senses }.forEach { sense ->
            senseRow(sense.senseId).assertExists()
            compose.onNodeWithText(requireNotNull(sense.representativeScene)).assertDoesNotExist()
            sense.examples.forEach { example ->
                compose.onNodeWithText(example.textEn).assertDoesNotExist()
                compose.onNodeWithText(example.textZh).assertDoesNotExist()
            }
        }
        listOf("代表场景", "重点例句", "学习统计", "收回").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithTag("vocabulary-detail-sheet").assertDoesNotExist()
        assertTrue(actions.isEmpty())
    }

    @Test
    fun physicalListDragMovesEachEnglishWithItsOwnTwoSenseRows() {
        val state = fixture(longMeaning = true)
        val actions = render(state)
        val initial = scrollPosition()
        assertEquals(0f, initial.first, .5f)
        assertTrue("Long meanings must produce a genuine production scroll range", initial.second > 80f)
        // The production list is intentionally lazy because a real pool contains
        // thousands of words. Only visible groups may exist in semantics at once.
        compose.onNodeWithText("run", useUnmergedTree = true).assertIsDisplayed()
        captureIfRequested("scroll-initial")
        repeat(3) { list().performTouchInput { swipeUp() }; compose.waitForIdle() }
        compose.waitForIdle()
        val moved = scrollPosition()
        assertTrue("A dispatched drag is not evidence of movement: before=$initial after=$moved", moved.first > initial.first + 5f)
        captureIfRequested("scroll-moved")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("calm", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("calm", useUnmergedTree = true).assertIsDisplayed()
        senseRow("calm-state").assertIsDisplayed()
        senseRow("calm-action").assertIsDisplayed()
        assertTrue("Scrolling cannot select a sense or trigger pronunciation", actions.isEmpty())
    }

    private fun fixture(longMeaning: Boolean = false): VocabularyBookUiState {
        val explanation = "经营并负责安排商店的日常事务，包括协调人员、安排时间和合理使用资源。这里强调承担组织责任，而不是人在道路上跑步，也不是机器正在运转。"
        fun sense(id: String, english: String, meaning: String, order: Long, phonetic: String) = VocabularySenseRecord(
            id, english, meaning, if (order % 2L == 1L) KnowledgeStatus.MASTERED else KnowledgeStatus.LEARNING,
            if (order % 2L == 1L) 3 else 0, setOf("CET4"), order,
            phonetic = phonetic, pronunciationText = english,
            representativeScene = "仅详情可见的已存场景：$id",
            examples = listOf(
                VocabularyExample("Stored example one for $id.", "该词义的第一条已存例句：$id", "Stored example one for $id."),
                VocabularyExample("Stored example two for $id.", "该词义的第二条已存例句：$id", "Stored example two for $id."),
                VocabularyExample("Stored example three for $id.", "该词义的第三条已存例句：$id", "Stored example three for $id."),
            ),
            focusOccurrenceCount = 2, contextOccurrenceCount = 5,
        )
        val entries = listOf(
            VocabularyEntry("run", listOf(sense("run-motion", "run", "跑步", 1, "/rʌn/"),
                sense("run-business", "run", if (longMeaning) explanation.repeat(6) else "经营", 2, "/rʌn/"))),
            VocabularyEntry("calm", listOf(sense("calm-state", "calm", "平静的", 3, "/kɑːm/"),
                sense("calm-action", "calm", "使平静下来", 4, "/kɑːm/"))),
        )
        entries.forEach { entry ->
            check(entry.senses.size == 2 && entry.senses.all { it.englishForm == entry.englishForm })
            check(entry.senses.map { it.phonetic }.distinct().size == 1)
            check(entry.senses.map { it.pronunciationText }.distinct() == listOf(entry.englishForm))
        }
        return VocabularyBookUiState("CET4", entries, loading = false)
    }

    private fun render(state: VocabularyBookUiState): MutableList<VocabularyBookAction> {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.window.decorView.width == WIDTH && compose.activity.window.decorView.height == HEIGHT }
        val actions = mutableListOf<VocabularyBookAction>()
        compose.setContent { RlTheme { RlPaperBackground { VocabularyBookScreen(state, actions::add) } } }
        compose.waitForIdle()
        assertActualWindow()
        list().assertIsDisplayed()
        return actions
    }

    private fun assertActualWindow() {
        val resources = compose.activity.resources
        assertEquals(320, resources.configuration.densityDpi)
        assertEquals(2f, resources.displayMetrics.density, .001f)
        assertEquals(1f, resources.configuration.fontScale, .001f)
        val root = compose.onRoot().fetchSemanticsNode().layoutInfo
        assertEquals(WIDTH, root.width); assertEquals(HEIGHT, root.height)
    }

    private fun senseTag(id: String) = "vocabulary-sense-$id"
    private fun senseRow(id: String) = compose.onNodeWithTag(senseTag(id))
    private fun list() = compose.onNodeWithTag("vocabulary-sense-list")
    private fun byId(id: Int) = compose.onNode(SemanticsMatcher("Existing production node $id") { it.id == id }, useUnmergedTree = true)
    private fun scrollPosition(): Pair<Float, Float> {
        val range = list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        return range.value() to range.maxValue()
    }

    private fun assertInList(node: SemanticsNodeInteraction) {
        val id = list().fetchSemanticsNode().id
        assertTrue("English, phonetic and speaker belong to the same moving list as their sense rows",
            generateSequence(node.fetchSemanticsNode()) { it.parent }.any { it.id == id })
    }

    private fun rawBounds(node: SemanticsNodeInteraction): Rect {
        return rawBounds(node.fetchSemanticsNode().layoutInfo.coordinates)
    }

    private fun belongsTo(coordinates: LayoutCoordinates, ancestor: LayoutCoordinates) =
        generateSequence(coordinates) { it.parentLayoutCoordinates }.any { it === ancestor }

    private fun rawBounds(coordinates: LayoutCoordinates): Rect {
        val origin = coordinates.localToRoot(Offset.Zero)
        return Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
    }

    private fun assertWholeText(node: SemanticsNodeInteraction, expected: String) {
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals(1, layouts.size)
        val layout = layouts.single()
        assertEquals(expected, layout.layoutInput.text.text)
        assertEquals(2f, layout.layoutInput.density.density, .001f)
        assertEquals(1f, layout.layoutInput.density.fontScale, .001f)
        assertTrue(layout.lineCount > 0)
        assertFalse(layout.multiParagraph.didExceedMaxLines)
        assertEquals(expected.length, layout.getLineEnd(layout.lineCount - 1))
        val align = layout.layoutInput.style.textAlign
        assertTrue("These existing Start/Left Text nodes do not need semantic Center correction",
            align == TextAlign.Left || ((align == TextAlign.Start || align == TextAlign.Unspecified) &&
                layout.layoutInput.layoutDirection == LayoutDirection.Ltr))
        val semantic = node.fetchSemanticsNode()
        val coordinates = semantic.layoutInfo.coordinates
        val origin = coordinates.localToRoot(Offset.Zero)
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true).intersect(semantic.boundsInRoot)
        repeat(layout.lineCount) { line ->
            assertFalse(layout.isLineEllipsized(line))
            val glyphs = (layout.getLineStart(line) until layout.getLineEnd(line, visibleEnd = true))
                .filter { !expected[it].isWhitespace() }.map { layout.getBoundingBox(it).translate(origin) }
            assertTrue("A short normal label must fit its real clip: text=$expected clip=$clip glyphs=$glyphs",
                glyphs.isNotEmpty() && glyphs.all { it.left >= clip.left - 1f && it.right <= clip.right + 1f &&
                    it.top >= clip.top - 1f && it.bottom <= clip.bottom + 1f })
        }
    }

    private fun captureIfRequested(label: String) {
        val phase = InstrumentationRegistry.getArguments().getString("vocabularyGroupSnapshot") ?: return
        require(phase in setOf("before", "after") && label in setOf("default-initial", "default-details", "scroll-initial", "scroll-moved"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.waitForIdle()
        assertActualWindow()
        val position = scrollPosition()
        val decor = compose.activity.window.decorView
        val drawn = CountDownLatch(1)
        val listener = ViewTreeObserver.OnDrawListener { drawn.countDown() }
        instrumentation.runOnMainSync {
            check(decor.hasWindowFocus())
            decor.viewTreeObserver.addOnDrawListener(listener)
            decor.postInvalidateOnAnimation()
        }
        try { check(drawn.await(10, TimeUnit.SECONDS)) { "The isolated vocabulary window did not draw" } }
        finally { instrumentation.runOnMainSync { if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(listener) } }
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        compose.waitForIdle()
        assertEquals("Capture cannot move the inspected group", position, scrollPosition())
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(WIDTH, bitmap.width); assertEquals(HEIGHT, bitmap.height)
            var ink = 0
            for (y in 0 until bitmap.height step 16) for (x in 0 until bitmap.width step 16) {
                val color = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(color) < 170 && android.graphics.Color.green(color) < 170 && android.graphics.Color.blue(color) < 170) ink++
            }
            assertTrue("A blank frame is not visual evidence", ink > 8)
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa")
            check(directory.isDirectory || directory.mkdirs())
            val output = File(directory, "40-vocabulary-groups-$label-$phase.png")
            check(output.canonicalFile.parentFile == directory.canonicalFile)
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private companion object { const val WIDTH = 1829; const val HEIGHT = 860 }
}
