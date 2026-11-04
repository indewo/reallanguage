package com.reallanguage.app.feature.learning.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
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
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.RemainingChapterSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Pure screen fixtures: no Graph, database, credentials, recording, or HTTP. */
class ChapterSelectionButtonFitTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun useTheProductionWindowPolicy() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    @Test fun referenceSizeStartIsOneCompleteHorizontalLabelAndSelectsItsChapter() {
        val actions = mutableListOf<ChapterSelectionAction>()
        render(listOf(RemainingChapterSummary("c2", 2, "我写了一张简短的便条。")), actions)

        assertStartLabel("c2", requireSingleLine = true)
        start("c2").assertIsEnabled().performClick()

        assertEquals(listOf(ChapterSelectionAction.ChapterSelected("c2")), actions)
        compose.onNodeWithText("这份日记还有 1 个未开始章节").assertIsDisplayed()
        compose.onNodeWithText("稍后学习").assertIsDisplayed()
    }

    @Test fun aLongIdentityRemainsCompleteAndTouchScrollableWithoutMovingTheFooter() {
        val identity = "同事听完后点了点头，我们把尚未确认的安排逐条整理，保留每一项真实说明。".repeat(12) +
            "最后一项章节身份仍应完整可读。"
        val actions = mutableListOf<ChapterSelectionAction>()
        render(listOf(RemainingChapterSummary("long", 2, identity)), actions)
        val title = compose.onNodeWithText(identity, useUnmergedTree = true)
        val layout = layout(title)
        assertEquals(identity, layout.layoutInput.text.text)
        assertEquals("Do not shrink the chapter identity to make a button fit", 21.sp, layout.layoutInput.style.fontSize)
        assertTrue("This fixture must really wrap", layout.lineCount > 4)
        assertFalse("Do not remove or ellipsize identity text", layout.multiParagraph.didExceedMaxLines)
        assertFalse("Every identity line must wrap within its width", layout.didOverflowWidth)
        assertEquals(identity.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue("The opening line must remain readable", lineIsVisible(title, layout, 0))

        val footer = compose.onNodeWithText("稍后学习").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val paper = compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("chapter-selection-list")))
        var gestures = 0
        while (!lineIsVisible(title, layout, layout.lineCount - 1) && gestures < 40) {
            paper.assertIsDisplayed().performTouchInput { swipeUp() }
            compose.waitForIdle()
            gestures++
        }
        assertTrue("The real finger gesture must read through the long identity", gestures > 0)
        assertTrue("The complete last identity line must be readable after local scrolling",
            lineIsVisible(title, layout, layout.lineCount - 1))
        assertEquals("Reading the chapter list must not move or hide Learn later", footer,
            compose.onNodeWithText("稍后学习").assertIsDisplayed().fetchSemanticsNode().boundsInRoot)

        start("long").performScrollTo()
        assertStartLabel("long", requireSingleLine = true)
        start("long").assertIsEnabled().performClick()
        assertEquals(listOf(ChapterSelectionAction.ChapterSelected("long")), actions)
    }

    @Test fun shortDoubleFontKeepsEveryStartGlyphReadableAndTargetsTheChosenChapter() {
        val actions = mutableListOf<ChapterSelectionAction>()
        val chapters = listOf(
            RemainingChapterSummary("c2", 2, "同事听完后点了点头"),
            RemainingChapterSummary("c3", 3, "我也轻松了很多"),
        )
        render(chapters, actions, short = true)

        chapters.forEach { chapter ->
            start(chapter.chapterId).performScrollTo()
            // Two-times font may wrap; both actual glyphs must still be fully visible.
            assertStartLabel(chapter.chapterId, requireSingleLine = false)
            start(chapter.chapterId).assertIsEnabled().performClick()
            compose.onNodeWithText("稍后学习").assertIsDisplayed()
        }
        assertEquals(chapters.map { ChapterSelectionAction.ChapterSelected(it.chapterId) }, actions)
    }

    private fun render(
        chapters: List<RemainingChapterSummary>,
        actions: MutableList<ChapterSelectionAction>,
        short: Boolean = false,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, if (short) 2f else 1f)) {
                RlTheme {
                    Box(Modifier.requiredSize(if (short) 640.dp else 836.dp, if (short) 360.dp else 470.5.dp)
                        .testTag("chapter-selection-fit-viewport")) {
                        ChapterSelectionScreen(ChapterSelectionUiState(sourceId = "isolated-screen", chapters = chapters), actions::add)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun start(id: String) = compose.onNodeWithTag("chapter-selection-start-$id")

    private fun assertStartLabel(id: String, requireSingleLine: Boolean) {
        val button = start(id).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val text = compose.onNode(hasText("开始") and hasAnyAncestor(hasTestTag("chapter-selection-start-$id")),
            useUnmergedTree = true).assertIsDisplayed()
        val layout = layout(text)
        assertEquals("开始", layout.layoutInput.text.text)
        assertEquals("Preserve the existing button font size", 16.sp, layout.layoutInput.style.fontSize)
        if (requireSingleLine) assertEquals("At the original viewport 开始 must not be stacked vertically", 1, layout.lineCount)
        assertFalse("A clipped label cannot count as a fitting button", layout.multiParagraph.didExceedMaxLines)
        val coordinates = text.fetchSemanticsNode().layoutInfo.coordinates
        val origin = coordinates.localToRoot(Offset.Zero)
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(button)
            .intersect(compose.onNodeWithTag("chapter-selection-fit-viewport").fetchSemanticsNode().boundsInRoot)
        val glyphs = "开始".indices.map { layout.getBoundingBox(it).translate(origin) }
        assertTrue("Both start glyphs must be complete inside the real button: id=$id clip=$clip glyphs=$glyphs",
            glyphs.all { contains(clip, it) })
    }

    private fun layout(text: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        return results.single()
    }

    private fun lineIsVisible(text: SemanticsNodeInteraction, layout: TextLayoutResult, line: Int): Boolean {
        val coordinates = text.fetchSemanticsNode().layoutInfo.coordinates
        val origin = coordinates.localToRoot(Offset.Zero)
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(compose.onNodeWithTag("chapter-selection-fit-viewport").fetchSemanticsNode().boundsInRoot)
        val lineBounds = Rect(origin.x + layout.getLineLeft(line), origin.y + layout.getLineTop(line),
            origin.x + layout.getLineRight(line), origin.y + layout.getLineBottom(line))
        return contains(clip, lineBounds)
    }

    private fun contains(clip: Rect, content: Rect) = clip.width > 0f && clip.height > 0f &&
        content.left >= clip.left - 1f && content.right <= clip.right + 1f &&
        content.top >= clip.top - 1f && content.bottom <= clip.bottom + 1f
}
