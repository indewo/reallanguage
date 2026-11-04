package com.reallanguage.app.feature.formation.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.formation.domain.EnglishCandidate
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishSentenceDraft
import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.LearningTargetGenerator
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Public formation screens in a real Activity; tests do not claim source-image parity. */
class FormationSmallHeightReadabilityTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before fun landscape() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            WindowCompat.setDecorFitsSystemWindows(it.window, false)
            WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
        composeRule.waitUntil(5_000) {
            composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    @Test fun nextSentenceDoesNotInheritThePreviousSentencesBottomTargetScroll() {
        val sentences = (1..2).map { index -> EnglishSentenceDraft(
            "u$index", "这是第 $index 个不同的重点句。", true,
            listOf(EnglishCandidate("e$index", "This is the distinct sentence number $index.")),
        ) }
        val targets = (1..2).flatMap { sentence -> (1..6).map { target ->
            LearningTarget("target-$sentence-$target", "u$sentence", "Target $sentence-$target",
                "该重点句中需要完整阅读的独立学习解释。".repeat(4), LearningTargetType.WORD)
        } }
        lateinit var viewModel: LearningTargetViewModel
        composeRule.runOnUiThread {
            viewModel = LearningTargetViewModel(listOf(EnglishChapterDraft("c1", sentences)),
                mapOf("u1" to "e1", "u2" to "e2"), targets, emptyList(),
                LearningTargetGenerator { _, _ -> error("This navigation test must not generate or call AI") })
        }
        render(height = 400.dp, fontScale = 1f) {
            LearningTargetScreen(viewModel.uiState.collectAsState().value, viewModel::onAction)
        }
        // One real finger gesture in the visible gap between the first two
        // cards must expose the next row. This does not use ScrollBy/ScrollTo.
        val page = composeRule.onNodeWithTag("learning_targets_page")
        val pageBounds = page.fetchSemanticsNode().boundsInRoot
        val firstCard = composeRule.onNodeWithTag("learning_target_target-1-1").fetchSemanticsNode().boundsInRoot
        val secondCard = composeRule.onNodeWithTag("learning_target_target-1-2").fetchSemanticsNode().boundsInRoot
        val gutterX = (firstCard.right + secondCard.left) / 2f - pageBounds.left
        val startY = minOf(firstCard.bottom, secondCard.bottom) - pageBounds.top - 12f
        val endY = maxOf(firstCard.top, secondCard.top) - pageBounds.top + 12f
        assertTrue("The actual outer list must offer a usable visible drag region", startY - endY > 80f)
        page.performTouchInput { swipe(Offset(gutterX, startY), Offset(gutterX, endY), durationMillis = 400) }
        composeRule.waitForIdle()
        assertReadableLine("Target 1-6")
        // Reach the card in the outer list before scrolling its own finite paper.
        // Text.performScrollTo operates only on its nearest scroll ancestor.
        composeRule.onNodeWithTag("learning_target_target-1-6").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Target 1-6", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        assertReadableLine("Target 1-6")
        composeRule.onNodeWithText("继续").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(1, viewModel.uiState.value.focusSentenceIndex)
            assertEquals(targets.map { it.targetId }.toSet(), viewModel.uiState.value.selectedTargetIds)
        }
        // This is a different sentence, not a resize or a new target added to the old one.
        // Its first actual target must not start hidden by another sentence's scroll offset.
        composeRule.onNodeWithText("Target 2-1", useUnmergedTree = true).assertIsDisplayed()
        assertReadableLine("Target 2-1")
    }

    @Test fun targetSelectionAtWideShort130PercentKeepsLongContentAndEveryActionReadable() {
        val english = "I want to explain the plan clearly and check what my friend understood. ".repeat(8)
        val chinese = "我希望清楚解释计划并确认朋友理解了哪些安排。".repeat(8)
        val targets = (1..6).map { LearningTarget("t$it", "u1", "Target $it",
            "需要完整阅读而不能裁掉的学习解释。".repeat(8), LearningTargetType.WORD) }
        val actions = mutableListOf<LearningTargetAction>()
        render {
            LearningTargetScreen(LearningTargetUiState(
                chapters = listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", chinese, true,
                    listOf(EnglishCandidate("e1", english)))))),
                selectedCandidateIds = mapOf("u1" to "e1"), targets = targets,
                selectedTargetIds = targets.map { it.targetId }.toSet(), queryableExpressions = emptyList(),
                generationFailed = true,
            ), actions::add)
        }
        assertGlyphLayout("选择学习目标")
        assertGlyphLayout("句子会继续学习，下面的词义与句式可以按需选择。")
        listOf(english, chinese).forEach { text ->
            assertGlyphLayout(text)
            val node = composeRule.onNodeWithText(text)
            // At the baseline these are already visible, independently scrollable
            // Text nodes (not descendants of another scroll container).
            if (node.fetchSemanticsNode().boundsInRoot.height <= 0f) node.performScrollTo()
            assertReadableLine(text)
        }
        composeRule.onNodeWithTag("learning_target_t6").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Target 6", useUnmergedTree = true).performScrollTo()
        assertReadableLine("Target 6")
        composeRule.onNodeWithText("学习目标生成失败").performScrollTo()
        assertReadableLine("学习目标生成失败")
        listOf("全部取消", "返回", "继续").forEach {
            assertGlyphLayout(it)
            composeRule.onNodeWithText(it).assertIsDisplayed().performClick()
        }
        assertEquals(listOf(LearningTargetAction.ClearAllRequested, LearningTargetAction.BackRequested,
            LearningTargetAction.ContinueRequested), actions)
    }

    @Test fun finalFixAtWideShort130PercentKeepsBothDocumentsReadableAfterFailureAndActionsReachable() {
        val english = "I want to explain the plan clearly and check what my friend understood. ".repeat(8)
        val chinese = "我希望清楚解释计划并确认朋友理解了哪些安排。".repeat(8)
        val targets = (1..12).map { LearningTarget("t$it", "u1", "Target $it",
            "当前句中经过确认的目标", LearningTargetType.WORD) }
        val actions = mutableListOf<FinalFixAction>()
        render {
            FinalFixScreen(FinalFixUiState(
                listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", chinese, true,
                    listOf(EnglishCandidate("e1", english)))))),
                mapOf("u1" to "e1"), targets, fixFailed = true,
            ), actions::add)
        }
        assertGlyphLayout("固定后，学习将只使用下面确认的内容与目标。")
        listOf(english, chinese, "Target 12").forEach { text ->
            assertGlyphLayout(text)
            composeRule.onNodeWithText(text).performScrollTo()
            assertReadableLine(text)
        }
        val failure = "未能完整固定，本次没有生成任何正式章节，请重试。"
        assertGlyphLayout(failure)
        composeRule.onNodeWithText(failure).performScrollTo().assertIsDisplayed()
        assertReadableLine(failure)
        listOf("返回修改", "确认并固定").forEach { text ->
            // The confirm title has the same text; select its actual action below instead.
            if (text == "返回修改") {
                assertGlyphLayout(text)
                composeRule.onNodeWithText(text).assertIsDisplayed().performClick()
            } else {
                composeRule.onNodeWithTag("final_fix_confirm").assertIsDisplayed().performClick()
            }
        }
        assertEquals(listOf(FinalFixAction.BackRequested, FinalFixAction.ConfirmRequested), actions)
    }

    private fun assertGlyphLayout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        val diagnostic = "[$text] size=${layout.size}, paragraphHeight=${layout.multiParagraph.height}, " +
            "lines=${layout.lineCount}, edges=${(0 until layout.lineCount).map { layout.getLineLeft(it) to layout.getLineRight(it) }}"
        // Wrap-content paragraphs may retain unused width. Only actual line edges,
        // complete height and all characters can establish clipping (not the overflow boolean).
        assertFalse(diagnostic, layout.multiParagraph.didExceedMaxLines)
        assertTrue(diagnostic, layout.multiParagraph.height <= layout.size.height + 1f)
        assertEquals(diagnostic, text.length, layout.getLineEnd(layout.lineCount - 1))
        repeat(layout.lineCount) { line ->
            assertFalse(diagnostic, layout.isLineEllipsized(line))
            assertTrue(diagnostic, layout.getLineLeft(line) >= -1f)
            assertTrue(diagnostic, layout.getLineRight(line) <= layout.size.width + 1f)
        }
        return layout
    }

    private fun assertReadableLine(text: String) {
        val layout = assertGlyphLayout(text)
        val node = composeRule.onNodeWithText(text, useUnmergedTree = true)
        val visible = node.fetchSemanticsNode().boundsInRoot
        val unclipped = node.getUnclippedBoundsInRoot()
        val originY = unclipped.top.value * 2f
        assertTrue("[$text] must expose at least one complete line, not merely a sliver: " +
            "visible=$visible, unclipped=$unclipped, lines=${layout.lineCount}",
            visible.width > 0 && (0 until layout.lineCount).any { line ->
                originY + layout.getLineTop(line) >= visible.top - 1f &&
                    originY + layout.getLineBottom(line) <= visible.bottom + 1f
            })
    }

    private fun render(height: Dp = 300.dp, fontScale: Float = 1.3f, content: @Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale)) {
                RlTheme {
                    Box(Modifier.requiredSize(850.dp, height).testTag("formation-small-height-viewport")) { content() }
                }
            }
        }
        val viewport = composeRule.onNodeWithTag("formation-small-height-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(1700f, viewport.width, 1f)
        assertEquals(height.value * 2f, viewport.height, 1f)
    }
}
