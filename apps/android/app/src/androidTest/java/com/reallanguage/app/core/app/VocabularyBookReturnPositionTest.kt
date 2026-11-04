package com.reallanguage.app.core.app

import android.content.pm.ActivityInfo
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.VocabularyBookRepository
import com.reallanguage.app.feature.knowledge.domain.VocabularyLoadResult
import com.reallanguage.app.feature.knowledge.domain.VocabularySenseRecord
import com.reallanguage.app.feature.knowledge.presentation.VocabularyBookViewModel
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * SPEC FR-NAV-004 / SCREEN_SPEC KB-02: inline details preserve the list position.
 * Real Entry, ViewModel and Compose interactions; only the 20-record repository
 * is an isolated in-memory fixture. No user database, HTTP, AI or TTS is accessed.
 */
class VocabularyBookReturnPositionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun realEntryReturnsToTheSameListPositionAfterSwitchingTheExpandedSense() {
        val records = records()
        val loads = AtomicInteger()
        val creations = AtomicInteger()
        val pronunciationCalls = AtomicInteger()
        val homeCalls = AtomicInteger()
        var model: VocabularyBookViewModel? = null
        val repository = object : VocabularyBookRepository {
            override suspend fun loadAllRecords(): VocabularyLoadResult {
                loads.incrementAndGet()
                return VocabularyLoadResult.Ready(setOf("CET4"), records)
            }
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                check(modelClass == VocabularyBookViewModel::class.java)
                creations.incrementAndGet()
                return VocabularyBookViewModel(repository).also { model = it } as T
            }
        }
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.window.decorView.width == 1829 && compose.activity.window.decorView.height == 860 }
        compose.setContent { RlTheme { RlPaperBackground {
            VocabularyBookEntry(factory, { homeCalls.incrementAndGet() }, {
                pronunciationCalls.incrementAndGet()
                error("This return-position test must not request pronunciation")
            })
        } } }
        compose.waitUntil(5_000) { model?.uiState?.value?.entries?.sumOf { it.senses.size } == 20 }
        compose.waitForIdle()
        assertActualWindow()
        assertEquals(1, loads.get())
        assertEquals(1, creations.get())
        val originalRecords = requireNotNull(model).uiState.value.entries

        dragListToItsRealEnd()
        val beforeOffset = listScroll()
        val beforeRows = visibleRows(records)
        val lastBefore = rowOrigin("sense-20")
        assertTrue("A real nonzero list position is required", beforeOffset.first > 0f)
        assertTrue("The last row really is visible before opening it", beforeRows.contains("sense-20"))
        captureIfRequested("list-before-open")

        physicalClick("vocabulary-sense-sense-20")
        compose.waitUntil(5_000) { model?.uiState?.value?.expandedSenseId == "sense-20" }
        compose.onNodeWithTag("vocabulary-detail-sheet").assertIsDisplayed()
        compose.onNodeWithTag("vocabulary-sense-list").assertDoesNotExist()
        physicalClick("vocabulary-detail-sense-sense-19")
        compose.waitUntil(5_000) { model?.uiState?.value?.expandedSenseId == "sense-19" }
        compose.runOnIdle {
            assertEquals("The real VM directly replaces the current meaning", "sense-19", model?.uiState?.value?.expandedSense?.senseId)
            assertEquals("Opening/switching does not mutate saved list content", originalRecords, model?.uiState?.value?.entries)
        }
        physicalClick("vocabulary-detail-collapse")
        compose.waitUntil(5_000) { model?.uiState?.value?.expandedSenseId == null }
        compose.waitForIdle()
        compose.onNodeWithTag("vocabulary-sense-list").assertIsDisplayed()
        compose.onNodeWithTag("vocabulary-detail-sheet").assertDoesNotExist()

        // Observe immediately, without ScrollTo, compensating swipes, a recreated
        // ViewModel or manually supplying a remembered state from this test.
        val afterOffset = listScroll()
        val afterRows = visibleRows(records)
        val lastAfter = rowOrigin("sense-20")
        captureIfRequested("list-after-collapse")
        val diagnostic = "before=$beforeOffset after=$afterOffset beforeRows=$beforeRows afterRows=$afterRows " +
            "lastBefore=$lastBefore lastAfter=$lastAfter"
        assertEquals("Collapse preserves the real list offset; $diagnostic", beforeOffset.first, afterOffset.first, 1f)
        assertEquals("The unchanged records keep the same scroll extent", beforeOffset.second, afterOffset.second, 1f)
        assertEquals("The same rows remain in view; $diagnostic", beforeRows, afterRows)
        assertEquals("The last row keeps its vertical screen position", lastBefore.y, lastAfter.y, 1f)
        assertEquals("The last row keeps its horizontal screen position", lastBefore.x, lastAfter.x, 1f)
        compose.runOnIdle {
            assertEquals(1, loads.get())
            assertEquals(1, creations.get())
            assertEquals(0, pronunciationCalls.get())
            assertEquals(0, homeCalls.get())
            assertEquals(originalRecords, model?.uiState?.value?.entries)
        }
    }

    private fun records(): List<VocabularySenseRecord> {
        val simple = listOf(
            "book" to "图书", "calm" to "平静", "desk" to "书桌", "early" to "提早", "field" to "田野", "glass" to "玻璃",
            "home" to "家", "idea" to "想法", "kind" to "友善", "light" to "光线", "music" to "音乐", "note" to "笔记",
            "open" to "打开", "paper" to "纸张", "quiet" to "安静", "room" to "房间", "table" to "桌子", "water" to "水",
        ).mapIndexed { index, (word, meaning) ->
            VocabularySenseRecord("sense-${index + 1}", word, meaning, KnowledgeStatus.LEARNING, 0, setOf("CET4"), (index + 1).toLong())
        }
        return simple + listOf(
            VocabularySenseRecord("sense-19", "run", "跑步", KnowledgeStatus.MASTERED, 3, setOf("CET4"), 19),
            VocabularySenseRecord("sense-20", "run", "经营", KnowledgeStatus.LEARNING, 1, setOf("CET4"), 20),
        )
    }

    private fun assertActualWindow() {
        val resources = compose.activity.resources
        assertEquals(320, resources.configuration.densityDpi)
        assertEquals(2f, resources.displayMetrics.density, .001f)
        assertEquals(1f, resources.configuration.fontScale, .001f)
        assertEquals(1829, compose.activity.window.decorView.width)
        assertEquals(860, compose.activity.window.decorView.height)
        val root = compose.onRoot().fetchSemanticsNode().layoutInfo
        assertEquals(1829, root.width); assertEquals(860, root.height)
    }

    private fun listScroll(): Pair<Float, Float> {
        val range = compose.onNodeWithTag("vocabulary-sense-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        return range.value() to range.maxValue()
    }

    private fun dragListToItsRealEnd() {
        var gestures = 0
        while (listScroll().first < listScroll().second - 1f && gestures < 50) {
            val before = listScroll()
            val viewport = compose.onNodeWithTag("vocabulary-sense-list")
            val bounds = viewport.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height > 100f)
            viewport.performTouchInput {
                swipe(Offset(bounds.width / 2f, bounds.height - 16f), Offset(bounds.width / 2f, 16f), 300)
            }
            compose.waitForIdle()
            assertTrue("A physical drag must move the real list", listScroll().first > before.first)
            gestures++
        }
        assertTrue("Reach the actual end with bounded physical gestures", gestures > 0 && listScroll().first >= listScroll().second - 1f)
    }

    private fun physicalClick(tag: String) {
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().fetchSemanticsNode()
        assertTrue(node.config.contains(SemanticsActions.OnClick))
        val coordinates = node.layoutInfo.coordinates
        val point = coordinates.localToRoot(Offset(coordinates.size.width / 2f, coordinates.size.height / 2f))
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(node.boundsInRoot).intersect(Rect(0f, 0f, 1829f, 860f))
        assertTrue("The promised action's actual center must be visible: tag=$tag point=$point clip=$clip", clip.contains(point))
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { click(point - root.topLeft) }
        compose.waitForIdle()
    }

    private fun rowOrigin(senseId: String): Offset = compose.onNodeWithTag("vocabulary-sense-$senseId")
        .fetchSemanticsNode().layoutInfo.coordinates.localToRoot(Offset.Zero)

    private fun visibleRows(records: List<VocabularySenseRecord>): List<String> {
        val viewport = compose.onNodeWithTag("vocabulary-sense-list").fetchSemanticsNode().boundsInRoot
        return records.mapNotNull { record ->
            val node = compose.onNodeWithTag("vocabulary-sense-${record.senseId}").fetchSemanticsNode()
            val coordinates = node.layoutInfo.coordinates
            val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
                .intersect(node.boundsInRoot).intersect(viewport).intersect(Rect(0f, 0f, 1829f, 860f))
            record.senseId.takeIf { clip.width > 1f && clip.height > 1f }
        }
    }

    private fun captureIfRequested(label: String) {
        val phase = InstrumentationRegistry.getArguments().getString("vocabularyReturnSnapshot") ?: return
        require(phase in setOf("before", "after") && label in setOf("list-before-open", "list-after-collapse"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.waitForIdle()
        assertActualWindow()
        val before = listScroll()
        val decor = compose.activity.window.decorView
        val drawn = CountDownLatch(1)
        val listener = ViewTreeObserver.OnDrawListener { drawn.countDown() }
        instrumentation.runOnMainSync {
            assertTrue(decor.hasWindowFocus())
            decor.viewTreeObserver.addOnDrawListener(listener)
            decor.postInvalidateOnAnimation()
        }
        try { check(drawn.await(10, TimeUnit.SECONDS)) { "Actual vocabulary window did not draw" } }
        finally { instrumentation.runOnMainSync { if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(listener) } }
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        compose.waitForIdle()
        assertEquals(before, listScroll())
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(1829, image.width); assertEquals(860, image.height)
            var ink = 0
            for (y in 0 until image.height step 16) for (x in 0 until image.width step 16) {
                val color = image.getPixel(x, y)
                if (android.graphics.Color.red(color) < 170 && android.graphics.Color.green(color) < 170 && android.graphics.Color.blue(color) < 170) ink++
            }
            assertTrue("Blank frames are not evidence", ink > 8)
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa")
            check(directory.isDirectory || directory.mkdirs())
            val output = File(directory, "40-vocabulary-return-$label-$phase.png")
            check(output.canonicalFile.parentFile == directory.canonicalFile)
            output.outputStream().use { check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }
}
