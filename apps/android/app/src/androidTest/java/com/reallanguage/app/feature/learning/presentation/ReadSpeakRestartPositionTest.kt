package com.reallanguage.app.feature.learning.presentation

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelStore
import com.reallanguage.app.R
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.ReadSpeakCompletionSignal
import com.reallanguage.app.feature.learning.domain.ReadSpeakFeedbackResult
import com.reallanguage.app.feature.learning.domain.ReadSpeakRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real Screen/VM and pointer input; no microphone, HTTP, SQL, or process-recovery claim. */
class ReadSpeakRestartPositionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var model: ReadSpeakViewModel
    private val actions = mutableListOf<ReadSpeakAction>()
    private val feedback = mutableListOf<Pair<String, ReadSpeakCompletionSignal>>()

    @Before fun focusedLearningWindow() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
    }

    @Test fun firstFocusDragStartsAtTheBeginningWithoutASeparateStartButton() {
        render()
        val initialFirst = raw(firstWord())
        val initialClip = clip(readingRow())
        val initialScroll = scrollPosition()
        assertEquals(0f, initialScroll.first, .5f)
        assertTrue("Long fixture must have genuine horizontal overflow", initialScroll.second > initialClip.width)
        assertTrue("First word must genuinely fit before recording", fits(initialFirst, initialClip))
        assertTrue("Fixture leaves enough physical drag distance", initialFirst.width < initialClip.width * .5f)
        val rootOrigin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
        compose.runOnIdle {
            assertFalse(model.uiState.value.listening)
            assertEquals(0, model.uiState.value.highlightedWordCount)
            assertTrue(actions.none { it == ReadSpeakAction.StartRequested || it is ReadSpeakAction.WordReached })
            assertTrue(feedback.isEmpty())
        }
        val begin = Offset(initialFirst.left + 2f, initialFirst.center.y)
        val end = Offset(initialFirst.right - 2f, initialFirst.center.y)
        var pointerDown = false
        try {
            compose.onRoot().performTouchInput {
                down(begin - rootOrigin)
                repeat(20) { step ->
                    moveTo(begin + (end - begin) * ((step + 1) / 20f) - rootOrigin, 32L)
                }
            }
            pointerDown = true
            compose.runOnIdle {
                assertEquals("The first drag both starts recording and traverses the first word", 1,
                    model.uiState.value.highlightedWordCount)
                assertEquals(listOf(0), actions.filterIsInstance<ReadSpeakAction.WordReached>().map { it.wordIndex })
                assertTrue(model.uiState.value.listening)
                assertEquals(1, actions.count { it == ReadSpeakAction.StartRequested })
                assertFalse(model.uiState.value.completed)
                assertTrue("No feedback request before explicit recorded confirmation", feedback.isEmpty())
            }
        } finally {
            if (pointerDown) compose.onRoot().performTouchInput { advanceEventTime(200L); up() }
        }
        compose.runOnIdle {
            assertEquals(1, model.uiState.value.highlightedWordCount)
            assertTrue(feedback.isEmpty())
        }
        assertEquals(0f, scrollPosition().first, .5f)
    }

    private fun render() {
        val sentence = "Yesterday our team discussed several practical ways to make weekly meetings clearer while giving every colleague enough time for thoughtful questions and useful feedback."
        val chapter = FixedLearningChapter("isolated-restart-position", 1,
            listOf(FixedLearningSentence("focus", sentence, "这是一句隔离的指读练习。", true)))
        val repository = ReadSpeakRepository { id, signal ->
            feedback += id to signal
            ReadSpeakFeedbackResult.Ready("隔离反馈已返回。", contentComplete = true)
        }
        compose.setContent {
            val viewModel = remember { ReadSpeakViewModel(chapter, "focus", repository) }
            val store = remember { ViewModelStore().apply { put("isolated-restart-position", viewModel) } }
            DisposableEffect(store) { onDispose { store.clear() } }
            model = viewModel
            val state by viewModel.uiState.collectAsState()
            RlTheme { RlPaperBackground {
                Box(Modifier.fillMaxSize().testTag("read-restart-viewport")) {
                    ReadSpeakScreen(state) { action -> actions += action; viewModel.onAction(action) }
                }
            } }
        }
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag("read-restart-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("Use the actual focused landscape window", viewport.width > viewport.height)
        assertEquals("Use actual system font1, not a replaced LocalDensity", 1f,
            compose.activity.resources.configuration.fontScale, .001f)
    }

    private fun readingRow() = compose.onNodeWithTag("learning-english-focus")
    private fun firstWord() = compose.onNodeWithText("Yesterday", useUnmergedTree = true)
    private fun scrollPosition(): Pair<Float, Float> {
        val range = readingRow().fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        return compose.runOnIdle { range.value() to range.maxValue() }
    }
    private fun raw(node: SemanticsNodeInteraction): Rect {
        val coordinates = node.fetchSemanticsNode().layoutInfo.coordinates
        return Rect(coordinates.localToRoot(Offset.Zero), Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
    }
    private fun clip(node: SemanticsNodeInteraction): Rect {
        val coordinates = node.fetchSemanticsNode().layoutInfo.coordinates
        return coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(compose.onNodeWithTag("read-restart-viewport").fetchSemanticsNode().boundsInRoot)
    }
    private fun fits(bounds: Rect, viewport: Rect) = viewport.width > 0f && viewport.height > 0f &&
        bounds.left >= viewport.left - 1f && bounds.right <= viewport.right + 1f &&
        bounds.top >= viewport.top - 1f && bounds.bottom <= viewport.bottom + 1f
}
