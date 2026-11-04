package com.reallanguage.app.feature.review.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.review.domain.ReviewAbility
import com.reallanguage.app.feature.review.domain.ReviewAbilityModule
import com.reallanguage.app.feature.review.domain.ReviewAbilityStatus
import com.reallanguage.app.feature.review.domain.ReviewChapterSnapshot
import com.reallanguage.app.feature.review.domain.ReviewContextSentence
import com.reallanguage.app.feature.review.domain.ReviewSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Readability of the existing chapter-selection state, not original 37's independent-speaking artwork. */
class ReviewChapterContentFitTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun landscape() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test fun threeSourceRowsAreLocallyScrollableWithoutMovingTheActionButtons() {
        val lines = listOf("First context line.", "The current fixed focus sentence.", "Last context line.")
        render(lines)
        val continueButton = compose.onNodeWithTag("review-chapter-continue").assertIsDisplayed()
        val before = continueButton.fetchSemanticsNode().boundsInRoot
        lines.forEach { line ->
            compose.onNodeWithText(line, useUnmergedTree = true).performScrollTo()
            assertWholeVisibleText(line)
        }
        assertEquals("Only source content scrolls; the page actions remain fixed", before, continueButton.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun longSourceKeepsAllCharactersAndExposesItsFinalLineInsideTheSourceViewport() {
        val longLine = "The final context sentence preserves the details of the original conversation without dropping any words. ".repeat(8).trim()
        render(listOf("First context line.", "The current fixed focus sentence.", longLine))
        val layout = textLayout(longLine)
        assertTrue("This fixture must exercise multiple full lines", layout.lineCount >= 4)
        val sourceScroll = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText(longLine)))
        sourceScroll.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, 100_000f)) }
        compose.waitForIdle()
        val node = compose.onNodeWithText(longLine, useUnmergedTree = true)
        val visible = node.fetchSemanticsNode().boundsInRoot
        val originY = node.getUnclippedBoundsInRoot().top.value * 2f
        val last = layout.lineCount - 1
        assertTrue("The last complete line must be reachable, not clipped at the source panel edge: visible=$visible", 
            originY + layout.getLineTop(last) >= visible.top - 1f && originY + layout.getLineBottom(last) <= visible.bottom + 1f)
        compose.onNodeWithTag("review-chapter-continue").assertIsDisplayed()
        compose.onNodeWithText("退出统一回顾？").assertIsDisplayed()
    }

    @Test fun currentAbilityTitleIsWholeAndBothActionsRemainReachable() {
        val actions = mutableListOf<ReviewChapterAction>()
        render(listOf("First context line.", "The current fixed focus sentence.", "Last context line."), actions::add)
        assertWholeVisibleText("整句会说")
        compose.onNodeWithTag("review-chapter-continue").assertIsDisplayed().performClick()
        compose.onNodeWithText("退出统一回顾？").assertIsDisplayed().performClick()
        assertEquals(listOf(ReviewChapterAction.OpenCurrentModuleRequested, ReviewChapterAction.ExitRequested), actions)
    }

    @Test fun shortDoubleFontKeepsLongSourceCurrentAbilityAndActionsLocallyReachable() {
        val source = "九月九日日记：和多年没有见面的朋友重新联系后，讨论周末旅行的具体安排以及需要提前准备的事项"
        val actions = mutableListOf<ReviewChapterAction>()
        render(listOf("The first context line remains available.", "I discussed the plan with my friend.", "The last context line is retained."),
            actions::add, width = 640.dp, height = 360.dp, fontScale = 2f, sourceTitle = source)
        captureShortIfRequested("initial")
        assertWholeVisibleText("统一回顾 · 第 2 章")
        val header = compose.onNodeWithText("统一回顾 · 第 2 章").getUnclippedBoundsInRoot()
        textLayout(source)
        compose.onNodeWithText(source, useUnmergedTree = true).performScrollTo()
        assertReadableLine(source)
        // A long source need not fit at once, but one whole line must fit the local viewport.
        textLayout("整句会说")
        compose.onNodeWithText("整句会说", useUnmergedTree = true).performScrollTo()
        assertWholeVisibleText("整句会说")
        captureShortIfRequested("ability")
        listOf("继续当前能力", "退出统一回顾？").forEach { text ->
            val layout = textLayout(text)
            val node = compose.onNodeWithText(text, useUnmergedTree = true)
            if (node.fetchSemanticsNode().boundsInRoot.height + 1f < layout.multiParagraph.height) node.performScrollTo()
            assertWholeVisibleText(text)
            node.assertIsDisplayed().performClick()
        }
        assertEquals("Independent regions may scroll; the whole page must not move", header,
            compose.onNodeWithText("统一回顾 · 第 2 章").getUnclippedBoundsInRoot())
        assertEquals(listOf(ReviewChapterAction.OpenCurrentModuleRequested, ReviewChapterAction.ExitRequested), actions)
        captureShortIfRequested("actions")
    }

    private fun captureShortIfRequested(stage: String) {
        val phase = InstrumentationRegistry.getArguments().getString("reviewContentFitCapture") ?: return
        require(phase in setOf("before", "after"))
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag("review-content-fit-viewport").captureToImage().asAndroidBitmap()
        try {
            check(bitmap.width == 1280 && bitmap.height == 720)
            val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "visual-qa")
            check(folder.isDirectory || folder.mkdirs())
            File(folder, "37-review-short-font2-$stage-$phase.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun textLayout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        val diagnostic = "[$text] size=${layout.size}, paragraphHeight=${layout.multiParagraph.height}, lines=${layout.lineCount}"
        assertFalse(diagnostic, layout.multiParagraph.didExceedMaxLines)
        assertTrue(diagnostic, layout.multiParagraph.height <= layout.size.height + 1f)
        assertEquals(diagnostic, text.length, layout.getLineEnd(layout.lineCount - 1))
        repeat(layout.lineCount) { line ->
            assertFalse(diagnostic, layout.isLineEllipsized(line))
            assertTrue(diagnostic, layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
        }
        return layout
    }

    private fun assertWholeVisibleText(text: String) {
        val layout = textLayout(text)
        val node = compose.onNodeWithText(text, useUnmergedTree = true)
        val visible = node.fetchSemanticsNode().boundsInRoot
        val original = node.getUnclippedBoundsInRoot()
        val top = original.top.value * 2f
        val left = original.left.value * 2f
        repeat(layout.lineCount) { line ->
            assertTrue("[$text] must fit the actually visible viewport, not just its un-clipped Text layout: visible=$visible, original=$original",
                top + layout.getLineTop(line) >= visible.top - 1f &&
                    top + layout.getLineBottom(line) <= visible.bottom + 1f &&
                    left + layout.getLineLeft(line) >= visible.left - 1f &&
                    left + layout.getLineRight(line) <= visible.right + 1f)
        }
    }

    private fun assertReadableLine(text: String) {
        val layout = textLayout(text)
        val node = compose.onNodeWithText(text, useUnmergedTree = true)
        val visible = node.fetchSemanticsNode().boundsInRoot
        val top = node.getUnclippedBoundsInRoot().top.value * 2f
        assertTrue("[$text] needs a complete readable line, not a scrollable slice of a glyph: visible=$visible, lineHeight=${layout.getLineBottom(0) - layout.getLineTop(0)}",
            visible.width > 0f && (0 until layout.lineCount).any { line ->
                top + layout.getLineTop(line) >= visible.top - 1f && top + layout.getLineBottom(line) <= visible.bottom + 1f
            })
    }

    private fun render(lines: List<String>, onAction: (ReviewChapterAction) -> Unit = {},
        width: Dp = 914.5.dp, height: Dp = 430.dp, fontScale: Float = 1f, sourceTitle: String = "已保存的日记") {
        val sentences = lines.mapIndexed { index, text -> ReviewContextSentence("sentence-$index", text, "已保存中文$index", index == 1, index) }
        val chapter = ReviewChapterSnapshot("review", "5f49e9ac-8c66-4d15-a3bb-cb75554cdf82", ReviewSourceType.DIARY,
            sourceTitle, lines, lines[1],
            listOf(ReviewAbility("ability", ReviewAbilityModule.FULL_SENTENCE_SPEAKING, ReviewAbilityStatus.LEARNING, "sentence-1")),
            originalChapterNumber = 2, originalSentences = sentences)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale)) {
                RlTheme {
                    Box(Modifier.requiredSize(width, height).testTag("review-content-fit-viewport")) {
                        RlPaperBackground {
                            ReviewChapterScreen(ReviewChapterUiState("review", "command", chapter, loading = false), onAction)
                        }
                    }
                }
            }
        }
        val bounds = compose.onNodeWithTag("review-content-fit-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(width.value * 2f, bounds.width, 1f)
        assertEquals(height.value * 2f, bounds.height, 1f)
    }
}
