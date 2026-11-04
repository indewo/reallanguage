package com.reallanguage.app.feature.error.presentation

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
import com.reallanguage.app.feature.error.domain.ConnectionResumePoint
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Actual overlay/window/text/touch; isolated state only, not HTTP, account checks or visual parity.
 * Frozen 49 depicts checking, not idle/failed states, and does not specify their pixel appearance. */
class ConnectionErrorStateVisibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var width = 1831
    private var height = 859
    private val captureId = UUID.randomUUID().toString()

    @Test fun referenceIdleDoesNotPretendThatConnectionAndAccountAreBeingChecked() {
        val actions = render(state())
        capture("reference-idle")
        compose.onNodeWithText(text(R.string.connection_error_checking)).assertDoesNotExist()
        assertProgressCount(0)
        assertWhole(text(R.string.connection_error_title))
        assertButtonWhole("connection-error-return", R.string.connection_error_return)
        assertButtonWhole("connection-error-reconnect", R.string.connection_error_reconnect)
        compose.onNodeWithTag("connection-error-return").assertIsEnabled()
        compose.onNodeWithTag("connection-error-reconnect").assertIsEnabled()
        clickCenter("connection-error-reconnect")
        clickCenter("connection-error-return")
        assertEquals(listOf(ConnectionErrorAction.ReconnectRequested, ConnectionErrorAction.ReturnRequested), actions)
    }

    @Test fun referenceCheckingShowsRealBusyStateDisablesReconnectButKeepsReturn() {
        val actions = render(state().copy(reconnecting = true))
        capture("reference-checking")
        assertProgressCount(1)
        assertWhole(text(R.string.connection_error_checking))
        compose.onNodeWithTag("connection-error-reconnect").assertIsNotEnabled()
        compose.onNodeWithTag("connection-error-return").assertIsEnabled()
        assertButtonWhole("connection-error-return", R.string.connection_error_return)
        assertButtonWhole("connection-error-reconnect", R.string.connection_error_reconnect)
        clickCenter("connection-error-reconnect")
        assertTrue(actions.isEmpty())
        clickCenter("connection-error-return")
        assertEquals(listOf(ConnectionErrorAction.ReturnRequested), actions)
    }

    @Test fun referenceFailureIsReadableAndOffersRetryWithoutAStaleSpinner() {
        val actions = render(state().copy(reconnectFailed = true))
        capture("reference-failed")
        assertProgressCount(0)
        compose.onNodeWithText(text(R.string.connection_error_checking)).assertDoesNotExist()
        assertWhole(text(R.string.connection_error_retry_failed))
        assertButtonWhole("connection-error-return", R.string.connection_error_return)
        assertButtonWhole("connection-error-reconnect", R.string.connection_error_reconnect)
        compose.onNodeWithTag("connection-error-reconnect").assertIsEnabled()
        clickCenter("connection-error-reconnect")
        clickCenter("connection-error-return")
        assertEquals(listOf(ConnectionErrorAction.ReconnectRequested, ConnectionErrorAction.ReturnRequested), actions)
    }

    @Test fun shortSystemDoubleFontCanReadAllCopyAndReachBothWholeFortyEightDpActions() {
        val actions = render(state().copy(reconnectFailed = true), short = true)
        capture("short-failed-initial")
        val copy = listOf(
            R.string.connection_error_title,
            R.string.connection_error_explanation,
            R.string.connection_error_no_offline,
            R.string.connection_error_temporary_input_kept,
            R.string.connection_error_retry_failed,
            R.string.connection_error_restore_note,
        )
        copy.forEach { resource ->
            val value = text(resource)
            val node = compose.onNodeWithText(value, useUnmergedTree = true)
            val layout = layout(node, value)
            repeat(layout.lineCount) { reachLine(node, layout, it) }
        }
        for (resource in listOf(R.string.connection_error_return, R.string.connection_error_reconnect)) {
            val value = text(resource)
            val node = compose.onNodeWithText(value, useUnmergedTree = true)
            val layout = layout(node, value)
            repeat(layout.lineCount) { reachLine(node, layout, it) }
        }
        // Verify both labels at this same final position, not after separate corrective scrolls.
        assertButtonWhole("connection-error-return", R.string.connection_error_return)
        assertButtonWhole("connection-error-reconnect", R.string.connection_error_reconnect)
        capture("short-failed-actions")
        clickFortyEightDpVerticalEdges("connection-error-return")
        clickFortyEightDpVerticalEdges("connection-error-reconnect")
        assertEquals(listOf(
            ConnectionErrorAction.ReturnRequested, ConnectionErrorAction.ReturnRequested,
            ConnectionErrorAction.ReconnectRequested, ConnectionErrorAction.ReconnectRequested,
        ), actions)
        assertProgressCount(0)
    }

    private fun render(state: ConnectionErrorUiState, short: Boolean = false): MutableList<ConnectionErrorAction> {
        width = if (short) 1280 else 1831
        height = if (short) 720 else 859
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.window.decorView.width == width && compose.activity.window.decorView.height == height }
        assertEquals(320, compose.activity.resources.configuration.densityDpi)
        assertEquals(if (short) 2f else 1f, compose.activity.resources.configuration.fontScale, .001f)
        val actions = mutableListOf<ConnectionErrorAction>()
        compose.setContent { RlTheme { RlPaperBackground { ConnectionErrorOverlay(state, actions::add) } } }
        compose.waitForIdle()
        return actions
    }

    private fun state() = ConnectionErrorUiState(ConnectionResumePoint("isolated-explanation", "line-2", "隔离且未提交的输入"))
    private fun text(resource: Int) = compose.activity.getString(resource)
    private fun window() = Rect(0f, 0f, width.toFloat(), height.toFloat())

    private fun assertProgressCount(expected: Int) {
        val nodes = compose.onAllNodes(SemanticsMatcher("actual progress semantics") {
            SemanticsProperties.ProgressBarRangeInfo in it.config
        }, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(expected, nodes.size)
    }

    private fun assertButtonWhole(tag: String, resource: Int) {
        val button = compose.onNodeWithTag(tag).fetchSemanticsNode()
        assertTrue("The actual action must have positive visible bounds: $tag", button.boundsInRoot.intersect(window()).height > 0)
        assertWhole(text(resource))
    }

    private fun assertWhole(value: String) {
        val node = compose.onNodeWithText(value, useUnmergedTree = true)
        val layout = layout(node, value)
        repeat(layout.lineCount) { line -> assertTrue("Clipped text line $line: $value", geometry(node, layout, line).visible) }
    }

    private fun layout(node: SemanticsNodeInteraction, value: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        return results.single().also {
            assertEquals(value, it.layoutInput.text.text)
            assertFalse("Do not truncate actual recovery text", it.multiParagraph.didExceedMaxLines)
            assertEquals(value.length, it.getLineEnd(it.lineCount - 1))
        }
    }

    private data class Glyphs(val boxes: List<Rect>, val clip: Rect) {
        val visible get() = boxes.isNotEmpty() && boxes.all {
            it.left >= clip.left - 1 && it.right <= clip.right + 1 && it.top >= clip.top - 1 && it.bottom <= clip.bottom + 1
        }
    }

    private fun geometry(interaction: SemanticsNodeInteraction, layout: TextLayoutResult, line: Int): Glyphs {
        val node = interaction.fetchSemanticsNode()
        val coordinates = node.layoutInfo.coordinates
        var origin = coordinates.localToRoot(Offset.Zero)
        val actualWidth = coordinates.size.width
        val paragraphWidth = layout.multiParagraph.width
        if (abs(paragraphWidth - actualWidth) > 1f) {
            val offset = (paragraphWidth - actualWidth) / 2f
            val align = layout.layoutInput.style.textAlign
            val left = align == TextAlign.Left || ((align == TextAlign.Start || align == TextAlign.Unspecified) &&
                layout.layoutInput.layoutDirection == LayoutDirection.Ltr)
            assertTrue("Resolve real paragraph before judging clipping: width=$paragraphWidth actual=$actualWidth size=${layout.size} lines=${layout.lineCount} align=$align",
                layout.lineCount == 1 && layout.size.width == actualWidth && paragraphWidth > actualWidth &&
                    ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt() == actualWidth)
            if (!(left && abs(layout.getLineLeft(0)) <= 1f && layout.getLineRight(0) <= actualWidth + 1f)) {
                assertTrue("Only the previously verified centered single-line semantic offset may be normalized",
                    align == TextAlign.Center && abs(layout.getLineLeft(0) - offset) <= 1f &&
                        abs(layout.getLineRight(0) - offset - actualWidth) <= 1f)
                origin -= Offset(offset, 0f)
            }
        }
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, true).intersect(node.boundsInRoot).intersect(window())
        val value = layout.layoutInput.text.text
        val boxes = (layout.getLineStart(line) until layout.getLineEnd(line, visibleEnd = true))
            .filter { !value[it].isWhitespace() }.map { layout.getBoundingBox(it).translate(origin) }
        return Glyphs(boxes, clip).also {
            if (!it.visible) println("Connection glyph diagnostic line=$line origin=$origin clip=$clip actual=${coordinates.size} layout=${layout.size} boxes=$boxes")
        }
    }

    private fun reachLine(node: SemanticsNodeInteraction, layout: TextLayoutResult, line: Int) {
        var gestures = 0
        while (!geometry(node, layout, line).visible && gestures++ < 24) {
            val scroll = generateSequence(node.fetchSemanticsNode().parent) { it.parent }
                .firstOrNull { SemanticsProperties.VerticalScrollAxisRange in it.config }
            assertNotNull("Clipped content requires a real production scroll region", scroll)
            val coordinates = scroll!!.layoutInfo.coordinates
            val viewport = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, true).intersect(scroll.boundsInRoot).intersect(window())
            val glyphs = geometry(node, layout, line).boxes
            assertTrue(glyphs.isNotEmpty())
            val top = glyphs.minOf { it.top }
            val bottom = glyphs.maxOf { it.bottom }
            val slop = ViewConfiguration.get(compose.activity).scaledTouchSlop.toFloat()
            assertTrue("Actual viewport cannot fit one unscaled line: $viewport glyphHeight=${bottom - top}", viewport.height >= bottom - top - 1f)
            assertTrue(viewport.height > slop + 10f)
            val upward = bottom > viewport.bottom
            val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
            if (abs(range.value() - if (upward) range.maxValue() else 0f) < 1f) break
            val outside = if (upward) bottom - viewport.bottom else viewport.top - top
            val distance = (outside.coerceAtLeast(0f) + (viewport.height - (bottom - top)).coerceAtLeast(0f) / 2f + slop)
                .coerceIn(slop + 2f, viewport.height - 8f)
            val from = Offset(viewport.center.x, if (upward) viewport.bottom - 4f else viewport.top + 4f)
            val to = from + Offset(0f, if (upward) -distance else distance)
            assertTrue(viewport.contains(from) && viewport.contains(to))
            val rootOrigin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
            compose.onRoot().performTouchInput {
                val start = from - rootOrigin
                val end = to - rootOrigin
                down(start)
                repeat(20) { index -> moveTo(start + (end - start) * ((index + 1) / 20f), 40L) }
                repeat(6) { advanceEventTime(40L); moveTo(end, 0L) }
                up()
            }
            compose.waitForIdle()
        }
        assertTrue("Actual line $line remains clipped after $gestures real gestures", geometry(node, layout, line).visible)
    }

    private fun clickCenter(tag: String) {
        val point = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center
        clickRoot(point)
    }

    private fun clickFortyEightDpVerticalEdges(tag: String) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val touch = node.touchBoundsInRoot.intersect(window())
        val minimum = 48f * compose.activity.resources.displayMetrics.density
        assertTrue("A visible 48dp touch target is required: $tag $touch", touch.width >= minimum - 1f && touch.height >= minimum - 1f)
        val center = touch.center
        // Real physical hits near opposite edges verify the expanded touch target, not just painted height.
        clickRoot(center + Offset(0f, -minimum / 2f + 2f))
        clickRoot(center + Offset(0f, minimum / 2f - 2f))
    }

    private fun clickRoot(point: Offset) {
        assertTrue(window().contains(point))
        val origin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
        compose.onRoot().performTouchInput { click(point - origin) }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        val phase = InstrumentationRegistry.getArguments().getString("connectionStateCapture") ?: return
        require(phase in setOf("before", "after"))
        compose.waitForIdle()
        val decor = compose.activity.window.decorView
        val drawn = AtomicBoolean()
        val listener = ViewTreeObserver.OnDrawListener { drawn.set(true) }
        compose.activityRule.scenario.onActivity { decor.viewTreeObserver.addOnDrawListener(listener); decor.postInvalidateOnAnimation() }
        try { compose.waitUntil(5_000) { drawn.get() } }
        finally { compose.activityRule.scenario.onActivity { if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(listener) } }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(width, bitmap.width); assertEquals(height, bitmap.height)
            val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "visual-qa")
            check(directory.isDirectory || directory.mkdirs())
            val output = File(directory, "49-connection-state-$name-$phase-$captureId.png")
            check(output.createNewFile())
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            println("Connection state screenshot=${output.absolutePath}")
        } finally { bitmap.recycle() }
    }
}
