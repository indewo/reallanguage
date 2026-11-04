package com.reallanguage.app.feature.learning.presentation

import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewTreeObserver
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
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.ReadSpeakCompletionSignal
import com.reallanguage.app.feature.learning.domain.ReadSpeakFeedbackResult
import com.reallanguage.app.feature.learning.domain.ReadSpeakRepository
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Actual focused-window pointer -> Screen/VM only; no microphone, API, SQL, or audio evidence. */
class ReadSpeakPointerBoundaryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var model: ReadSpeakViewModel
    private val actions = mutableListOf<ReadSpeakAction>()
    private val feedback = mutableListOf<ReadSpeakCompletionSignal>()
    private lateinit var nativeDecor: android.view.View

    @Test
    fun capturedFingerOutsideFocusClipCannotAdvanceScrollOrCompleteTheSentence() {
        val deadline = SystemClock.elapsedRealtime() + 40_000L
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        val sentence = "We carefully discuss practical ideas during weekly meetings and share useful feedback."
        val chapter = FixedLearningChapter("pointer-${UUID.randomUUID()}", 1,
            listOf(FixedLearningSentence("focus", sentence, "这是隔离指读边界测试。", true)))
        val repository = ReadSpeakRepository { _, signal ->
            feedback += signal
            ReadSpeakFeedbackResult.Ready("隔离反馈。", contentComplete = true)
        }
        compose.setContent {
            val viewModel = remember { ReadSpeakViewModel(chapter, "focus", repository) }
            val store = remember { ViewModelStore().apply { put("pointer-boundary", viewModel) } }
            DisposableEffect(store) { onDispose { store.clear() } }
            model = viewModel
            val state by viewModel.uiState.collectAsState()
            RlTheme { RlPaperBackground {
                Box(Modifier.fillMaxSize().testTag("pointer-boundary-viewport")) {
                    ReadSpeakScreen(state) { action -> actions += action; viewModel.onAction(action) }
                }
            } }
        }
        compose.waitForIdle()

        // Cache attached identities before DOWN; no semantic-idle calls during the live pointer.
        val row = compose.onNodeWithTag("learning-english-focus").fetchSemanticsNode()
        val viewport = compose.onNodeWithTag("pointer-boundary-viewport").fetchSemanticsNode()
        val root = compose.onRoot().fetchSemanticsNode()
        val first = compose.onNodeWithText("We", useUnmergedTree = true).fetchSemanticsNode()
        val tail = compose.onNodeWithText("feedback.", useUnmergedTree = true).fetchSemanticsNode()
        val range = row.config[SemanticsProperties.HorizontalScrollAxisRange]
        nativeDecor = compose.activity.window.decorView
        val geometry = onUiBefore(deadline) {
            check(compose.activity.window.decorView.hasWindowFocus()) { "Isolated Activity must own native input" }
            val view = bounds(viewport)
            val coordinates = row.layoutInfo.coordinates
            val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true).intersect(view)
            val rootCoordinates = root.layoutInfo.coordinates
            Geometry(clip, view, bounds(first), bounds(tail),
                rootCoordinates.localToScreen(Offset.Zero) - rootCoordinates.localToRoot(Offset.Zero))
        }
        assertTrue("Actual landscape viewport required", geometry.viewport.width > geometry.viewport.height)
        assertTrue("First word must genuinely fit the FOCUS clip", fits(geometry.first, geometry.clip))
        assertTrue("Fixture must have real horizontal overflow", onUiBefore(deadline) { range.maxValue() > 0f })
        assertTrue("Tail starts clipped; no synthetic pre-scroll", geometry.tail.right > geometry.clip.right)
        val outsideY = geometry.clip.bottom + 24f
        // Keep the escape gesture at the visible reading edge. A larger finger-reading font
        // intentionally places the unscrolled tail well beyond the physical viewport.
        val beyondTail = Offset(geometry.clip.right - 4f, outsideY)
        assertTrue("Out-of-row movement must stay physically on screen", geometry.viewport.contains(beyondTail))
        assertFalse("Outside target is not part of the FOCUS clip", geometry.clip.contains(beyondTail))

        val downTime = SystemClock.uptimeMillis()
        var point = Offset(geometry.first.left + 2f, geometry.first.center.y)
        var pointerDown = false
        compose.mainClock.autoAdvance = false
        try {
            // Only clear an earlier interrupted test's native stream, before our DOWN.
            inject(MotionEvent.ACTION_CANCEL, point + geometry.screenOffset, downTime, requireAccepted = false)
            tick(deadline)
            inject(MotionEvent.ACTION_DOWN, point + geometry.screenOffset, downTime)
            pointerDown = true
            val start = point
            val firstEnd = Offset(geometry.first.right - 2f, geometry.first.center.y)
            repeat(8) { step ->
                point = start + (firstEnd - start) * ((step + 1) / 8f)
                inject(MotionEvent.ACTION_MOVE, point + geometry.screenOffset, downTime)
                tick(deadline)
            }
            val before = onUiBefore(deadline) {
                assertTrue("The first in-row drag must start recording", model.uiState.value.listening)
                assertEquals(1, actions.count { it == ReadSpeakAction.StartRequested })
                assertEquals("Precondition: real in-row drag captured and highlighted one whole word", 1, model.uiState.value.highlightedWordCount)
                assertFalse(model.uiState.value.completed)
                assertTrue(feedback.isEmpty())
                actions.filterIsInstance<ReadSpeakAction.WordReached>().map { it.wordIndex } to range.value()
            }
            assertEquals(listOf(0), before.first)

            // Leave vertically at the first word's x BEFORE moving toward the tail. Thus no
            // intermediate sample can legitimately reach the sentence end while still in-row.
            point = Offset(firstEnd.x, outsideY)
            inject(MotionEvent.ACTION_MOVE, point + geometry.screenOffset, downTime)
            tick(deadline)
            val outsideStart = point
            repeat(8) { step ->
                point = outsideStart + (beyondTail - outsideStart) * ((step + 1) / 8f)
                inject(MotionEvent.ACTION_MOVE, point + geometry.screenOffset, downTime)
                tick(deadline)
            }
            repeat(4) { tick(deadline) }
            fun assertUnchanged() = onUiBefore(deadline) {
                assertEquals("A captured pointer outside the real row must not emit further words", before.first,
                    actions.filterIsInstance<ReadSpeakAction.WordReached>().map { it.wordIndex })
                assertEquals("Out-of-row edge hold cannot scroll the reading row", before.second, range.value(), .5f)
                assertEquals(1, model.uiState.value.highlightedWordCount)
                assertFalse("Leaving the row is not TOUCH_REACHED_END", model.uiState.value.completed)
                assertTrue("No feedback may be requested by out-of-row motion", feedback.isEmpty())
            }
            assertUnchanged()
            inject(MotionEvent.ACTION_UP, point + geometry.screenOffset, downTime)
            pointerDown = false
            tick(deadline)
            assertUnchanged()
        } finally {
            try {
                if (pointerDown) inject(MotionEvent.ACTION_CANCEL, point + geometry.screenOffset, downTime)
                tick(SystemClock.elapsedRealtime() + 2_000L)
            } finally { compose.mainClock.autoAdvance = true }
        }
    }

    private data class Geometry(val clip: Rect, val viewport: Rect, val first: Rect, val tail: Rect, val screenOffset: Offset)
    private fun bounds(node: SemanticsNode): Rect {
        val coordinates = node.layoutInfo.coordinates
        check(coordinates.isAttached) { "Fixture layout detached" }
        return Rect(coordinates.localToRoot(Offset.Zero), Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
    }
    private fun fits(a: Rect, b: Rect) = a.left >= b.left && a.right <= b.right && a.top >= b.top && a.bottom <= b.bottom
    private fun inject(action: Int, point: Offset, downTime: Long, requireAccepted: Boolean = true) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { val accepted = InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, false)
            check(accepted || !requireAccepted) {
            "Native input rejected: action=$action"
        } } finally { event.recycle() }
    }
    private fun tick(deadline: Long) {
        val drawn = CountDownLatch(1)
        val decor = nativeDecor
        val listener = ViewTreeObserver.OnDrawListener { drawn.countDown() }
        compose.mainClock.advanceTimeBy(64L)
        onUiBefore(deadline) {
            decor.viewTreeObserver.addOnDrawListener(listener)
            decor.postInvalidateOnAnimation()
        }
        try { awaitBefore(drawn, deadline) }
        finally { onUiBefore(SystemClock.elapsedRealtime() + 1_000L) {
            if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(listener)
        } }
    }
    private fun <T> onUiBefore(deadline: Long, block: () -> T): T {
        val done = CountDownLatch(1)
        val result = AtomicReference<Result<T>>()
        Handler(Looper.getMainLooper()).post {
            result.set(runCatching { check(SystemClock.elapsedRealtime() < deadline); block() })
            done.countDown()
        }
        awaitBefore(done, deadline)
        return result.get().getOrThrow()
    }
    private fun awaitBefore(latch: CountDownLatch, deadline: Long) {
        val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtMost(1_000L)
        check(remaining > 0L && latch.await(remaining, TimeUnit.MILLISECONDS)) { "Bounded native fixture wait failed, not a product verdict" }
    }
}
