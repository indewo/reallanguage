package com.reallanguage.app.feature.knowledge.presentation

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.reallanguage.app.R
import com.reallanguage.app.core.app.PatternBookEntry
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.PatternBookRepository
import com.reallanguage.app.feature.knowledge.domain.PatternLoadResult
import com.reallanguage.app.feature.knowledge.domain.PatternRecord
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * SCREEN_SPEC 3.4. Real Entry/VM/Screen, with only local read results suspended.
 * Root supplies 1774x887/d320/font1, or 1280x720/d320/font2. No DB, account, HTTP, audio,
 * removal or AI. No runBlocking, local density override or compensating scroll.
 * State-copy predicates express the already specified object/action distinction,
 * without introducing an exact new sentence or a new production test tag.
 */
class PatternLoadStateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val model = AtomicReference<PatternBookViewModel>()
    private val homeCalls = AtomicInteger()

    @Test
    fun pendingReadShowsItsObjectAndProgressWithoutPretendingEmptyAndCanReturn() {
        val repository = DelayedReads()
        mount(repository)
        captureIfRequested("pending")
        assertPending()
        physicalClick(compose.onNodeWithTag("ledger-back"))
        compose.waitUntil(5_000) { homeCalls.get() == 1 && repository.cancelled.get() == 1 }
        assertEquals(1, repository.calls.get())
        assertEquals(0, repository.active.get())
        compose.onNodeWithTag("ledger-back").assertDoesNotExist()
    }

    @Test
    fun failedReadThenPhysicalRetryWaitsOnceAndOnlySuccessfulEmptyShowsEmpty() {
        retryJourney(emptyList(), "empty")
    }

    @Test
    fun failedReadThenPhysicalRetryWaitsOnceAndShowsTheActualStoredPattern() {
        retryJourney(listOf(PATTERN), "ready")
    }

    private fun retryJourney(finalPatterns: List<PatternRecord>, label: String) {
        val repository = DelayedReads()
        mount(repository)
        repository.answers[0].complete(PatternLoadResult.Failed)
        compose.waitUntil(5_000) { model.get()?.uiState?.value?.loadFailed == true }
        captureIfRequested("failed-$label")
        assertNotEmptyOrNoMatch()
        progress().assertDoesNotExist()
        assertWholeText(failedCopy())
        val retry = compose.onNodeWithText(string(R.string.diary_retry))
        retry.assertIsDisplayed().assertIsEnabled()
        assertWholeText(compose.onNodeWithText(string(R.string.diary_retry), useUnmergedTree = true))
        assertBackVisible()
        val capturedRetry = requireNotNull(retry.fetchSemanticsNode().config[SemanticsActions.OnClick].action)

        // Both intentions use the same real on-screen point. Do not perform a
        // second query for a button after the first click has changed the page.
        val point = actualControlCenter(retry)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            click(point - root.topLeft)
            advanceEventTime(60L)
            click(point - root.topLeft)
        }
        compose.waitUntil(5_000) { repository.calls.get() >= 2 && model.get()?.uiState?.value?.loading == true }
        captureIfRequested("retry-$label")
        assertEquals("Two physical retry taps issue only one new read", 2, repository.calls.get())
        assertEquals("Read operations never overlap", 1, repository.maxActive.get())
        assertEquals(1, repository.active.get())
        assertPending()
        failedCopy().assertDoesNotExist()
        // Existing retry intents also remain guarded while this actual read is
        // pending, even if an old click callback was already queued by the UI.
        compose.runOnIdle { repeat(2) { capturedRetry() } }
        compose.waitForIdle()
        assertEquals(2, repository.calls.get())

        repository.answers[1].complete(PatternLoadResult.Ready(finalPatterns))
        compose.waitUntil(5_000) { model.get()?.uiState?.value?.let { !it.loading && !it.loadFailed } == true }
        captureIfRequested("complete-$label")
        progress().assertDoesNotExist()
        loadingCopy().assertDoesNotExist()
        failedCopy().assertDoesNotExist()
        compose.onNodeWithText(string(R.string.diary_retry)).assertDoesNotExist()
        if (finalPatterns.isEmpty()) {
            assertWholeText(compose.onNodeWithText(string(R.string.pattern_book_empty), useUnmergedTree = true))
            compose.onNodeWithTag("pattern-row-${PATTERN.patternId}").assertDoesNotExist()
        } else {
            assertNotEmptyOrNoMatch()
            compose.onNodeWithTag("pattern-row-${PATTERN.patternId}").assertIsDisplayed()
            assertWholeText(compose.onNodeWithText(PATTERN.structureEn, useUnmergedTree = true))
            assertWholeText(compose.onNodeWithText(PATTERN.functionZh, useUnmergedTree = true))
            compose.onNodeWithText("会写\n已掌握").assertIsDisplayed()
            compose.onNodeWithText("会说\n学习中").assertIsDisplayed()
        }
        compose.runOnIdle {
            assertEquals(finalPatterns, model.get().uiState.value.patterns)
            assertEquals(null, model.get().uiState.value.expandedPatternId)
        }
        assertEquals(2, repository.calls.get())
        assertEquals(0, repository.active.get())
        assertEquals(0, homeCalls.get())
        physicalClick(compose.onNodeWithTag("ledger-back"))
        compose.waitUntil(5_000) { homeCalls.get() == 1 }
    }

    private fun assertPending() {
        assertNotEmptyOrNoMatch()
        assertWholeText(compose.onNodeWithText("句式本", useUnmergedTree = true))
        progress().assertIsDisplayed()
        assertWholeText(loadingCopy())
        assertBackVisible()
        compose.onNodeWithText(string(R.string.diary_retry)).assertDoesNotExist()
    }

    private fun assertNotEmptyOrNoMatch() {
        compose.onNodeWithText(string(R.string.pattern_book_empty)).assertDoesNotExist()
        compose.onNodeWithText("没有匹配的句式或功能").assertDoesNotExist()
    }

    private fun progress() = compose.onNode(SemanticsMatcher.expectValue(
        SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
    ))

    private fun loadingCopy() = stateCopy("current pattern read") {
        it.contains("句式") && (it.contains("读取") || it.contains("加载")) &&
            !it.contains("失败") && !it.contains("无法") && !it.contains("未")
    }

    private fun failedCopy() = stateCopy("failed pattern read, not just a retry label") {
        it.contains("句式") && (it.contains("失败") || it.contains("无法") || it.contains("未"))
    }

    private fun stateCopy(description: String, matches: (String) -> Boolean) = compose.onNode(
        SemanticsMatcher(description) { node -> node.config.contains(SemanticsProperties.Text) &&
            node.config[SemanticsProperties.Text].any { matches(it.text) } },
        useUnmergedTree = true,
    )

    private fun assertBackVisible() { actualControlCenter(compose.onNodeWithTag("ledger-back")) }

    private fun physicalClick(node: SemanticsNodeInteraction) {
        val point = actualControlCenter(node)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { click(point - root.topLeft) }
    }

    private fun actualControlCenter(node: SemanticsNodeInteraction): Offset {
        val semantic = node.assertIsDisplayed().assertIsEnabled().fetchSemanticsNode()
        val coordinates = semantic.layoutInfo.coordinates
        val point = coordinates.localToRoot(Offset(coordinates.size.width / 2f, coordinates.size.height / 2f))
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(semantic.boundsInRoot)
        assertTrue("The physical action center must be in its real visible control", clip.contains(point))
        return point
    }

    private fun assertWholeText(node: SemanticsNodeInteraction) {
        val semantic = node.assertIsDisplayed().fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals(1, layouts.size)
        val layout = layouts.single()
        val text = layout.layoutInput.text.text
        assertTrue(text.isNotBlank() && layout.lineCount > 0)
        assertFalse(layout.multiParagraph.didExceedMaxLines)
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
        val coordinates = semantic.layoutInfo.coordinates
        val origin = coordinates.localToRoot(Offset.Zero)
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true).intersect(semantic.boundsInRoot)
        repeat(layout.lineCount) { line ->
            assertFalse(layout.isLineEllipsized(line))
            val glyphs = (layout.getLineStart(line) until layout.getLineEnd(line, visibleEnd = true))
                .filter { !text[it].isWhitespace() }.map { layout.getBoundingBox(it).translate(origin) }
            assertTrue("State copy must fit without scrolling: text=$text line=$line clip=$clip glyphs=$glyphs",
                glyphs.isNotEmpty() && glyphs.all { it.left >= clip.left - 1f && it.right <= clip.right + 1f &&
                    it.top >= clip.top - 1f && it.bottom <= clip.bottom + 1f })
        }
    }

    private fun mount(repository: DelayedReads) {
        val shown = mutableStateOf(true)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
                check(modelClass == PatternBookViewModel::class.java)
                return PatternBookViewModel(repository).also(model::set) as T
            }
        }
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.window.decorView.width == WIDTH && compose.activity.window.decorView.height == HEIGHT }
        compose.setContent { RlTheme { if (shown.value) {
            PatternBookEntry(factory, { _, _ -> error("Load-state rendering must not request removal") },
                { homeCalls.incrementAndGet(); shown.value = false },
                { error("Load-state rendering must not request audio") })
        } } }
        compose.waitUntil(5_000) { repository.calls.get() == 1 && model.get()?.uiState?.value?.loading == true }
        compose.waitForIdle()
        assertWindow()
    }

    private fun assertWindow() {
        val resources = compose.activity.resources
        assertEquals(320, resources.configuration.densityDpi)
        assertEquals(2f, resources.displayMetrics.density, .001f)
        assertEquals(if (shortViewport) 2f else 1f, resources.configuration.fontScale, .001f)
        val root = compose.onRoot().fetchSemanticsNode().layoutInfo
        assertEquals(WIDTH, root.width); assertEquals(HEIGHT, root.height)
    }

    private fun captureIfRequested(label: String) {
        val phase = InstrumentationRegistry.getArguments().getString("patternLoadSnapshot") ?: return
        require(phase in setOf("before", "after") && label in setOf("pending", "failed-empty", "failed-ready",
            "retry-empty", "retry-ready", "complete-empty", "complete-ready"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.waitForIdle()
        assertWindow()
        val stateBefore = model.get().uiState.value
        val decor = compose.activity.window.decorView
        val drawn = CountDownLatch(1)
        val listener = ViewTreeObserver.OnDrawListener { drawn.countDown() }
        instrumentation.runOnMainSync {
            check(decor.hasWindowFocus())
            decor.viewTreeObserver.addOnDrawListener(listener)
            decor.postInvalidateOnAnimation()
        }
        try { check(drawn.await(10, TimeUnit.SECONDS)) { "The actual pattern load-state window did not draw" } }
        finally { instrumentation.runOnMainSync { if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(listener) } }
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        compose.waitForIdle()
        assertEquals(stateBefore, model.get().uiState.value)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(WIDTH, bitmap.width); assertEquals(HEIGHT, bitmap.height)
            var ink = 0
            for (y in 0 until bitmap.height step 16) for (x in 0 until bitmap.width step 16) {
                val color = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(color) < 170 && android.graphics.Color.green(color) < 170 && android.graphics.Color.blue(color) < 170) ink++
            }
            assertTrue("Blank frames are not state evidence", ink > 8)
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa")
            check(directory.isDirectory || directory.mkdirs())
            val output = File(directory, "42-pattern-load-$label-$phase${if (shortViewport) "-short" else ""}.png")
            check(output.canonicalFile.parentFile == directory.canonicalFile)
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun string(id: Int) = compose.activity.getString(id)

    private class DelayedReads : PatternBookRepository {
        val answers = List(2) { CompletableDeferred<PatternLoadResult>() }
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val cancelled = AtomicInteger()
        override suspend fun loadAllPatterns(): PatternLoadResult {
            val index = calls.getAndIncrement()
            check(index in answers.indices) { "Unexpected duplicate repository read" }
            val current = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, current) }
            return try { answers[index].await() }
            catch (error: CancellationException) { cancelled.incrementAndGet(); throw error }
            finally { active.decrementAndGet() }
        }
    }

    private companion object {
        val shortViewport = when (InstrumentationRegistry.getArguments().getString("knowledgeLoadViewport")) {
            null, "reference" -> false
            "short" -> true
            else -> error("Unknown knowledge loading viewport")
        }
        val WIDTH = if (shortViewport) 1280 else 1774
        val HEIGHT = if (shortViewport) 720 else 887
        val PATTERN = PatternRecord("render-only-pattern", "used to + verb", "表示过去的习惯",
            KnowledgeStatus.MASTERED, KnowledgeStatus.LEARNING, 2, 4)
    }
}
