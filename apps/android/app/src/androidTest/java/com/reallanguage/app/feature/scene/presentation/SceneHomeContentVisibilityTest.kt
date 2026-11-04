package com.reallanguage.app.feature.scene.presentation

import android.content.pm.ActivityInfo
import android.os.ParcelFileDescriptor
import android.view.ViewTreeObserver
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.scene.domain.PublicSceneTemplates
import com.reallanguage.app.feature.scene.domain.RecentSceneRecommendation
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Screen/window/font and physical scrolling/selection. Isolated display state, no HTTP/AI/SQL.
 * Original 67 and baseline are 1831x859/d320/font1. Short is real 1280x720/d320/system-font2.
 * There is no test-added scroll or density wrapper and no promise of full visual equivalence.
 */
class SceneHomeContentVisibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var width = 1831
    private var height = 859

    @Test fun referenceHeaderAndTemplateRowsHaveCompleteGlyphsAndSelectExactTemplate() = verifyTemplates(false)
    @Test fun shortDoubleFontHeaderAndTemplateRowsRemainReadableAndSelectable() = verifyTemplates(true)
    @Test fun referenceDirectEntryOpensDedicatedDescriptionPage() = verifyDirectEntry(false)
    @Test fun shortDoubleFontDirectEntryRemainsReadableAndActionable() = verifyDirectEntry(true)

    @Test fun loadingOrEmptyRecommendationsNeverFlashAFalseEmptyRecommendation() {
        configureReferenceWindow()
        var state by mutableStateOf(SceneHomeUiState(loadingRecommendations = true, recommendations = emptyList()))
        compose.setContent {
            RlTheme {
                SceneHomeScreen(state, {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("scene-home-recommendations").assertDoesNotExist()
        compose.onNodeWithTag("scene-home-recommendation-empty").assertDoesNotExist()
        compose.onNodeWithTag("scene-home-templates").assertIsDisplayed()

        compose.runOnIdle { state = state.copy(loadingRecommendations = false) }
        compose.waitForIdle()
        compose.onNodeWithTag("scene-home-recommendations").assertDoesNotExist()
        compose.onNodeWithTag("scene-home-recommendation-empty").assertDoesNotExist()
        compose.onNodeWithTag("scene-home-templates").assertIsDisplayed()
    }

    @Test fun wholeDirectDescriptionPaperIsARealNavigationTarget() {
        val actions = render(short = false, description = "")
        compose.onNodeWithTag("scene-home-direct-panel").assertHasClickAction().performClick()
        assertEquals(listOf(SceneHomeAction.DirectDescriptionRequested), actions)
    }

    @Test fun recentRecommendationsShowStartPromptAndOpenDirectlyWithoutSelectionState() {
        val actions = render(short = false)
        compose.onNodeWithTag("scene-home-recommendation-prompt").assertIsDisplayed()
        compose.onAllNodesWithText("选择一个开始").assertCountEquals(2)
        compose.onNodeWithTag("scene-home-recommendation-0").assertHasClickAction().performClick()
        compose.waitForIdle()
        assertEquals(listOf(SceneHomeAction.RecommendationSelected("recent-app")), actions)
        compose.onNodeWithTag("scene-home-recommendation-selected").assertDoesNotExist()
    }

    private fun verifyTemplates(short: Boolean) {
        val actions = render(short)
        capture("${if (short) "short" else "reference"}-templates-initial")
        readText("AI 设定场景")
        readText("练习你真正会遇到的表达")
        // Keep the actual product templates, not shorter substitutes copied from the illustration.
        PublicSceneTemplates.forEach { template ->
            readText(template.titleZh)
            // The approved ticket design shows the category name and icon only. The
            // detailed scope remains product data for the next step, not clipped card copy.
            val node = compose.onNodeWithText(template.titleZh, useUnmergedTree = true)
            val button = generateSequence(node.fetchSemanticsNode()) { it.parent }
                .first { SemanticsActions.OnClick in it.config }
            assertTrue("Keep a real 48dp template target: ${template.templateId}, size=${button.layoutInfo.coordinates.size}",
                button.layoutInfo.width >= 96 && button.layoutInfo.height >= 96)
            val bounds = clip(button)
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("A visible physical template target is required: $bounds", bounds.width > 0 && bounds.height >= 48f)
            compose.onRoot().performTouchInput { click(bounds.center - root.topLeft) }
            compose.waitForIdle()
            assertEquals(PublicSceneTemplates.take(actions.size).map { SceneHomeAction.TemplateSelected(it.templateId) }, actions)
        }
        assertEquals(PublicSceneTemplates.map { SceneHomeAction.TemplateSelected(it.templateId) }, actions)
        capture("${if (short) "short" else "reference"}-templates-read")
    }

    private fun verifyDirectEntry(short: Boolean) {
        val actions = render(short, "")
        capture("${if (short) "short" else "reference"}-direct-entry")
        compose.onNodeWithText("直接描述你的场景").assertIsDisplayed()
        compose.onNodeWithTag("scene-home-direct-entry", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("选择内容类型").assertIsDisplayed()
        compose.onNodeWithTag("scene-home-direct-panel").assertHasClickAction().performClick()
        compose.waitForIdle()
        assertEquals(listOf(SceneHomeAction.DirectDescriptionRequested), actions)
    }

    private fun render(short: Boolean, description: String = "向同事解释项目延期"): MutableList<SceneHomeAction> {
        width = if (short) 1280 else 1831
        height = if (short) 720 else 859
        configureDevice(width, height, if (short) 2f else 1f)
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.window.decorView.width == width && compose.activity.window.decorView.height == height }
        assertEquals(320, compose.activity.resources.configuration.densityDpi)
        assertEquals(if (short) 2f else 1f, compose.activity.resources.configuration.fontScale, .001f)
        val actions = mutableListOf<SceneHomeAction>()
        compose.setContent {
            val state = SceneHomeUiState(
                loadingRecommendations = false,
                recommendations = listOf(
                    RecentSceneRecommendation("recent-app", "英语 App 宣传", "近期内容", listOf("isolated-source")),
                    RecentSceneRecommendation("recent-change", "成长变化", "近期内容", listOf("isolated-source-2")),
                ),
                directDescription = description,
            )
            RlTheme { SceneHomeScreen(state, actions::add) }
        }
        compose.waitForIdle()
        return actions
    }

    private fun configureReferenceWindow() {
        width = 1831
        height = 859
        configureDevice(width, height, 1f)
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) {
            compose.activity.window.decorView.width == width && compose.activity.window.decorView.height == height
        }
    }

    private fun configureDevice(width: Int, height: Int, fontScale: Float) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf(
            "wm size ${width}x${height}",
            "wm density 320",
            "settings put system font_scale $fontScale",
        ).forEach { command ->
            val result = automation.executeShellCommand(command)
            ParcelFileDescriptor.AutoCloseInputStream(result).use { it.readBytes() }
        }
        compose.activityRule.scenario.recreate()
    }

    private fun readText(value: String, allowHorizontalEllipsis: Boolean = false) {
        val node = compose.onNodeWithText(value, useUnmergedTree = true)
        val paragraph = paragraph(node, value)
        if (!allowHorizontalEllipsis) {
            assertFalse("Full title/action text must not be ellipsized: $value", paragraph.multiParagraph.didExceedMaxLines)
            assertEquals(value.length, paragraph.getLineEnd(paragraph.lineCount - 1))
        }
        repeat(paragraph.lineCount) { line -> reach(node) { textGeometry(node, paragraph, line, allowHorizontalEllipsis) } }
    }

    private fun readInput(value: String) {
        val input = compose.onNodeWithTag("scene-home-direct")
        val paragraph = paragraph(input, value)
        assertEquals("Short fixture input must fit a single line", 1, paragraph.lineCount)
        reach(input) {
            val merged = input.fetchSemanticsNode()
            val field = compose.onNode(SemanticsMatcher("actual field") { it.id == merged.id }, useUnmergedTree = true).fetchSemanticsNode()
            fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = node.children.asSequence().flatMap { sequenceOf(it) + descendants(it) }
            val leaves = descendants(field).filter {
                it.children.isEmpty() && it.layoutInfo.width == paragraph.size.width && SemanticsProperties.Text !in it.config
            }.toList()
            assertEquals("Resolve the actual editable paragraph, not the decorated field: paragraph=${paragraph.size}; nodes=" +
                descendants(field).joinToString { "${it.id}:${it.layoutInfo.coordinates.size}:${it.config.map { entry -> entry.key.name }}" }, 1, leaves.size)
            val drawing = leaves.single()
            val origin = drawing.layoutInfo.coordinates.localToRoot(Offset.Zero)
            val bounds = clip(drawing).intersect(clip(field))
            val glyphs = value.indices.filter { !value[it].isWhitespace() }.map { paragraph.getBoundingBox(it).translate(origin) }
            Geometry(glyphs, bounds, "input paragraph=${paragraph.size}, drawing=${drawing.layoutInfo.coordinates.size}, origin=$origin")
        }
    }

    private fun paragraph(node: SemanticsNodeInteraction, value: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        return results.single().also { assertEquals(value, it.layoutInput.text.text) }
    }

    private data class Geometry(val glyphs: List<Rect>, val clip: Rect, val diagnostic: String) {
        val visible get() = glyphs.isNotEmpty() && clip.width > 0 && clip.height > 0 && glyphs.all {
            it.left >= clip.left - 1 && it.right <= clip.right + 1 && it.top >= clip.top - 1 && it.bottom <= clip.bottom + 1
        }
    }

    private fun textGeometry(node: SemanticsNodeInteraction, paragraph: TextLayoutResult, line: Int, ellipsisAllowed: Boolean): Geometry {
        val semantic = node.fetchSemanticsNode()
        val coordinates = semantic.layoutInfo.coordinates
        var origin = coordinates.localToRoot(Offset.Zero)
        val actualWidth = coordinates.size.width
        val paragraphWidth = paragraph.multiParagraph.width
        if (abs(paragraphWidth - actualWidth) > 1f) {
            val offset = (paragraphWidth - actualWidth) / 2f
            val align = paragraph.layoutInput.style.textAlign
            val left = align == TextAlign.Left || ((align == TextAlign.Start || align == TextAlign.Unspecified) && paragraph.layoutInput.layoutDirection == LayoutDirection.Ltr)
            assertTrue("Resolve actual paragraph width; text=${paragraph.layoutInput.text}, paragraph=$paragraphWidth, actual=$actualWidth",
                paragraph.lineCount == 1 && paragraph.size.width == actualWidth && paragraphWidth > actualWidth &&
                    ceil(paragraph.multiParagraph.intrinsics.maxIntrinsicWidth).toInt() == actualWidth)
            if (!(left && abs(paragraph.getLineLeft(0)) <= 1f && paragraph.getLineRight(0) <= actualWidth + 1f)) {
                assertTrue("Only a verified centered semantics offset can be normalized", align == TextAlign.Center &&
                    abs(paragraph.getLineLeft(0) - offset) <= 1f && abs(paragraph.getLineRight(0) - offset - actualWidth) <= 1f)
                origin -= Offset(offset, 0f)
            }
        }
        val value = paragraph.layoutInput.text.text
        val bounds = clip(semantic)
        var glyphs = (paragraph.getLineStart(line) until paragraph.getLineEnd(line, visibleEnd = true))
            .filter { !value[it].isWhitespace() }.map { paragraph.getBoundingBox(it).translate(origin) }
        if (ellipsisAllowed && paragraph.isLineEllipsized(line)) {
            // The existing horizontal preview remains a preview. Its first ink
            // still must have its full vertical extent, not the current cut half-line.
            glyphs = glyphs.take(1)
        }
        return Geometry(glyphs, bounds, "text=$value line=$line paragraph=${paragraph.size} origin=$origin")
    }

    private fun clip(node: SemanticsNode): Rect {
        val coordinates = node.layoutInfo.coordinates
        return coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, true)
            .intersect(node.boundsInRoot).intersect(Rect(0f, 0f, width.toFloat(), height.toFloat()))
    }

    private fun reach(node: SemanticsNodeInteraction, geometry: () -> Geometry) {
        repeat(24) {
            val actual = geometry()
            if (actual.visible) return
            val scroll = generateSequence(node.fetchSemanticsNode().parent) { it.parent }
                .firstOrNull { SemanticsProperties.VerticalScrollAxisRange in it.config }
            assertNotNull("Clipped content has no production scroll: $actual", scroll)
            val viewport = clip(scroll!!)
            assertTrue("Production viewport fits a complete line: $actual; viewport=$viewport",
                viewport.height >= actual.glyphs.maxOf { it.height } - 1)
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val target = actual.glyphs.first()
            val distance = (target.center.y - viewport.center.y).coerceIn(-viewport.height * .35f, viewport.height * .35f)
            val start = Offset(viewport.center.x, viewport.center.y)
            val end = start - Offset(0f, if (abs(distance) < 40f) if (distance >= 0f) 40f else -40f else distance)
            compose.onRoot().performTouchInput {
                down(start - root.topLeft)
                repeat(12) { step -> moveTo(start + (end - start) * ((step + 1) / 12f) - root.topLeft, 50) }
                advanceEventTime(250)
                up()
            }
            compose.waitForIdle()
        }
        val result = geometry()
        assertTrue("Glyphs remain clipped after bounded physical scrolling: $result", result.visible)
    }

    private fun capture(label: String) {
        val phase = InstrumentationRegistry.getArguments().getString("sceneHomeContentCapture") ?: return
        require(phase in setOf("before", "after"))
        compose.waitForIdle()
        val decor = compose.activity.window.decorView
        val latch = CountDownLatch(1)
        val listener = ViewTreeObserver.OnDrawListener { latch.countDown() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { decor.viewTreeObserver.addOnDrawListener(listener); decor.invalidate() }
        try { assertTrue("Actual window drew the fixture", latch.await(3, TimeUnit.SECONDS)) }
        finally { instrumentation.runOnMainSync { if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(listener) } }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(width, bitmap.width); assertEquals(height, bitmap.height)
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa")
            check(directory.isDirectory || directory.mkdirs())
            val output = File(directory, "67-scene-home-$label-$phase.png")
            output.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            val copy = instrumentation.uiAutomation.executeShellCommand(
                "cp ${output.absolutePath} /sdcard/Download/${output.name}",
            )
            ParcelFileDescriptor.AutoCloseInputStream(copy).use { it.readBytes() }
        } finally { bitmap.recycle() }
    }
}
