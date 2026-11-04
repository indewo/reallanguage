package com.reallanguage.app.core.app

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.VocabularyBookRepository
import com.reallanguage.app.feature.knowledge.domain.VocabularyLoadResult
import com.reallanguage.app.feature.knowledge.domain.VocabularySenseRecord
import com.reallanguage.app.feature.knowledge.presentation.VocabularyBookViewModel
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Actual 1829x860/d320/font1, or 1280x720/d320/font2 Entry + VM, with only an in-memory deferred
 * repository. Screenshots precede visibility assertions. No Graph, saved data,
 * account, HTTP, SQL, audio or AI is used; Ready means read completion, not a save.
 */
class VocabularyLoadStateEntryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun firstPendingReadShowsActualVisibleLoadingRatherThanSuccessfulEmptyContent() {
        val harness = mount()
        capture("initial-pending")
        assertEquals(1, harness.loads.get())
        assertTrue(harness.model.uiState.value.loading)
        assertFalse(harness.model.uiState.value.loadFailed)
        emptyMessage().assertDoesNotExist()
        retry().assertDoesNotExist()
        assertWholeControl(progress())
        assertTrue("Loading must identify the visible action and object (读取 + 词汇), with every glyph unclipped", visibleLoadingObjectAndAction())
        assertNoSideEffects(harness, expectedLoads = 1)
    }

    @Test
    fun failedReadPhysicalRetryAndStaleClicksWaitForOnlyOneSecondReadThenShowItsActualGroup() {
        val harness = mount()
        capture("flow-first-pending")
        val firstLoadingVisible = visibleProgress()
        val firstLoadingExplained = visibleLoadingObjectAndAction()
        emptyMessage().assertDoesNotExist()

        compose.runOnIdle { assertTrue(harness.first.complete(VocabularyLoadResult.Failed)) }
        compose.waitUntil(5_000) { harness.model.uiState.value.loadFailed }
        capture("flow-failed")
        assertFalse(harness.model.uiState.value.loading)
        assertTrue(harness.model.uiState.value.entries.isEmpty())
        emptyMessage().assertDoesNotExist()
        progress().assertDoesNotExist()
        val failureExplained = visibleFailureExplanation()
        val retryNode = retry().assertIsDisplayed().assertIsEnabled()
        assertWholeControl(retryNode)
        val originalRetry = requireNotNull(retryNode.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        physicalClickTwice(retryNode)
        compose.waitUntil(5_000) { harness.loads.get() == 2 && harness.model.uiState.value.loading }

        // Replays the formerly visible button's real callback while the new read
        // is pending. The first retry above remains an actual physical click;
        // this does not call the ViewModel directly or manufacture a new action.
        compose.runOnIdle { repeat(3) { assertTrue(originalRetry()) } }
        compose.waitForIdle()
        assertEquals("Pending retry and stale UI callbacks must not start a third read", 2, harness.loads.get())
        assertTrue(harness.model.uiState.value.loading)
        assertFalse(harness.model.uiState.value.loadFailed)
        assertTrue(harness.model.uiState.value.entries.isEmpty())
        emptyMessage().assertDoesNotExist()
        retry().assertDoesNotExist()
        capture("flow-retry-pending")
        val retryLoadingVisible = visibleProgress()
        val retryLoadingExplained = visibleLoadingObjectAndAction()

        val actualRecords = records()
        compose.runOnIdle { assertTrue(harness.second.complete(VocabularyLoadResult.Ready(setOf("CET4"), actualRecords))) }
        compose.waitUntil(5_000) { !harness.model.uiState.value.loading && harness.model.uiState.value.entries.size == 1 }
        capture("flow-ready-group")
        assertFalse(harness.model.uiState.value.loadFailed)
        assertEquals("run", harness.model.uiState.value.entries.single().englishForm)
        assertEquals(actualRecords, harness.model.uiState.value.entries.single().senses)
        compose.onNodeWithTag("vocabulary-sense-run-business").assertIsDisplayed()
        emptyMessage().assertDoesNotExist()
        retry().assertDoesNotExist()
        progress().assertDoesNotExist()
        assertNoSideEffects(harness, expectedLoads = 2)

        // Retain all four captured states and the real retry/Ready proof even
        // when the old screen fails to communicate its loading/error states.
        assertTrue(
            "Distinct visible states are required: firstLoading=$firstLoadingVisible " +
                "firstLoadingExplanation=$firstLoadingExplained failureExplanation=$failureExplained " +
                "retryLoading=$retryLoadingVisible retryLoadingExplanation=$retryLoadingExplained",
            firstLoadingVisible && firstLoadingExplained && failureExplained && retryLoadingVisible && retryLoadingExplained,
        )
    }

    @Test
    fun onlyAnActuallyCompletedEmptyReadMayShowTheExistingEmptyMessage() {
        val harness = mount()
        capture("empty-before-completion")
        assertTrue(harness.model.uiState.value.loading)
        emptyMessage().assertDoesNotExist()
        compose.runOnIdle { assertTrue(harness.first.complete(VocabularyLoadResult.Ready(setOf("CET4"), emptyList()))) }
        compose.waitUntil(5_000) { !harness.model.uiState.value.loading }
        capture("ready-empty")
        assertFalse(harness.model.uiState.value.loadFailed)
        assertTrue(harness.model.uiState.value.entries.isEmpty())
        assertEquals("CET4", harness.model.uiState.value.currentScope)
        assertWholeControl(emptyMessage())
        retry().assertDoesNotExist()
        progress().assertDoesNotExist()
        compose.onNodeWithTag("vocabulary-detail-sheet").assertDoesNotExist()
        assertNoSideEffects(harness, expectedLoads = 1)
    }

    private class Harness {
        val first = CompletableDeferred<VocabularyLoadResult>()
        val second = CompletableDeferred<VocabularyLoadResult>()
        val loads = AtomicInteger()
        val creations = AtomicInteger()
        val home = AtomicInteger()
        val playback = AtomicInteger()
        lateinit var model: VocabularyBookViewModel
    }

    private fun mount(): Harness {
        val harness = Harness()
        val repository = object : VocabularyBookRepository {
            override suspend fun loadAllRecords(): VocabularyLoadResult = when (harness.loads.incrementAndGet()) {
                1 -> harness.first.await()
                2 -> harness.second.await()
                else -> error("Only the initial read and one user retry belong to this isolated fixture")
            }
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                check(modelClass == VocabularyBookViewModel::class.java)
                harness.creations.incrementAndGet()
                return VocabularyBookViewModel(repository).also { harness.model = it } as T
            }
        }
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.window.decorView.width == width && compose.activity.window.decorView.height == height }
        compose.setContent { RlTheme { RlPaperBackground {
            VocabularyBookEntry(factory, { harness.home.incrementAndGet() }, {
                harness.playback.incrementAndGet()
                error("No playback belongs to a deferred vocabulary read")
            })
        } } }
        compose.waitUntil(5_000) { harness.loads.get() == 1 }
        compose.waitForIdle()
        assertActualWindow()
        assertEquals(1, harness.creations.get())
        return harness
    }

    private fun assertNoSideEffects(harness: Harness, expectedLoads: Int) {
        compose.runOnIdle {
            assertEquals(expectedLoads, harness.loads.get())
            assertEquals(1, harness.creations.get())
            assertEquals(0, harness.home.get())
            assertEquals(0, harness.playback.get())
            assertNull(harness.model.uiState.value.expandedSenseId)
            assertFalse(harness.model.uiState.value.pronunciationBusy)
            assertFalse(harness.model.uiState.value.pronunciationFailed)
        }
    }

    // Existing general loading semantics, as used by the review/account tests;
    // no future vocabulary tag or prescribed new loading sentence is assumed.
    private val progressMatcher = SemanticsMatcher.expectValue(
        SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
    )
    private fun progress() = compose.onNode(progressMatcher)
    private fun retry() = compose.onNodeWithText(compose.activity.getString(R.string.diary_retry))
    private fun emptyMessage() = compose.onNodeWithText(compose.activity.getString(R.string.vocabulary_book_empty))

    private fun visibleProgress(): Boolean {
        val matches = compose.onAllNodes(progressMatcher).fetchSemanticsNodes()
        if (matches.size != 1) return false
        return isWholeControlVisible(progress())
    }

    private fun visibleLoadingObjectAndAction(): Boolean {
        val matcher = SemanticsMatcher("The current read action and vocabulary object, without prescribing a full sentence") { node ->
            node.config.contains(SemanticsProperties.Text) && node.config[SemanticsProperties.Text].any {
                it.text.contains("读取") && it.text.contains("词汇")
            }
        }
        if (compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size != 1) return false
        val interaction = compose.onNode(matcher, useUnmergedTree = true)
        if (!isWholeControlVisible(interaction)) return false
        val node = interaction.fetchSemanticsNode()
        if (!node.config.contains(SemanticsActions.GetTextLayoutResult)) return false
        val layouts = mutableListOf<TextLayoutResult>()
        var supplied = false
        interaction.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { supplied = it(layouts) }
        if (!supplied || layouts.size != 1) return false
        val layout = layouts.single()
        val text = layout.layoutInput.text.text
        if (layout.lineCount == 0 || layout.multiParagraph.didExceedMaxLines ||
            layout.getLineEnd(layout.lineCount - 1) != text.length ||
            (0 until layout.lineCount).any(layout::isLineEllipsized)) return false
        val coordinates = node.layoutInfo.coordinates
        val origin = coordinates.localToRoot(Offset.Zero)
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(node.boundsInRoot).intersect(canvas)
        return text.indices.filterNot { text[it].isWhitespace() }.all { index ->
            val glyph = layout.getBoundingBox(index)
            fits(Rect(origin.x + glyph.left, origin.y + glyph.top, origin.x + glyph.right, origin.y + glyph.bottom), clip)
        }
    }

    private fun visibleFailureExplanation(): Boolean {
        // Check meaning, not a new exact resource/string. The failure state has
        // no user rows, so a failure/read-unavailable explanation cannot come
        // from an unrelated vocabulary definition or merely the retry label.
        val matcher = SemanticsMatcher("A read-failure explanation rather than only a retry button") { node ->
            node.config.contains(SemanticsProperties.Text) &&
                node.config[SemanticsProperties.Text].any { text ->
                    Regex("失败|无法|未能|未完成|未读取|未加载|不可用").containsMatchIn(text.text)
                } && !node.config.contains(SemanticsActions.OnClick)
        }
        val nodes = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
        return nodes.any { node ->
            val coordinates = node.layoutInfo.coordinates
            val origin = coordinates.localToRoot(Offset.Zero)
            val full = Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
            val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
                .intersect(node.boundsInRoot).intersect(canvas)
            fits(full, clip)
        }
    }

    private fun assertWholeControl(interaction: SemanticsNodeInteraction) {
        interaction.assertIsDisplayed()
        assertTrue("Actual loading/action content must be inside its visible window clip", isWholeControlVisible(interaction))
    }

    private fun isWholeControlVisible(interaction: SemanticsNodeInteraction): Boolean {
        val node = interaction.fetchSemanticsNode()
        val coordinates = node.layoutInfo.coordinates
        val origin = coordinates.localToRoot(Offset.Zero)
        val full = Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(node.boundsInRoot).intersect(canvas)
        return fits(full, clip)
    }

    private fun fits(full: Rect, clip: Rect) = full.width > 1f && full.height > 1f &&
        full.left >= clip.left - 1f && full.top >= clip.top - 1f && full.right <= clip.right + 1f && full.bottom <= clip.bottom + 1f

    private fun physicalClickTwice(interaction: SemanticsNodeInteraction) {
        val node = interaction.fetchSemanticsNode()
        val coordinates = node.layoutInfo.coordinates
        val center = coordinates.localToRoot(Offset(coordinates.size.width / 2f, coordinates.size.height / 2f))
        val rootOrigin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
        compose.onRoot().performTouchInput {
            click(center - rootOrigin)
            advanceEventTime(32L)
            click(center - rootOrigin)
        }
        compose.waitForIdle()
    }

    private fun assertActualWindow() {
        assertEquals(320, compose.activity.resources.configuration.densityDpi)
        assertEquals(2f, compose.activity.resources.displayMetrics.density, .001f)
        assertEquals(if (shortViewport) 2f else 1f, compose.activity.resources.configuration.fontScale, .001f)
        assertEquals(width, compose.activity.window.decorView.width)
        assertEquals(height, compose.activity.window.decorView.height)
        val root = compose.onRoot().fetchSemanticsNode().layoutInfo
        assertEquals(width, root.width)
        assertEquals(height, root.height)
    }

    private fun capture(label: String) {
        require(label in setOf("initial-pending", "flow-first-pending", "flow-failed", "flow-retry-pending", "flow-ready-group", "empty-before-completion", "ready-empty"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val phase = InstrumentationRegistry.getArguments().getString("vocabularyLoadSnapshot") ?: "before"
        require(phase in setOf("before", "after"))
        compose.waitForIdle()
        assertActualWindow()
        val decor = compose.activity.window.decorView
        val drawn = CountDownLatch(1)
        val listener = ViewTreeObserver.OnDrawListener { drawn.countDown() }
        instrumentation.runOnMainSync {
            assertTrue(decor.hasWindowFocus())
            decor.viewTreeObserver.addOnDrawListener(listener)
            decor.postInvalidateOnAnimation()
        }
        try { check(drawn.await(10, TimeUnit.SECONDS)) { "Actual vocabulary loading window did not draw" } }
        finally { instrumentation.runOnMainSync { if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(listener) } }
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(width, image.width)
            assertEquals(height, image.height)
            var ink = 0
            for (y in 0 until image.height step 16) for (x in 0 until image.width step 16) {
                val pixel = image.getPixel(x, y)
                if (Color.red(pixel) < 170 && Color.green(pixel) < 170 && Color.blue(pixel) < 170) ink++
            }
            assertTrue("A blank frame is not a loading-state screenshot", ink > 8)
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa")
            check(directory.isDirectory || directory.mkdirs())
            val output = File(directory, "40-vocabulary-load-$label-$phase${if (shortViewport) "-short" else ""}.png")
            check(output.canonicalFile.parentFile == directory.canonicalFile)
            output.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }

    private fun records() = listOf(
        VocabularySenseRecord("run-business", "run", "经营", KnowledgeStatus.MASTERED, 2, setOf("CET4"), 1,
            phonetic = "/rʌn/", pronunciationText = "run", focusOccurrenceCount = 3, contextOccurrenceCount = 4),
    )

    companion object {
        private val shortViewport = when (InstrumentationRegistry.getArguments().getString("knowledgeLoadViewport")) {
            null, "reference" -> false
            "short" -> true
            else -> error("Unknown knowledge loading viewport")
        }
        private val width = if (shortViewport) 1280 else 1829
        private val height = if (shortViewport) 720 else 860
        private val canvas = Rect(0f, 0f, width.toFloat(), height.toFloat())
    }
}
