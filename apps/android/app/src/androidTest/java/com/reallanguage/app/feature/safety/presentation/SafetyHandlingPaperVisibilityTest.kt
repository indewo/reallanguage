package com.reallanguage.app.feature.safety.presentation

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.SafetyCaseDetails
import com.reallanguage.app.feature.safety.domain.SafetyEdit
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Isolated real Screen/glyph/touch evidence, no safety service, account, SQL or AI.
 * Current capture baseline 1831x859/d320/font1; frozen original12 is 1829x860.
 * No outer test scroll, density override, modified artwork, or screenshot editing.
 */
class SafetyHandlingPaperVisibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun referenceChangeHeadingAndBothRowsStayInsideActualLightPaperNotTransparentOrBackingArea() {
        val state = fixture()
        val actions = render(state)
        capture("changes-initial")
        val paper = compose.onNodeWithTag("safety-handling-changes").fetchSemanticsNode()
        val coordinates = paper.layoutInfo.coordinates
        val origin = coordinates.localToRoot(Offset.Zero)
        // Production's existing Image fills this exact Box with ContentScale.FillBounds.
        // Alpha and pale paper pixels are read from the immutable drawable, not the
        // rendered text or a fabricated solid rectangle over its transparent corners.
        val options = BitmapFactory.Options().apply { inScaled = false }
        val bitmap = requireNotNull(BitmapFactory.decodeResource(compose.activity.resources,
            R.drawable.rl_diary_processing_analysis_note, options))
        try {
            assertEquals(1055, bitmap.width); assertEquals(1491, bitmap.height)
            val values = listOf("变更说明") + state.details.edits.flatMap { listOf(it.originalText, it.replacementText) }
            values.forEach { value ->
                val node = compose.onNode(hasText(value) and hasAnyAncestor(hasTestTag("safety-handling-changes")), useUnmergedTree = true)
                val text = paragraph(node, value)
                repeat(text.lineCount) { line ->
                    val actual = geometry(node, text, line)
                    assertTrue("All actual change glyphs must first fit the screen: $actual", actual.visible)
                    val bad = actual.glyphs.flatMap { box -> listOf(
                        Offset(box.left + 1f, box.top + 1f), Offset(box.right - 1f, box.top + 1f),
                        Offset(box.left + 1f, box.bottom - 1f), Offset(box.right - 1f, box.bottom - 1f), box.center,
                    ) }.firstOrNull { point ->
                        val x = ((point.x - origin.x) / coordinates.size.width * bitmap.width).toInt()
                        val y = ((point.y - origin.y) / coordinates.size.height * bitmap.height).toInt()
                        if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) true else {
                            val pixel = bitmap.getPixel(x, y)
                            // Excludes transparent background and the dark olive backing;
                            // accepts the actual warm light sheet, without pixel-color matching.
                            Color.alpha(pixel) < 245 || Color.red(pixel) < 210 || Color.green(pixel) < 200 || Color.blue(pixel) < 185
                        }
                    }
                    assertNull("Change text is outside the drawable's actual light paper: value=$value line=$line point=$bad " +
                        "imageOrigin=$origin imageSize=${coordinates.size} glyphs=${actual.glyphs}", bad)
                }
            }
        } finally { bitmap.recycle() }
        assertTrue("A read-only inspection must not automatically accept or submit", actions.isEmpty())
        verifyActions(actions)
    }

    @Test fun longOriginalAndDeidentifiedContentCanBeTouchReadToTheirLastLinesBeforeChoosingAnAction() {
        val state = fixture(long = true)
        val actions = render(state)
        capture("long-initial")
        val actionBounds = compose.onNodeWithTag("safety-handling-actions").fetchSemanticsNode().boundsInRoot
        var gestures = 0
        for ((tag, value) in listOf(
            "safety-handling-original" to state.details.originalSource.text,
            "safety-handling-deidentified" to state.details.deidentifiedSource.text,
        )) {
            val node = compose.onNode(hasText(value) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            val text = paragraph(node, value)
            assertTrue("The fixture really requires reading more than the fixed paper height", text.lineCount > 8)
            repeat(text.lineCount) { line -> gestures += reach(node, text, line) }
            assertTrue("Last line must be genuinely visible, not merely present in semantics", geometry(node, text, text.lineCount - 1).visible)
            capture(if (tag.endsWith("original")) "long-original-tail" else "long-deidentified-tail")
        }
        assertTrue("Long paragraphs were read through real physical scrolling", gestures > 0)
        assertEquals("Reading the two paper areas retains the existing action bar", actionBounds,
            compose.onNodeWithTag("safety-handling-actions").fetchSemanticsNode().boundsInRoot)
        assertTrue("Scrolling must not accept, return or send for manual review", actions.isEmpty())
        verifyActions(actions)
    }

    private fun verifyActions(actions: MutableList<SafetyHandlingAction>) {
        listOf(
            "返回修改" to SafetyHandlingAction.ReturnToModifyRequested,
            "提交人工复核" to SafetyHandlingAction.SubmitManualReviewRequested,
            "使用脱敏版本" to SafetyHandlingAction.UseDeidentifiedRequested,
        ).forEachIndexed { index, (value, action) ->
            val node = compose.onNodeWithText(value, useUnmergedTree = true)
            val text = paragraph(node, value)
            repeat(text.lineCount) { reach(node, text, it) }
            val button = compose.onNodeWithText(value).assertIsEnabled()
            button.performTouchInput { click() }
            assertEquals(index + 1, actions.size)
            assertEquals(action, actions.last())
        }
    }

    private fun render(state: SafetyHandlingUiState): MutableList<SafetyHandlingAction> {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.window.decorView.width == 1831 && compose.activity.window.decorView.height == 859 }
        assertEquals(320, compose.activity.resources.configuration.densityDpi)
        assertEquals(1f, compose.activity.resources.configuration.fontScale, .001f)
        val actions = mutableListOf<SafetyHandlingAction>()
        compose.setContent { RlTheme { SafetyHandlingScreen(state, actions::add) } }
        compose.waitForIdle()
        return actions
    }

    private fun fixture(long: Boolean = false): SafetyHandlingUiState {
        val context = if (long) "我们逐项核对任务进度，讨论需要补充的说明，并记录下一次沟通的安排。".repeat(12) +
            "最后一行仍然属于本次完整内容，需要能够清楚读完。" else ""
        val source = ConfirmedSource("今天我和王经理在上海办公室讨论了项目。$context", SourceType.DIARY, SourceContentShape.ARTICLE)
        return SafetyHandlingUiState(SafetyCaseDetails(
            safetyCaseId = "render-only-safety-paper",
            originalSource = source,
            userReasonZh = "原文包含可能识别个人身份的信息，暂时不能直接进入学习。",
            deidentifiedSource = source.copy(text = "今天我和一位同事在办公室讨论了项目。$context"),
            edits = listOf(SafetyEdit("王经理", "一位同事", "已隐藏"), SafetyEdit("上海办公室", "办公室", "已隐藏")),
        ))
    }

    private fun paragraph(node: SemanticsNodeInteraction, value: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        return layouts.single().also {
            assertEquals(value, it.layoutInput.text.text)
            assertEquals(1f, it.layoutInput.density.fontScale, .001f)
            assertFalse("No original content may be discarded to make the paper fit", it.multiParagraph.didExceedMaxLines)
            assertEquals(value.length, it.getLineEnd(it.lineCount - 1))
            repeat(it.lineCount) { line -> assertFalse(it.isLineEllipsized(line)) }
        }
    }

    private data class Geometry(val glyphs: List<Rect>, val clip: Rect, val diagnostic: String) {
        val visible get() = glyphs.isNotEmpty() && clip.width > 0 && clip.height > 0 && glyphs.all {
            it.left >= clip.left - 1 && it.right <= clip.right + 1 && it.top >= clip.top - 1 && it.bottom <= clip.bottom + 1
        }
    }

    private fun geometry(node: SemanticsNodeInteraction, paragraph: TextLayoutResult, line: Int): Geometry {
        val semantic = node.fetchSemanticsNode()
        val coordinates = semantic.layoutInfo.coordinates
        var origin = coordinates.localToRoot(Offset.Zero)
        val width = coordinates.size.width
        val semanticWidth = paragraph.multiParagraph.width
        if (abs(semanticWidth - width) > 1f) {
            assertTrue("Resolve genuine paragraph coordinates before glyph validation: text=${paragraph.layoutInput.text}, size=${paragraph.size}, semanticWidth=$semanticWidth actualWidth=$width",
                paragraph.lineCount == 1 && paragraph.size.width == width && semanticWidth > width &&
                    ceil(paragraph.multiParagraph.intrinsics.maxIntrinsicWidth).toInt() == width)
            val left = paragraph.getLineLeft(0)
            if (abs(left) > 1f) {
                val offset = (semanticWidth - width) / 2f
                assertTrue("Only the established centered single-line semantic correction is accepted", paragraph.layoutInput.style.textAlign == TextAlign.Center &&
                    abs(left - offset) <= 1f && abs(paragraph.getLineRight(0) - offset - width) <= 1f)
                origin -= Offset(offset, 0f)
            }
        }
        val value = paragraph.layoutInput.text.text
        val glyphs = (paragraph.getLineStart(line) until paragraph.getLineEnd(line, visibleEnd = true))
            .filter { !value[it].isWhitespace() }.map { paragraph.getBoundingBox(it).translate(origin) }
        return Geometry(glyphs, clip(semantic), "line=$line paragraph=${paragraph.size} actual=${coordinates.size} origin=$origin")
    }

    private fun clip(node: SemanticsNode): Rect {
        val coordinates = node.layoutInfo.coordinates
        return coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, true)
            .intersect(node.boundsInRoot).intersect(Rect(0f, 0f, 1831f, 859f))
    }

    private fun reach(node: SemanticsNodeInteraction, text: TextLayoutResult, line: Int): Int {
        repeat(24) { count ->
            val current = geometry(node, text, line)
            if (current.visible) return count
            val scroll = generateSequence(node.fetchSemanticsNode().parent) { it.parent }
                .firstOrNull { SemanticsProperties.VerticalScrollAxisRange in it.config }
            assertNotNull("Clipped source text has no production scroll: $current", scroll)
            val viewport = clip(scroll!!)
            assertTrue("The actual scroll viewport fits one complete glyph line: $current, viewport=$viewport",
                viewport.height >= current.glyphs.maxOf { it.height } - 1)
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val delta = (current.glyphs.first().center.y - viewport.center.y)
                .coerceIn(-viewport.height * .35f, viewport.height * .35f)
            val start = viewport.center
            val end = start - Offset(0f, if (abs(delta) < 40f) if (delta >= 0f) 40f else -40f else delta)
            compose.onRoot().performTouchInput {
                down(start - root.topLeft)
                repeat(12) { step -> moveTo(start + (end - start) * ((step + 1) / 12f) - root.topLeft, 50) }
                advanceEventTime(250)
                up()
            }
            compose.waitForIdle()
        }
        val result = geometry(node, text, line)
        assertTrue("Complete line is not reachable after bounded real drags: $result", result.visible)
        return 24
    }

    private fun capture(label: String) {
        val phase = InstrumentationRegistry.getArguments().getString("safetyPaperCapture") ?: return
        require(phase in setOf("before", "after"))
        compose.waitForIdle()
        val decor = compose.activity.window.decorView
        val latch = CountDownLatch(1)
        val listener = ViewTreeObserver.OnDrawListener { latch.countDown() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { decor.viewTreeObserver.addOnDrawListener(listener); decor.invalidate() }
        try { assertTrue(latch.await(3, TimeUnit.SECONDS)) }
        finally { instrumentation.runOnMainSync { if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(listener) } }
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(1831, image.width); assertEquals(859, image.height)
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "12-safety-paper-$label-$phase.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }
}
