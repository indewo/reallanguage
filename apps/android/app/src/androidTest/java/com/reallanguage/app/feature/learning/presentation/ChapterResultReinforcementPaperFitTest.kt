package com.reallanguage.app.feature.learning.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewTreeObserver
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.ChapterArchiveSummary
import com.reallanguage.app.feature.learning.domain.ChapterReinforcementItem
import com.reallanguage.app.feature.learning.domain.ChapterResultCategory
import com.reallanguage.app.feature.learning.domain.ChapterResultSummaryItem
import kotlin.math.abs
import kotlin.math.ceil
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real Screen plus unchanged artwork; neutral display fixtures, no SQL, account, HTTP or AI. */
class ChapterResultReinforcementPaperFitTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val captureRun = UUID.randomUUID().toString()

    @Before fun useTheProductionWindowAndOriginalArtwork() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeResource(compose.activity.resources, R.drawable.rl_torn_note, options)
        assertEquals(1700, options.outWidth)
        assertEquals(630, options.outHeight)
    }

    @Test fun bothPatternAbilitiesCanBeReadInsideTheActualPaperRatherThanItsTornEdge() {
        val actions = mutableListOf<ChapterResultAction>()
        render("表达感受", actions)
        val footer = footerBounds()
        val last = itemText("PATTERN_WRITING", "句式会写")
        val layout = measuredText(last, "句式会写", 13f)
        reachLastLineByTouch(last, layout)
        assertEquals("Local reading must not move the fixed footer", footer, footerBounds())
        assertEquals(emptyList<ChapterResultAction>(), actions)
        compose.onNodeWithText("返回首页").performClick()
        assertEquals(listOf(ChapterResultAction.ReturnHomeRequested), actions)
    }

    @Test fun longExplanationAndItsFinalAbilityLineRemainWholeAndTouchReachableOnThePaper() {
        val explanation = "这条句式用于描述人在某个具体时刻的感受，后面需要一个恰当的形容词；原来的语境和说话对象不能省略。".repeat(4) +
            "最后一行仍是本目标的完整解释，不应掉到纸外。"
        val actions = mutableListOf<ChapterResultAction>()
        render(explanation, actions)
        val footer = footerBounds()
        val paragraph = itemText("PATTERN_WRITING", explanation)
        val layout = measuredText(paragraph, explanation, 15f)
        assertTrue("This fixture must genuinely need local scrolling", layout.lineCount > 6)
        assertTrue("A real touch gesture must reach the last line of the second long item",
            reachLastLineByTouch(paragraph, layout) > 0)
        val label = itemText("PATTERN_WRITING", "句式会写")
        reachLastLineByTouch(label, measuredText(label, "句式会写", 13f))
        assertEquals("Reading all remaining content must preserve the footer location", footer, footerBounds())
        assertEquals("Reading text must not dispatch a learning action", emptyList<ChapterResultAction>(), actions)
    }

    @Test fun initialHeadingAndFirstItemAreWhollyOnThePaperBelowItsRealTopEdge() {
        val actions = mutableListOf<ChapterResultAction>()
        render("表达感受", actions)
        val footer = footerBounds()
        assertEquals("Initial rendering must really be at the top, not a pre-scrolled fixture", 0f, scrollPosition(), .01f)
        assertWholeTextOnPaper(itemText("PATTERN_SPEAKING", "feel + adjective"), "feel + adjective", 17f)
        assertWholeTextOnPaper(itemText("PATTERN_SPEAKING", "表达感受"), "表达感受", 15f)
        assertWholeTextOnPaper(itemText("PATTERN_SPEAKING", "句式会说"), "句式会说", 13f)
        assertWholeTextOnPaper(compose.onNodeWithText("需要继续巩固"), "需要继续巩固", 16f)
        assertEquals("Checking the paper's top must not move its footer", footer, footerBounds())
        assertTrue(actions.isEmpty())
    }

    @Test fun afterReadingTheLastItemTouchCanReturnTheHeadingAndFirstItemToTheSafePaperTop() {
        val explanation = "这条句式解释保留完整语境与说话对象，读完后仍应能够回到同一便签的开头。".repeat(5)
        val actions = mutableListOf<ChapterResultAction>()
        render(explanation, actions)
        val footer = footerBounds()
        val last = itemText("PATTERN_WRITING", "句式会写")
        assertTrue("The bounded real gesture must first reach a genuinely scrolled last item",
            reachLastLineByTouch(last, measuredText(last, "句式会写", 13f)) > 0)
        assertTrue(scrollPosition() > 0f)
        var gestures = 0
        while (scrollPosition() > 0f && gestures < 24) {
            val oldPosition = scrollPosition()
            scrollPanel().assertIsDisplayed().performTouchInput {
                swipe(Offset(width * .5f, height * .25f), Offset(width * .5f, height * .75f), 250)
            }
            compose.waitForIdle()
            gestures++
            if (scrollPosition() == oldPosition) break
        }
        assertTrue("Returning to the heading must use an actual touch gesture", gestures > 0)
        assertEquals("The original heading must be reachable at the actual start of the scroll", 0f, scrollPosition(), .01f)
        assertEquals("Scrolling in both directions must not move the footer", footer, footerBounds())
        assertTrue(actions.isEmpty())
        assertWholeTextOnPaper(itemText("PATTERN_SPEAKING", "feel + adjective"), "feel + adjective", 17f)
        assertWholeTextOnPaper(compose.onNodeWithText("需要继续巩固"), "需要继续巩固", 16f)
        compose.onNodeWithText("返回首页").performClick()
        assertEquals(listOf(ChapterResultAction.ReturnHomeRequested), actions)
    }

    @Test fun shortScreenAtTwoHundredPercentKeepsTheFinalAbilityAndFooterFullyReadable() {
        val actions = mutableListOf<ChapterResultAction>()
        render("表达感受", actions, widthDp = 640f, heightDp = 360f, fontScale = 2f)
        captureShortIfRequested("initial")
        // Measure the actual Text child, not the merged 220dp button semantics.
        val footer = compose.onNodeWithText("返回首页", useUnmergedTree = true).assertIsDisplayed()
        val footerBefore = footerBounds()
        val footerLayout = measuredText(footer, "返回首页", 16f)
        val footerCoordinates = footer.fetchSemanticsNode().layoutInfo.coordinates
        val footerOrigin = realParagraphOrigin(footerLayout, footerCoordinates.localToRoot(Offset.Zero), footerCoordinates.size.width)
        val footerClip = footerCoordinates.findRootCoordinates().localBoundingBoxOf(footerCoordinates, clipBounds = true)
            .intersect(compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot)
        assertEquals(2f, footerLayout.layoutInput.density.fontScale, .001f)
        assertTrue("The full footer label must remain visible at the actual 200% font size",
            "返回首页".indices.map { footerLayout.getBoundingBox(it).translate(footerOrigin) }.all { fits(it, footerClip) })
        val last = itemText("PATTERN_WRITING", "句式会写")
        reachLastLineByTouch(last, measuredText(last, "句式会写", 13f))
        assertEquals("Only the inner paper may scroll on the short screen", footerBefore, footerBounds())
        assertTrue(actions.isEmpty())
        compose.onNodeWithText("返回首页").performClick()
        assertEquals(listOf(ChapterResultAction.ReturnHomeRequested), actions)
    }

    @Test fun shortLargeTextCanTouchReadBothHeadingsAndTheLongExplanationWithoutMovingItsFooter() {
        val actions = mutableListOf<ChapterResultAction>()
        val explanation = "本句描述具体时刻的感受，形容词应符合原来的语境与说话对象。" +
            "这是同一个目标的完整中文说明，最后一行也要读全。"
        render(explanation, actions, widthDp = 640f, heightDp = 360f, fontScale = 2f)
        val footer = footerBounds()
        val headingScroll = compose.onNodeWithTag("chapter-result-heading-scroll")
        val title = compose.onNodeWithText("第 1 章 · 学习结果", useUnmergedTree = true)
        val archive = compose.onNodeWithText("本章已完成并归档", useUnmergedTree = true)
        assertWholeTextInViewport(title, "第 1 章 · 学习结果", 26f)
        val archiveLayout = measuredText(archive, "本章已完成并归档", 26f)
        assertEquals(2f, archiveLayout.layoutInput.density.fontScale, .001f)
        var gestures = 0
        while (!wholeTextInViewport(archive, archiveLayout) && gestures < 16) {
            headingScroll.performTouchInput {
                swipe(Offset(width * .5f, height * .8f), Offset(width * .5f, height * .2f), 250)
            }
            compose.waitForIdle()
            gestures++
        }
        assertTrue("The short header must be read by a real touch, not silently hidden", gestures > 0)
        assertWholeTextInViewport(archive, "本章已完成并归档", 26f)
        var returnGestures = 0
        while (headingScroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f &&
            returnGestures < 16) {
            headingScroll.performTouchInput {
                swipe(Offset(width * .5f, height * .2f), Offset(width * .5f, height * .8f), 250)
            }
            compose.waitForIdle()
            returnGestures++
        }
        assertTrue(returnGestures > 0)
        assertEquals(0f, headingScroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .01f)
        assertWholeTextInViewport(title, "第 1 章 · 学习结果", 26f)
        val paragraph = itemText("PATTERN_WRITING", explanation)
        val paragraphLayout = measuredText(paragraph, explanation, 15f)
        assertTrue("Keep a genuinely long paragraph at the original 200% size", paragraphLayout.lineCount > 6)
        assertEquals(2f, paragraphLayout.layoutInput.density.fontScale, .001f)
        assertTrue(reachLastLineByTouch(paragraph, paragraphLayout, maxGestures = 96, capture = false) > 0)
        val label = itemText("PATTERN_WRITING", "句式会写")
        reachLastLineByTouch(label, measuredText(label, "句式会写", 13f), capture = false)
        assertEquals("Header and paper scroll independently of the footer", footer, footerBounds())
        assertTrue(actions.isEmpty())
        compose.onNodeWithText("返回首页").performClick()
        assertEquals(listOf(ChapterResultAction.ReturnHomeRequested), actions)
    }

    @Test fun shortLargeTextWithANextChapterKeepsBothOriginalFooterActionsWholeAndTouchable() {
        val actions = mutableListOf<ChapterResultAction>()
        render("表达感受", actions, widthDp = 640f, heightDp = 360f, fontScale = 2f, hasNextChapter = true)
        val labels = listOf("稍后学习", "进入下一章")
        val before = labels.map { compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        labels.forEach { label ->
            val text = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            val layout = measuredText(text, label, 16f)
            assertEquals(2f, layout.layoutInput.density.fontScale, .001f)
            assertWholeTextInViewport(text, label, 16f)
        }
        assertTrue("The two real action targets must not overlap", before[0].right <= before[1].left + 1f)
        val last = itemText("PATTERN_WRITING", "句式会写")
        reachLastLineByTouch(last, measuredText(last, "句式会写", 13f), capture = false)
        assertEquals(before, labels.map { compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot })
        assertTrue(actions.isEmpty())
        compose.onNodeWithText("稍后学习").performTouchInput { click(center) }
        compose.onNodeWithText("进入下一章").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(listOf(ChapterResultAction.LearnLaterRequested, ChapterResultAction.EnterNextChapterRequested), actions)
    }

    private fun render(
        explanation: String,
        actions: MutableList<ChapterResultAction>,
        widthDp: Float = 836f,
        heightDp: Float = 470.5f,
        fontScale: Float = 1f,
        hasNextChapter: Boolean = false,
    ) {
        val summary = ChapterArchiveSummary("render-only-chapter", listOf(
            ChapterResultSummaryItem(ChapterResultCategory.SENTENCE, "整句能力", 3, 3),
            ChapterResultSummaryItem(ChapterResultCategory.PATTERN, "句式能力", 0, 2),
            ChapterResultSummaryItem(ChapterResultCategory.WRONG, "错题", 1, 1),
            ChapterResultSummaryItem(ChapterResultCategory.INCOMPLETE, "未完成", 2, 2),
        ), hasNextChapter, 1, listOf(
            ChapterReinforcementItem("s1", "PATTERN_SPEAKING", "p1", "feel + adjective", explanation),
            ChapterReinforcementItem("s1", "PATTERN_WRITING", "p1", "feel + adjective", explanation),
        ))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale)) {
                RlTheme {
                    Box(Modifier.requiredSize(widthDp.dp, heightDp.dp).testTag(VIEWPORT)) {
                        ChapterResultScreen(ChapterResultUiState(summary.chapterId, "render-only", 1, summary = summary), actions::add)
                    }
                }
            }
        }
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().layoutInfo
        assertEquals((widthDp * 2).toInt(), viewport.width)
        assertEquals((heightDp * 2).toInt(), viewport.height)
    }

    private fun itemText(capability: String, text: String) = compose.onNode(
        hasText(text) and hasAnyAncestor(hasTestTag("chapter-reinforcement-s1-$capability-p1")),
        useUnmergedTree = true,
    )

    private fun measuredText(node: SemanticsNodeInteraction, expected: String, fontSize: Float): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        val layout = results.single()
        assertEquals(expected, layout.layoutInput.text.text)
        assertEquals("Do not shrink the existing text to make it fit", fontSize.sp, layout.layoutInput.style.fontSize)
        assertTrue(layout.lineCount > 0)
        assertFalse("Scrolling must not stand in for discarded original text", layout.multiParagraph.didExceedMaxLines)
        assertEquals(expected.length, layout.getLineEnd(layout.lineCount - 1))
        repeat(layout.lineCount) { assertFalse("No original line may be ellipsized", layout.isLineEllipsized(it)) }
        return layout
    }

    private fun wholeTextInViewport(node: SemanticsNodeInteraction, layout: TextLayoutResult): Boolean {
        val coordinates = node.fetchSemanticsNode().layoutInfo.coordinates
        val origin = realParagraphOrigin(layout, coordinates.localToRoot(Offset.Zero), coordinates.size.width)
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot)
        val text = layout.layoutInput.text.text
        return text.indices.filter { !text[it].isWhitespace() }
            .all { fits(layout.getBoundingBox(it).translate(origin), clip) }
    }

    private fun assertWholeTextInViewport(node: SemanticsNodeInteraction, text: String, fontSize: Float) {
        val layout = measuredText(node, text, fontSize)
        assertTrue("Every actual glyph, including the last line, must fit its real ancestor clip: $text",
            wholeTextInViewport(node, layout))
    }

    private data class LineGeometry(val glyphs: List<Rect>, val visiblePaper: Rect, val origin: Offset) {
        fun isWhole() = glyphs.isNotEmpty() && glyphs.all { fits(it, visiblePaper) }
    }

    private fun assertWholeTextOnPaper(node: SemanticsNodeInteraction, expected: String, fontSize: Float) {
        val layout = measuredText(node, expected, fontSize)
        val geometry = lastLineGeometry(node, layout)
        val allGlyphs = expected.indices.filter { !expected[it].isWhitespace() }
            .map { layout.getBoundingBox(it).translate(geometry.origin) }
        val entireText = geometry.copy(glyphs = allGlyphs)
        assertTrue("Every original glyph must fit inside the unchanged paper ROI, including its y=90/630 top: " +
            "text=$expected geometry=$entireText", entireText.isWhole())
    }

    private fun scrollPanel() = compose.onNode(hasScrollAction() and
        hasAnyDescendant(hasTestTag("chapter-reinforcement-s1-PATTERN_WRITING-p1")))

    private fun scrollPosition() = scrollPanel().fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun lastLineGeometry(node: SemanticsNodeInteraction, layout: TextLayoutResult): LineGeometry {
        val coordinates = node.fetchSemanticsNode().layoutInfo.coordinates
        val origin = realParagraphOrigin(layout, coordinates.localToRoot(Offset.Zero), coordinates.size.width)
        val paperCoordinates = compose.onNodeWithTag(PAPER).fetchSemanticsNode().layoutInfo.coordinates
        val paperOrigin = paperCoordinates.localToRoot(Offset.Zero)
        // RlPaperPanel's actual rl_torn_note Image is matchParentSize + FillBounds.
        // Its 1700 x 630 PNG includes shadow/outer background, not usable paper.
        // Reuse the accepted original-image paper ROI, not the full composable bounds.
        val safePaper = Rect(
            paperOrigin.x + paperCoordinates.size.width * (45f / 1700f),
            paperOrigin.y + paperCoordinates.size.height * (90f / 630f),
            paperOrigin.x + paperCoordinates.size.width * (1655f / 1700f),
            paperOrigin.y + paperCoordinates.size.height * (540f / 630f),
        )
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(paperCoordinates.findRootCoordinates().localBoundingBoxOf(paperCoordinates, clipBounds = true))
            .intersect(compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot)
            .intersect(safePaper)
        val line = layout.lineCount - 1
        val text = layout.layoutInput.text.text
        val glyphs = (layout.getLineStart(line) until layout.getLineEnd(line, visibleEnd = true))
            .filter { !text[it].isWhitespace() }.map { layout.getBoundingBox(it).translate(origin) }
        return LineGeometry(glyphs, clip, origin)
    }

    private fun reachLastLineByTouch(
        node: SemanticsNodeInteraction,
        layout: TextLayoutResult,
        maxGestures: Int = 24,
        capture: Boolean = true,
    ): Int {
        val scroll = compose.onNode(hasScrollAction() and
            hasAnyDescendant(hasTestTag("chapter-reinforcement-s1-PATTERN_WRITING-p1")))
        fun position() = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        var geometry = lastLineGeometry(node, layout)
        var gestures = 0
        while (!geometry.isWhole() && gestures < maxGestures) {
            val oldPosition = position()
            val scrollCoordinates = scroll.fetchSemanticsNode().layoutInfo.coordinates
            val visibleScroll = scrollCoordinates.findRootCoordinates().localBoundingBoxOf(scrollCoordinates, clipBounds = true)
            val glyphCenter = (geometry.glyphs.minOf { it.top } + geometry.glyphs.maxOf { it.bottom }) / 2f
            val delta = glyphCenter - visibleScroll.center.y
            val touchSlop = ViewConfiguration.get(compose.activity).scaledTouchSlop.toFloat()
            scroll.assertIsDisplayed().performTouchInput {
                // A one-line viewport needs fine, reversible drags; never make
                // a large one-way swipe skip a readable line and call it clipped.
                val distance = (abs(delta) + touchSlop).coerceAtMost(height * .5f)
                val direction = if (delta >= 0f) 1f else -1f
                swipe(Offset(width * .5f, height * .5f + direction * distance / 2f),
                    Offset(width * .5f, height * .5f - direction * distance / 2f), 250)
            }
            compose.waitForIdle()
            gestures++
            geometry = lastLineGeometry(node, layout)
            if (position() == oldPosition) break
        }
        if (capture) captureShortIfRequested("last-line")
        assertTrue("Every last-line glyph must be touch reachable inside actual paper, not its shadow/torn edge: " +
            "text=${layout.layoutInput.text.text} gestures=$gestures position=${position()} geometry=$geometry",
            geometry.isWhole())
        return gestures
    }

    private fun captureShortIfRequested(suffix: String) {
        val phase = InstrumentationRegistry.getArguments().getString("resultPaperShortCapture") ?: return
        require(phase in setOf("before", "after") && suffix in setOf("initial", "last-line"))
        compose.waitForIdle()
        val anchors = listOf(footerBounds(), compose.onNodeWithTag(PAPER).fetchSemanticsNode().boundsInRoot)
        val view = compose.activity.window.decorView
        val drawn = AtomicBoolean()
        val listener = ViewTreeObserver.OnDrawListener { drawn.set(true) }
        compose.activityRule.scenario.onActivity {
            view.viewTreeObserver.addOnDrawListener(listener)
            view.postInvalidateOnAnimation()
        }
        try { compose.waitUntil(5_000) { drawn.get() } }
        finally { compose.activityRule.scenario.onActivity {
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener)
        } }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(200, 5_000)
        assertEquals(anchors, listOf(footerBounds(), compose.onNodeWithTag(PAPER).fetchSemanticsNode().boundsInRoot))
        val folder = File(requireNotNull(compose.activity.getExternalFilesDir(null)), "visual-qa")
        check(folder.isDirectory || folder.mkdirs())
        val output = File(folder, "32-paper-short-$suffix-$phase-$captureRun.png")
        check(output.canonicalFile.parentFile == folder.canonicalFile && !output.exists())
        val bitmap = requireNotNull(automation.takeScreenshot())
        try {
            assertEquals(1280, bitmap.width)
            assertEquals(720, bitmap.height)
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        Log.i("RealLanguageJourney", "Result short screenshot=${output.absolutePath}")
    }

    private fun realParagraphOrigin(layout: TextLayoutResult, origin: Offset, actualWidth: Int): Offset {
        val semanticWidth = layout.multiParagraph.width
        if (abs(semanticWidth - actualWidth) <= 1f) return origin
        // The same verified Compose 1.11.4 semantic-only width correction as the
        // header/dialog tests. Never infer clipping from didOverflowWidth alone.
        assertTrue("Resolve the real paragraph before asserting glyph geometry: " +
            "text=${layout.layoutInput.text} lines=${layout.lineCount} size=${layout.size} " +
            "actualWidth=$actualWidth semanticWidth=$semanticWidth " +
            "intrinsic=${layout.multiParagraph.intrinsics.maxIntrinsicWidth} " +
            "align=${layout.layoutInput.style.textAlign} left=${layout.getLineLeft(0)} right=${layout.getLineRight(0)}",
            layout.lineCount == 1 && layout.size.width == actualWidth && semanticWidth > actualWidth &&
                ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt() == actualWidth)
        val left = layout.getLineLeft(0)
        if (abs(left) <= 1f) return origin
        val offset = (semanticWidth - actualWidth) / 2f
        assertTrue("Only the established centered single-line correction is permitted",
            layout.layoutInput.style.textAlign == TextAlign.Center && abs(left - offset) <= 1f &&
                abs(layout.getLineRight(0) - offset - actualWidth) <= 1f)
        return origin - Offset(offset, 0f)
    }

    private fun footerBounds() = compose.onNodeWithText("返回首页").assertIsDisplayed().fetchSemanticsNode().boundsInRoot

    private companion object {
        const val VIEWPORT = "chapter-result-paper-fit-viewport"
        const val PAPER = "chapter-result-reinforcement-paper"
        fun fits(content: Rect, clip: Rect) = clip.width > 0f && clip.height > 0f &&
            content.left >= clip.left - 1f && content.right <= clip.right + 1f &&
            content.top >= clip.top - 1f && content.bottom <= clip.bottom + 1f
    }
}
