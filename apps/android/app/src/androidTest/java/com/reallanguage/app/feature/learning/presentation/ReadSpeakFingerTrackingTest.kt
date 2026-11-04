package com.reallanguage.app.feature.learning.presentation

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
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
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.File

/** Real pointer input -> production Screen/VM. No microphone, HTTP, SQL, or pronunciation evidence. */
class ReadSpeakFingerTrackingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var model: ReadSpeakViewModel
    private val actions = mutableListOf<ReadSpeakAction>()
    private val feedback = mutableListOf<Pair<String, ReadSpeakCompletionSignal>>()
    private var finger: Offset? = null
    private lateinit var nativeDecor: android.view.View

    @Before fun focusedLearningWindow() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
    }

    @Test fun oneUnbrokenFingerPathStartsRecordingAndWaitsForRecordedConfirmation() {
        val words = listOf("We", "speak", "clearly.")
        render(words.joinToString(" "))
        val row = clip(focusRow())
        val first = raw(word(words.first()))
        assertTrue("Short fixture must fit its actual current-focus row", words.all { fits(raw(word(it)), row) })
        beginFinger(Offset(first.left + 2f, first.center.y))
        try {
            words.forEachIndexed { index, text ->
                val bounds = raw(word(text))
                moveFinger(Offset(bounds.right - 2f, bounds.center.y))
                compose.runOnIdle {
                    assertEquals("Moving across a complete word must highlight it before finger up: index=$index",
                        index + 1, model.uiState.value.highlightedWordCount)
                    if (index < words.lastIndex) {
                        assertFalse(model.uiState.value.completed)
                        assertTrue(feedback.isEmpty())
                    }
                }
            }
        } finally { endFinger() }
        assertWaitingForRecordedConfirmation(words.size)
        captureListeningStateIfRequested()
        compose.onNodeWithTag("read_speak_manual_finished").assertIsEnabled().performTouchInput { click(center) }
        assertManuallyCompletedOnce(words.size)
        compose.onNodeWithText(compose.activity.getString(R.string.read_speak_continue)).assertIsEnabled()
    }

    private fun captureListeningStateIfRequested() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val name = InstrumentationRegistry.getArguments().getString("readSpeakAutoRecordingCapture") ?: return
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val output = File(instrumentation.targetContext.cacheDir, name)
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue("Auto-recording visual evidence must be a nonempty PNG", output.length() > 0L)
        } finally { bitmap.recycle() }
    }

    @Test fun onlyDraggingTheFocusSentenceAutomaticallyStartsTheReadingTask() {
        render("We speak clearly.", includeBackground = true)
        val background = clip(compose.onNodeWithTag("learning-english-context"))
        assertTrue("Read-only background must genuinely be on screen", background.width > 120f && background.height > 12f)
        beginFinger(Offset(background.left + 4f, background.center.y))
        try { moveFinger(Offset(background.right - 4f, background.center.y)) } finally { endFinger() }
        compose.runOnIdle {
            assertFalse(model.uiState.value.listening)
            assertEquals(0, model.uiState.value.highlightedWordCount)
            assertTrue(actions.none { it == ReadSpeakAction.StartRequested || it is ReadSpeakAction.WordReached })
            assertTrue(feedback.isEmpty())
        }

        val first = raw(word("We"))
        val last = raw(word("clearly."))
        beginFinger(Offset(first.left + 2f, first.center.y))
        try { moveFinger(Offset(last.right - 2f, last.center.y)) } finally { endFinger() }
        compose.runOnIdle {
            assertTrue(model.uiState.value.listening)
            assertEquals(1, actions.count { it == ReadSpeakAction.StartRequested })
            assertEquals(3, model.uiState.value.highlightedWordCount)
            assertFalse(model.uiState.value.completed)
            assertTrue(feedback.isEmpty())
        }
    }

    @Test fun heldFingerAtTheReadingEdgeRevealsAndReachesTheLongSentenceEndWithoutAnotherGesture() {
        val deadline = SystemClock.elapsedRealtime() + 40_000L
        val sentence = "Yesterday our team discussed several practical ways to make weekly meetings clearer while giving every colleague enough time for thoughtful questions and useful feedback."
        val words = sentence.split(" ")
        assertEquals("Each real word selector must be unambiguous", words.size, words.toSet().size)
        android.util.Log.i("ReadFingerFixture", "render-start")
        render(sentence)
        android.util.Log.i("ReadFingerFixture", "render-ready")
        val initial = scrollPosition()
        val initialClip = clip(focusRow())
        val tail = word(words.last())
        assertEquals(0f, initial.first, .5f)
        assertTrue("Fixture must actually overflow; no artificial pre-scroll", initial.second > initialClip.width)
        assertTrue("Sentence end must start outside the visible reading row", raw(tail).right > initialClip.right + 10f)
        val first = raw(word(words.first()))
        // Cache identities while idle, not stale rectangles. The same attached nodes expose
        // their current layout on the UI thread without an Espresso-idle query during DOWN.
        val rowNode = focusRow().fetchSemanticsNode()
        val tailNode = tail.fetchSemanticsNode()
        val viewportNode = compose.onNodeWithTag("finger-reading-viewport").fetchSemanticsNode()
        val rootNode = compose.onRoot().fetchSemanticsNode()
        val range = rowNode.config[SemanticsProperties.HorizontalScrollAxisRange]
        nativeDecor = compose.activity.window.decorView
        val screenOffset = onUiBefore(deadline) {
            val coordinates = rootNode.layoutInfo.coordinates
            check(compose.activity.window.decorView.hasWindowFocus()) { "The isolated Activity must own native touch input" }
            coordinates.localToScreen(Offset.Zero) - coordinates.localToRoot(Offset.Zero)
        }
        val downTime = SystemClock.uptimeMillis()
        var point = Offset(first.left + 2f, first.center.y)
        var pointerDown = false
        var released = false
        compose.mainClock.autoAdvance = false
        try {
            android.util.Log.i("ReadFingerFixture", "native-gesture-start")
            // A previously interrupted instrumentation process may leave a native pointer
            // held in InputDispatcher. Clear it BEFORE this test's single DOWN stream.
            injectPointer(MotionEvent.ACTION_CANCEL, point + screenOffset, downTime, requireAccepted = false)
            nativeReadingTick(deadline)
            // Actual platform pointer stream, not semantic clicks or a fabricated scroll.
            // Unlike performTouchInput, native injection does not advance MainTestClock:
            // every bounded tick below advances it exactly once and waits for a real draw.
            injectPointer(MotionEvent.ACTION_DOWN, point + screenOffset, downTime)
            pointerDown = true
            val start = point
            val edge = Offset(initialClip.right - 3f, first.center.y)
            repeat(20) { step ->
                point = start + (edge - start) * ((step + 1) / 20f)
                injectPointer(MotionEvent.ACTION_MOVE, point + screenOffset, downTime)
                nativeReadingTick(deadline)
            }
            var frames = 0
            var snapshot = readingGeometry(rowNode, tailNode, viewportNode, deadline)
            android.util.Log.i("ReadFingerFixture", "edge-reached")
            while (!snapshot.reachedEnd && frames < 600 && SystemClock.elapsedRealtime() < deadline - 1_500L) {
                point = if (fits(snapshot.tail, snapshot.viewport)) Offset(snapshot.tail.right - 2f, snapshot.tail.center.y)
                    else Offset(snapshot.viewport.right - 3f, first.center.y)
                injectPointer(MotionEvent.ACTION_MOVE, point + screenOffset, downTime)
                nativeReadingTick(deadline)
                snapshot = readingGeometry(rowNode, tailNode, viewportNode, deadline)
                frames += 1
            }
            val after = onUiBefore(SystemClock.elapsedRealtime() + 1_000L) { range.value() to range.maxValue() }
            android.util.Log.i("ReadFingerFixture", "native-gesture-checked")
            assertTrue("A held finger must advance the actual horizontal viewport: before=$initial after=$after frames=$frames",
                after.first > initial.first)
            assertTrue("The complete final word must be revealed by the same live pointer: tail=${snapshot.tail} clip=${snapshot.viewport} scroll=$after",
                fits(snapshot.tail, snapshot.viewport))
            assertTrue("Finger tracking must reach the end within the bounded gesture without submitting", snapshot.reachedEnd)
            injectPointer(MotionEvent.ACTION_UP, point + screenOffset, downTime)
            released = true
            nativeReadingTick(deadline)
            onUiBefore(deadline) {
                assertEquals(words.size, model.uiState.value.highlightedWordCount)
                assertFalse(model.uiState.value.completed)
                assertTrue(model.uiState.value.listening)
                assertTrue(feedback.isEmpty())
                assertEquals(1, actions.count { it == ReadSpeakAction.StartRequested })
                assertTrue(actions.any { it is ReadSpeakAction.WordReached })
                assertTrue(actions.none { it == ReadSpeakAction.ManualFinished || it == ReadSpeakAction.RecognitionCompleted })
            }
        } finally {
            try {
                if (pointerDown && !released) injectPointer(MotionEvent.ACTION_CANCEL, point + screenOffset, downTime)
                // Give UP/CANCEL a bounded native frame before restoring automatic time.
                nativeReadingTick(SystemClock.elapsedRealtime() + 2_000L)
            } finally { compose.mainClock.autoAdvance = true }
        }
    }

    @Test fun tappingTheClippedTailAfterEdgeScrollingCannotCompleteTheSentence() {
        val deadline = SystemClock.elapsedRealtime() + 40_000L
        val sentence = "Yesterday our team discussed several practical ways to make weekly meetings clearer while giving every colleague enough time for thoughtful questions and better communication."
        val words = sentence.split(" ")
        render(sentence)
        val initial = scrollPosition()
        val initialClip = clip(focusRow())
        assertEquals(0f, initial.first, .5f)
        assertTrue("The real sentence must overflow its current row", initial.second > initialClip.width)
        assertTrue("Tail starts outside the viewport", raw(word(words.last())).left > initialClip.right)
        val first = raw(word(words.first()))
        val rowNode = focusRow().fetchSemanticsNode()
        val tailNode = word(words.last()).fetchSemanticsNode()
        val viewportNode = compose.onNodeWithTag("finger-reading-viewport").fetchSemanticsNode()
        val rootNode = compose.onRoot().fetchSemanticsNode()
        val range = rowNode.config[SemanticsProperties.HorizontalScrollAxisRange]
        nativeDecor = compose.activity.window.decorView
        val screenOffset = onUiBefore(deadline) {
            check(nativeDecor.hasWindowFocus()) { "The isolated Activity must own native input" }
            val coordinates = rootNode.layoutInfo.coordinates
            coordinates.localToScreen(Offset.Zero) - coordinates.localToRoot(Offset.Zero)
        }
        var point = Offset(first.left + 2f, first.center.y)
        var downTime = SystemClock.uptimeMillis()
        var pointerDown = false
        fun partialTail(snapshot: ReadingGeometry): Boolean {
            val visible = snapshot.tail.intersect(snapshot.viewport)
            return visible.width >= snapshot.tail.width * .20f &&
                snapshot.tail.right > snapshot.viewport.right + 1f &&
                snapshot.tail.top >= snapshot.viewport.top && snapshot.tail.bottom <= snapshot.viewport.bottom
        }
        compose.mainClock.autoAdvance = false
        try {
            // Only this pre-DOWN reset may ignore rejection. There is exactly one accepted
            // continuous pointer for scrolling; the later tap is a separate deliberate action.
            injectPointer(MotionEvent.ACTION_CANCEL, point + screenOffset, downTime, requireAccepted = false)
            nativeReadingTick(deadline, 16L)
            injectPointer(MotionEvent.ACTION_DOWN, point + screenOffset, downTime)
            pointerDown = true
            val start = point
            val edge = Offset(initialClip.right - 3f, first.center.y)
            repeat(20) { step ->
                point = start + (edge - start) * ((step + 1) / 20f)
                injectPointer(MotionEvent.ACTION_MOVE, point + screenOffset, downTime)
                nativeReadingTick(deadline, 16L)
            }
            var snapshot = readingGeometry(rowNode, tailNode, viewportNode, deadline)
            var frames = 0
            while (!partialTail(snapshot) && !snapshot.reachedEnd && frames < 1_600 &&
                SystemClock.elapsedRealtime() < deadline - 3_000L
            ) {
                point = Offset(snapshot.viewport.right - 3f, first.center.y)
                injectPointer(MotionEvent.ACTION_MOVE, point + screenOffset, downTime)
                nativeReadingTick(deadline, 16L)
                snapshot = readingGeometry(rowNode, tailNode, viewportNode, deadline)
                frames += 1
            }
            assertTrue("Fixture must stop at a genuinely partial tail, not a fully revealed word: $snapshot frames=$frames",
                partialTail(snapshot))
            assertFalse("Edge tracking must not reach the full tail while it is still clipped", snapshot.reachedEnd)
            injectPointer(MotionEvent.ACTION_UP, point + screenOffset, downTime)
            pointerDown = false
            nativeReadingTick(deadline, 16L)
            snapshot = readingGeometry(rowNode, tailNode, viewportNode, deadline)
            assertTrue("After lifting, the visible tail must still be only partial: $snapshot", partialTail(snapshot))
            val before = onUiBefore(deadline) {
                assertTrue("The actual row was advanced by the held pointer", range.value() > initial.first)
                assertEquals(words.lastIndex, model.uiState.value.highlightedWordCount)
                assertTrue(model.uiState.value.listening)
                assertFalse(model.uiState.value.completed)
                assertTrue(feedback.isEmpty())
                range.value() to actions.filterIsInstance<ReadSpeakAction.WordReached>().map { it.wordIndex }
            }
            nativeReadingTick(deadline, 16L)
            onUiBefore(deadline) { assertEquals("UP stops edge scrolling before the tap", before.first, range.value(), .5f) }
            snapshot = readingGeometry(rowNode, tailNode, viewportNode, deadline)
            val visibleTail = snapshot.tail.intersect(snapshot.viewport)
            assertTrue("The tap targets an actual visible part of the clipped tail", partialTail(snapshot))
            point = visibleTail.center
            assertTrue(snapshot.viewport.contains(point) && snapshot.tail.contains(point))
            downTime = SystemClock.uptimeMillis()
            injectPointer(MotionEvent.ACTION_DOWN, point + screenOffset, downTime)
            pointerDown = true
            nativeReadingTick(deadline, 16L)
            injectPointer(MotionEvent.ACTION_UP, point + screenOffset, downTime)
            pointerDown = false
            repeat(2) { nativeReadingTick(deadline, 16L) }
            onUiBefore(deadline) {
                assertEquals("A clipped-tail tap must use the same completion guard as dragging", before.second,
                    actions.filterIsInstance<ReadSpeakAction.WordReached>().map { it.wordIndex })
                assertEquals(words.lastIndex, model.uiState.value.highlightedWordCount)
                assertTrue(model.uiState.value.listening)
                assertFalse("A partially visible tail is not the complete sentence end", model.uiState.value.completed)
                assertTrue("The partial-tail click must not request feedback", feedback.isEmpty())
            }
        } finally {
            try {
                if (pointerDown) injectPointer(MotionEvent.ACTION_CANCEL, point + screenOffset, downTime)
                nativeReadingTick(SystemClock.elapsedRealtime() + 2_000L, 16L)
            } finally { compose.mainClock.autoAdvance = true }
        }
    }

    @Test fun draggingAcrossTheFullyVisibleTailStillWaitsForRecordedConfirmation() {
        val words = listOf("We", "speak", "clearly.")
        render(words.joinToString(" "))
        val first = raw(word(words.first()))
        val tail = raw(word(words.last()))
        val viewport = clip(focusRow())
        assertTrue("Control requires the complete tail word inside the real row", fits(tail, viewport))
        val origin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
        compose.onRoot().performTouchInput {
            down(Offset(first.left + 2f, first.center.y) - origin)
            moveTo(Offset(tail.right - 2f, tail.center.y) - origin, 600L)
            up()
        }
        assertWaitingForRecordedConfirmation(words.size)
        compose.onNodeWithTag("read_speak_manual_finished").assertIsEnabled().performClick()
        assertManuallyCompletedOnce(words.size)
        compose.onNodeWithText(compose.activity.getString(R.string.read_speak_continue)).assertIsEnabled()
    }

    private fun render(textEn: String, includeBackground: Boolean = false) {
        val sentences = buildList {
            add(FixedLearningSentence("focus", textEn, "这是一句隔离的指读练习。", true))
            if (includeBackground) add(FixedLearningSentence("context", "Background stays unchanged.", "背景保持只读。", false))
        }
        val chapter = FixedLearningChapter("isolated-finger-reading", 1, sentences)
        val repository = ReadSpeakRepository { id, signal ->
            feedback += id to signal
            ReadSpeakFeedbackResult.Ready("隔离反馈已返回。", contentComplete = true)
        }
        compose.setContent {
            val viewModel = remember { ReadSpeakViewModel(chapter, "focus", repository) }
            val store = remember { ViewModelStore().apply { put("isolated-finger-reading", viewModel) } }
            DisposableEffect(store) { onDispose { store.clear() } }
            model = viewModel
            val state by viewModel.uiState.collectAsState()
            RlTheme { RlPaperBackground {
                Box(Modifier.fillMaxSize().testTag("finger-reading-viewport")) {
                    ReadSpeakScreen(state) { action -> actions += action; viewModel.onAction(action) }
                }
            } }
        }
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag("finger-reading-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("Use a real landscape window, not a taller fake host", viewport.width > viewport.height)
        assertEquals("Finger fixture is the font1 baseline; short/font2 is owned by the layout tests", 1f,
            compose.activity.resources.configuration.fontScale, .001f)
    }

    private fun assertWaitingForRecordedConfirmation(wordCount: Int) = compose.runOnIdle {
        assertEquals(wordCount, model.uiState.value.highlightedWordCount)
        assertFalse(model.uiState.value.completed)
        assertTrue(model.uiState.value.listening)
        assertTrue(feedback.isEmpty())
        assertEquals(1, actions.count { it == ReadSpeakAction.StartRequested })
        assertTrue(actions.any { it is ReadSpeakAction.WordReached })
        assertTrue(actions.none { it == ReadSpeakAction.ManualFinished || it == ReadSpeakAction.RecognitionCompleted })
    }

    private fun assertManuallyCompletedOnce(wordCount: Int) = compose.runOnIdle {
        assertEquals(wordCount, model.uiState.value.highlightedWordCount)
        assertTrue(model.uiState.value.completed)
        assertFalse(model.uiState.value.listening)
        assertEquals(listOf("focus" to ReadSpeakCompletionSignal.MANUAL_CONFIRMATION), feedback)
        assertEquals(1, actions.count { it == ReadSpeakAction.ManualFinished })
    }

    private fun focusRow() = compose.onNodeWithTag("learning-english-focus")
    private fun word(text: String) = compose.onNodeWithText(text, useUnmergedTree = true)
    private fun raw(node: SemanticsNodeInteraction): Rect {
        val coordinates = node.fetchSemanticsNode().layoutInfo.coordinates
        val origin = coordinates.localToRoot(Offset.Zero)
        return Rect(origin, androidx.compose.ui.geometry.Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
    }
    private fun clip(node: SemanticsNodeInteraction): Rect {
        val coordinates = node.fetchSemanticsNode().layoutInfo.coordinates
        return coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(compose.onNodeWithTag("finger-reading-viewport").fetchSemanticsNode().boundsInRoot)
    }
    private fun fits(bounds: Rect, viewport: Rect) = viewport.width > 0f && viewport.height > 0f &&
        bounds.left >= viewport.left - 1f && bounds.right <= viewport.right + 1f &&
        bounds.top >= viewport.top - 1f && bounds.bottom <= viewport.bottom + 1f
    private fun scrollPosition(): Pair<Float, Float> {
        val range = focusRow().fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        return compose.runOnIdle { range.value() to range.maxValue() }
    }

    private fun beginFinger(point: Offset) {
        check(finger == null)
        val origin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
        compose.onRoot().performTouchInput { down(point - origin) }
        finger = point
    }
    private fun moveFinger(point: Offset) {
        val from = checkNotNull(finger)
        val origin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
        compose.onRoot().performTouchInput {
            repeat(20) { step -> moveTo(from + (point - from) * ((step + 1) / 20f) - origin, 32L) }
        }
        finger = point
        compose.waitForIdle()
    }
    private fun endFinger() {
        if (finger == null) return
        compose.onRoot().performTouchInput { advanceEventTime(200L); up() }
        finger = null
        compose.waitForIdle()
    }

    private data class ReadingGeometry(val viewport: Rect, val tail: Rect, val reachedEnd: Boolean)

    private fun readingGeometry(row: SemanticsNode, tail: SemanticsNode, viewport: SemanticsNode,
        deadline: Long): ReadingGeometry = onUiBefore(deadline) {
        val rowCoordinates = row.layoutInfo.coordinates
        val tailCoordinates = tail.layoutInfo.coordinates
        val viewportCoordinates = viewport.layoutInfo.coordinates
        check(rowCoordinates.isAttached && tailCoordinates.isAttached && viewportCoordinates.isAttached) {
            "Fixture layout identity detached during the native gesture; not a product clipping verdict"
        }
        val screen = Rect(viewportCoordinates.localToRoot(Offset.Zero),
            androidx.compose.ui.geometry.Size(viewportCoordinates.size.width.toFloat(), viewportCoordinates.size.height.toFloat()))
        ReadingGeometry(
            rowCoordinates.findRootCoordinates().localBoundingBoxOf(rowCoordinates, clipBounds = true).intersect(screen),
            Rect(tailCoordinates.localToRoot(Offset.Zero),
                androidx.compose.ui.geometry.Size(tailCoordinates.size.width.toFloat(), tailCoordinates.size.height.toFloat())),
            model.uiState.value.highlightedWordCount == model.uiState.value.chapter.sentences
                .first { it.sentenceId == model.uiState.value.currentFocusSentenceId }.textEn.split(Regex("\\s+")).size,
        )
    }

    private fun injectPointer(action: Int, screenPoint: Offset, downTime: Long, requireAccepted: Boolean = true) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, screenPoint.x, screenPoint.y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            // Asynchronous system injection: never wait for Espresso or global UI idleness
            // while the production edge-scroll frame loop is deliberately active.
            val accepted = InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, false)
            check(accepted || !requireAccepted) {
                "Native pointer injection was rejected: action=$action"
            }
        } finally { event.recycle() }
    }

    private fun nativeReadingTick(deadline: Long, advanceMillis: Long = 64L) {
        val drawn = CountDownLatch(1)
        val decor = nativeDecor
        val listener = ViewTreeObserver.OnDrawListener { drawn.countDown() }
        compose.mainClock.advanceTimeBy(advanceMillis)
        onUiBefore(deadline) {
            decor.viewTreeObserver.addOnDrawListener(listener)
            decor.postInvalidateOnAnimation()
        }
        try { awaitBefore(drawn, deadline, "native reading draw") }
        finally {
            onUiBefore(SystemClock.elapsedRealtime() + 1_000L) {
                if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(listener)
            }
        }
    }

    private fun <T> onUiBefore(deadline: Long, block: () -> T): T {
        val finished = CountDownLatch(1)
        val result = AtomicReference<Result<T>>()
        Handler(Looper.getMainLooper()).post {
            result.set(runCatching {
                check(SystemClock.elapsedRealtime() < deadline) { "Native reading UI deadline elapsed" }
                block()
            })
            finished.countDown()
        }
        awaitBefore(finished, deadline, "native reading UI operation")
        return result.get().getOrThrow()
    }

    private fun awaitBefore(latch: CountDownLatch, deadline: Long, phase: String) {
        val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtMost(1_000L)
        check(remaining > 0L && latch.await(remaining, TimeUnit.MILLISECONDS)) {
            "Bounded fixture wait failed: $phase; not an accepted finger-tracking result"
        }
    }
}
