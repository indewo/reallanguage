package com.reallanguage.app.feature.knowledge.presentation

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.PatternRecord
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Dialog window, system density/font and physical touches; no storage, HTTP or audio. */
class PatternRemovalVisibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val standardPattern = "work up the courage to + 动词"
    private var width = 1717
    private var height = 916
    private val viewportMatcher = hasScrollAction() and hasAnyAncestor(hasTestTag("pattern-removal-sheet"))

    @Test fun referenceRemovalBoundaryAndBothActionsAreReadableWithoutScrolling() {
        val actions = render(standardPattern)
        capture("reference-initial")
        for (value in listOf(standardPattern) + boundaryTexts + listOf("取消", "确认移出")) {
            val node = text(value)
            val layout = layout(node, value)
            repeat(layout.lineCount) { assertTrue("Reference glyphs clipped: $value line $it", visible(node, layout, it)) }
        }
        assertTrue(actions.isEmpty())
    }

    @Test fun shortDoubleFontCanReadLongPatternAndFailureThenPhysicallyRetryOrCancel() {
        val pattern = "The more time someone spends preparing for an unfamiliar situation, " +
            "the more confidently they can + 动词原形"
        val actions = render(pattern, short = true, failed = true)
        capture("short-initial")
        val longLayout = layout(text(pattern), pattern)
        assertTrue("Use genuinely wrapped system-font2 content", longLayout.lineCount > 3)
        for (value in listOf(pattern) + boundaryTexts + listOf("尚未移出，原内容保持不变，请重试。")) {
            val node = text(value)
            val paragraph = layout(node, value)
            repeat(paragraph.lineCount) { reachLine(node, paragraph, it) }
        }
        capture("short-read")
        for ((label, tag) in listOf("确认移出" to "pattern-removal-confirm", "取消" to "pattern-removal-cancel")) {
            val node = text(label)
            val paragraph = layout(node, label)
            repeat(paragraph.lineCount) { reachLine(node, paragraph, it) }
            val action = compose.onNodeWithTag(tag).assertIsEnabled()
            val bounds = action.fetchSemanticsNode().boundsInRoot
            assertTrue("Keep both controls inside the actual dialog", window.contains(bounds.topLeft) && window.contains(bounds.bottomRight - Offset(1f, 1f)))
            assertTrue("Real touch target must remain at least 48dp high", action.fetchSemanticsNode().layoutInfo.height >= 96)
            action.performTouchInput { click() }
            compose.waitForIdle()
        }
        capture("short-actions")
        assertEquals(listOf(PatternRemovalAction.ConfirmRequested, PatternRemovalAction.CancelRequested), actions)
        // Actions are only recorded here. Actual removal/rollback belongs to real SQLite tests.
    }

    @Test fun removingCannotOfferAnEnabledCancelWhileTheOperationIsCommitted() {
        render(standardPattern, removing = true)
        compose.onNodeWithTag("pattern-removal-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("pattern-removal-cancel").assertIsNotEnabled()
    }

    @Test fun shortDoubleFontKeepsTheViewportAndBothChoicesInsideTheExistingPaper() {
        render(standardPattern, short = true, failed = true)
        val paper = compose.onNodeWithTag("pattern-removal-sheet").fetchSemanticsNode().boundsInRoot
        val content = compose.onNode(viewportMatcher).fetchSemanticsNode().boundsInRoot
        // The frozen torn-note raster has a top/tape band and a transparent bottom
        // fringe. Readable paper starts after 12% and ends before its last 10%.
        assertTrue("Scrolling text must not expose the underlying book through the top fringe: paper=$paper viewport=$content",
            content.top >= paper.top + paper.height * .12f)
        for (tag in listOf("pattern-removal-cancel", "pattern-removal-confirm")) {
            val action = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("Action must stay on opaque paper, not only inside the screen: paper=$paper action=$action",
                action.bottom <= paper.bottom - paper.height * .10f)
        }
    }

    private fun render(pattern: String, short: Boolean = false, failed: Boolean = false, removing: Boolean = false): MutableList<PatternRemovalAction> {
        width = if (short) 1280 else 1717
        height = if (short) 720 else 916
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.window.decorView.width == width && compose.activity.window.decorView.height == height }
        assertEquals(320, compose.activity.resources.configuration.densityDpi)
        assertEquals(if (short) 2f else 1f, compose.activity.resources.configuration.fontScale, .001f)
        val actions = mutableListOf<PatternRemovalAction>()
        val record = PatternRecord("isolated-pattern", pattern, "鼓起勇气做某事", KnowledgeStatus.MASTERED,
            KnowledgeStatus.LEARNING, 2, 2)
        compose.setContent { RlTheme {
            PatternBookScreen(PatternBookUiState(listOf(record), record.patternId, loading = false), {})
            PatternRemovalDialog(PatternRemovalUiState(record.patternId, "isolated-confirmation", removing, failed), pattern, actions::add)
        } }
        compose.waitForIdle()
        val dialog = compose.onNode(isDialog()).fetchSemanticsNode()
        assertEquals(width, dialog.layoutInfo.width)
        assertEquals(height, dialog.layoutInfo.height)
        return actions
    }

    private val window get() = Rect(0f, 0f, width.toFloat(), height.toFloat())
    private fun text(value: String) = compose.onNode(hasText(value) and hasAnyAncestor(isDialog()), useUnmergedTree = true)
    private fun layout(node: SemanticsNodeInteraction, value: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        val result = results.single()
        assertEquals(value, result.layoutInput.text.text)
        assertFalse("Never ellipsize the removal boundary", result.multiParagraph.didExceedMaxLines)
        assertEquals(value.length, result.getLineEnd(result.lineCount - 1))
        repeat(result.lineCount) { assertFalse(result.isLineEllipsized(it)) }
        return result
    }

    private fun visible(node: SemanticsNodeInteraction, paragraph: TextLayoutResult, line: Int): Boolean {
        val semantic = node.fetchSemanticsNode()
        val coordinates = semantic.layoutInfo.coordinates
        val origin = coordinates.localToRoot(Offset.Zero)
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(semantic.boundsInRoot).intersect(window)
        val value = paragraph.layoutInput.text.text
        val boxes = (paragraph.getLineStart(line) until paragraph.getLineEnd(line, visibleEnd = true))
            .filter { !value[it].isWhitespace() }.map { paragraph.getBoundingBox(it).translate(origin) }
        return boxes.isNotEmpty() && clip.height > 0 && boxes.all {
            it.left >= clip.left - 1f && it.right <= clip.right + 1f && it.top >= clip.top - 1f && it.bottom <= clip.bottom + 1f
        }
    }

    private fun reachLine(node: SemanticsNodeInteraction, paragraph: TextLayoutResult, line: Int) {
        var gestures = 0
        while (!visible(node, paragraph, line) && gestures < 45) {
            val scroll = compose.onAllNodes(viewportMatcher).fetchSemanticsNodes()
            assertEquals("Clipped removal text needs a bounded production scroll region, not a test wrapper", 1, scroll.size)
            val bounds = scroll.single().boundsInRoot.intersect(window)
            assertTrue("Enough vertical space to physically read a line", bounds.height > 80)
            val semantic = node.fetchSemanticsNode()
            val origin = semantic.layoutInfo.coordinates.localToRoot(Offset.Zero)
            val bottom = origin.y + paragraph.getLineBottom(line)
            val upward = bottom > bounds.bottom
            val range = scroll.single().config[SemanticsProperties.VerticalScrollAxisRange]
            val before = range.value()
            val endpoint = if (upward) range.maxValue() else 0f
            if (kotlin.math.abs(before - endpoint) <= 1f) break
            compose.onNode(viewportMatcher).performTouchInput {
                val start = if (upward) bounds.height - 5f else 5f
                val distance = minOf(bounds.height * .4f, 110f)
                swipe(Offset(bounds.width / 2, start), Offset(bounds.width / 2, start + if (upward) -distance else distance), 450)
            }
            compose.waitForIdle()
            gestures++
        }
        assertTrue("Actual line $line must become readable; gestures=$gestures text=${paragraph.layoutInput.text}", visible(node, paragraph, line))
    }

    private fun capture(label: String) {
        val phase = InstrumentationRegistry.getArguments().getString("patternRemovalCapture") ?: return
        require(phase in setOf("before", "after"))
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(width, bitmap.width); assertEquals(height, bitmap.height)
            val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa")
            check(folder.isDirectory || folder.mkdirs())
            File(folder, "44-pattern-removal-$label-$phase.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private val boundaryTexts = listOf("移出句式本？", "移出后将删除此句式的状态、代表场景、例句和未来任务。",
        "原来已经固定的句子不会被修改。", "将移出以下内容", "会写／会说状态", "连续成功次数", "代表场景与例句", "未来回顾任务")
}
