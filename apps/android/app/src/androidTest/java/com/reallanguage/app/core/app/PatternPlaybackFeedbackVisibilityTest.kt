package com.reallanguage.app.core.app

import android.content.pm.ActivityInfo
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.knowledge.domain.*
import com.reallanguage.app.feature.knowledge.presentation.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Real entry/player completion handling; repository/player fixtures never access user data or AI. */
class PatternPlaybackFeedbackVisibilityTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun aFailedExampleNearTheEndOfTheBookShowsItsFailureAndReachableRetryWithoutSearchingElsewhere() {
        rule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        val firstPlayback = CompletableDeferred<Boolean>()
        val calls = mutableListOf<String>()
        val records = (1..24).map { index ->
            PatternRecord("visibility-$index", "for the first time $index", "第一次经历 $index",
                KnowledgeStatus.MASTERED, KnowledgeStatus.LEARNING, 2, 3,
                examples = listOf(PatternExample("I cooked alone for the first time.", "我第一次独自做饭。", "I cooked alone for the first time.")))
        }
        val model = PatternBookViewModel(object : PatternBookRepository {
            override suspend fun loadAllPatterns() = PatternLoadResult.Ready(records)
        })
        rule.setContent { RlTheme {
            PatternBookEntry(factory(model), { _, _ -> error("No removal during playback feedback verification") }, {}, { text ->
                calls += text
                if (calls.size == 1) firstPlayback.await() else true
            })
        } }
        rule.onNodeWithTag("pattern-row-visibility-24").performScrollTo().performTouchInput { click() }
        rule.onNodeWithTag("pattern-example-visibility-24-0").performScrollTo().performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, calls.size); firstPlayback.complete(false) }
        rule.waitUntil(5_000) { model.uiState.value.pronunciationFailed }
        rule.waitForIdle()
        captureIfRequested()

        // No performScrollTo on feedback: the user has no reason to search at the top of 24 rows.
        wholeVisible("pattern-pronunciation-failed")
        rule.onNodeWithTag("pattern-pronunciation-retry").assertIsDisplayed().performTouchInput { click() }
        rule.runOnIdle {
            assertEquals(listOf("I cooked alone for the first time.", "I cooked alone for the first time."), calls)
            assertEquals("visibility-24", model.uiState.value.expandedPatternId)
            assertEquals(records, model.uiState.value.patterns)
            assertFalse(model.uiState.value.pronunciationBusy)
            assertFalse(model.uiState.value.pronunciationFailed)
        }
    }

    private fun wholeVisible(tag: String) {
        val node = rule.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        val coordinates = node.fetchSemanticsNode().layoutInfo.coordinates
        val origin = coordinates.localToRoot(Offset.Zero)
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
        assertFalse(layout.multiParagraph.didExceedMaxLines)
        assertFalse(layout.didOverflowHeight)
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
        if (layout.didOverflowWidth) {
            // Observed semantic ParagraphLayoutCache: width1383 vs actual816,
            // while the one left-aligned line occupies exactly816. Validate the
            // established cache condition and actual glyph edges, not the empty
            // reconstructed width as if it were visible text overflow.
            assertEquals(1, layout.lineCount)
            assertEquals(coordinates.size.width, layout.size.width)
            assertEquals(coordinates.size.width, kotlin.math.ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt())
            assertEquals(0f, layout.getLineLeft(0), .5f)
        }
        for (line in 0 until layout.lineCount) {
            assertFalse(layout.isLineEllipsized(line))
            assertTrue("Playback failure must be fully visible at the current reading location: $clip",
                origin.y + layout.getLineTop(line) >= clip.top - 1f &&
                    origin.y + layout.getLineBottom(line) <= clip.bottom + 1f &&
                    origin.x + layout.getLineLeft(line) >= clip.left - 1f &&
                    origin.x + layout.getLineRight(line) <= clip.right + 1f)
        }
    }

    private fun captureIfRequested() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val phase = InstrumentationRegistry.getArguments().getString("patternPlaybackFailureCapture") ?: return
        require(phase in setOf("before", "after"))
        val drawn = AtomicBoolean()
        val view = rule.activity.window.decorView
        val listener = ViewTreeObserver.OnDrawListener { drawn.set(true) }
        instrumentation.runOnMainSync { view.viewTreeObserver.addOnDrawListener(listener); view.postInvalidateOnAnimation() }
        try { rule.waitUntil(3_000) { drawn.get() } }
        finally { instrumentation.runOnMainSync { view.viewTreeObserver.removeOnDrawListener(listener) } }
        instrumentation.uiAutomation.waitForIdle(200, 3_000)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa/43-playback-failure-$phase.png")
        try {
            check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
            output.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun factory(model: ViewModel) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = model as T
    }
}
