package com.reallanguage.app.feature.home.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.home.domain.IncompleteLearningEntry
import com.reallanguage.app.feature.home.domain.OrdinarySourceType
import com.reallanguage.app.feature.home.domain.ResumeLearningKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** HM03 source and resume descriptions remain readable; this is not a pixel-parity assertion. */
class IncompleteLearningContentFitTest {
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

    @Test fun referenceLandscapeReadsEverySourceAndResumeDescriptionWithoutEllipsis() {
        render(entries = referenceEntries)
        val viewport = composeRule.onNodeWithTag("incomplete-learning-fit-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(1831f, viewport.width, 1f)
        assertEquals(859f, viewport.height, 1f)
        referenceEntries.forEach { entry ->
            assertWholeText(entry.sourceLabelZh)
            assertWholeText("第 ${entry.chapterNumber} 章 · ${entry.savedStepLabelZh}")
        }
        val source = composeRule.onNodeWithText(referenceEntries.first().sourceLabelZh, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val chapter = composeRule.onNodeWithText("第 2 章 · 整句会说", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val position = composeRule.onNodeWithText("停在 6 / 9 步", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val resume = composeRule.onNodeWithTag("incomplete-learning-resume-learning-1").fetchSemanticsNode().boundsInRoot
        assertTrue("100% reference must retain source → chapter → position → continue columns",
            source.right <= chapter.left && chapter.right <= position.left && position.right <= resume.left)
        assertTrue("100% reference fields must remain in one row, not the compact reading arrangement",
            source.top < resume.bottom && resume.top < source.bottom && chapter.top < resume.bottom && resume.top < chapter.bottom)
        composeRule.onNodeWithTag("incomplete-learning-return-home").assertIsDisplayed()
    }

    @Test fun shortDoubleFontKeepsLongSourcesCompleteAndLastResumeUsesThatExactLearningId() {
        val entries = referenceEntries.mapIndexed { index, entry ->
            entry.copy(
                sourceLabelZh = listOf(
                    "九月八日日记：和多年没有见面的朋友一起准备旅行并讨论出发前的安排",
                    "本机录音：会议结束后记录与同事协商调整下一阶段工作安排的表达 · 01:24",
                    "AI 场景：面试结束后向招聘负责人了解下一轮面试的时间与需要准备的材料",
                )[index],
                savedStepLabelZh = "完整书写中保留的当前句子与尚未提交的学习内容",
            )
        }
        val actions = mutableListOf<IncompleteLearningAction>()
        render(entries, width = 640.dp, height = 360.dp, fontScale = 2f,
            referenceInsets = false, onAction = actions::add)
        entries.forEach { entry ->
            assertWholeText(entry.sourceLabelZh)
            assertWholeText("第 ${entry.chapterNumber} 章 · ${entry.savedStepLabelZh}")
        }
        val footer = composeRule.onNodeWithTag("incomplete-learning-return-home").assertIsDisplayed()
        val footerBefore = footer.fetchSemanticsNode().boundsInRoot
        val viewport = composeRule.onNodeWithTag("incomplete-learning-fit-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue("Return must remain inside the actual short viewport", footerBefore.top >= viewport.top && footerBefore.bottom <= viewport.bottom + 1f)
        composeRule.onNodeWithTag("incomplete-learning-resume-${entries.last().learningId}")
            .performScrollTo().assertIsDisplayed().performClick()
        footer.assertIsDisplayed()
        assertEquals("Only the list should scroll, not the fixed Return action",
            footerBefore, footer.fetchSemanticsNode().boundsInRoot)
        footer.performClick()
        assertEquals(listOf(IncompleteLearningAction.ResumeRequested(entries.last().learningId),
            IncompleteLearningAction.ReturnHomeRequested), actions)
    }

    @Test fun backIconHasAnAccessiblePurposeAndActuallyReturnsHome() {
        val actions = mutableListOf<IncompleteLearningAction>()
        render(referenceEntries, onAction = actions::add)
        composeRule.onNodeWithContentDescription("返回").assertIsDisplayed().assertHasClickAction().performClick()
        assertEquals(listOf(IncompleteLearningAction.ReturnHomeRequested), actions)
    }

    @Test fun shortDoubleFontUsesReadableGroupsInsteadOfSingleCharacterColumnsAndKeepsContinueOnOneLine() {
        val source = "今天与多年未见的朋友重新联系并约好周末一起出门散步"
        val entry = referenceEntries.first().copy(sourceLabelZh = source,
            savedStepLabelZh = "完整书写时保留的当前句子与尚未提交的学习内容")
        val actions = mutableListOf<IncompleteLearningAction>()
        render(listOf(entry), width = 640.dp, height = 360.dp, fontScale = 2f,
            referenceInsets = false, onAction = actions::add)
        listOf(" 当前位置", "继续").forEach { text ->
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertTrue("[$text] must remain an understandable one-line label, not the observed vertical character strip: " +
                layouts.joinToString { "size=${it.size}, lines=${it.lineCount}, overflow=${it.hasVisualOverflow}" },
                layouts.isNotEmpty() && layouts.all { it.lineCount == 1 && !it.hasVisualOverflow })
        }
        assertWholeText(source)
        assertWholeText("第 ${entry.chapterNumber} 章 · ${entry.savedStepLabelZh}")
        val sourceLayouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(source, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(sourceLayouts)) }
        assertTrue("Long Chinese source text must use a readable line, not a column of isolated characters",
            sourceLayouts.all { layout -> (0 until layout.lineCount - 1).all { line ->
                layout.getLineEnd(line, visibleEnd = true) - layout.getLineStart(line) >= 4
            } })
        composeRule.onNodeWithTag("incomplete-learning-resume-${entry.learningId}")
            .performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("incomplete-learning-return-home").assertIsDisplayed().performClick()
        assertEquals(listOf(IncompleteLearningAction.ResumeRequested(entry.learningId),
            IncompleteLearningAction.ReturnHomeRequested), actions)
    }

    private fun assertWholeText(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertTrue("HM03 must not truncate source/resume text [$text]: " +
            layouts.joinToString { "size=${it.size}, overflowW=${it.didOverflowWidth}, overflowH=${it.didOverflowHeight}, " +
                "lines=${it.lineCount}, lastEnd=${it.getLineEnd(it.lineCount - 1)}, " +
                "edges=${(0 until it.lineCount).map { line -> it.getLineLeft(line) to it.getLineRight(line) }}, " +
                "constraints=${it.layoutInput.constraints}, maxLines=${it.layoutInput.maxLines}, softWrap=${it.layoutInput.softWrap}" },
            layouts.isNotEmpty() && layouts.all { layout ->
                !layout.hasVisualOverflow && layout.getLineEnd(layout.lineCount - 1) == text.length &&
                    (0 until layout.lineCount).all { line ->
                        !layout.isLineEllipsized(line) && layout.getLineLeft(line) >= -.5f &&
                            layout.getLineRight(line) <= layout.size.width + .5f
                    }
            })
    }

    private fun render(entries: List<IncompleteLearningEntry>, width: Dp = 915.5.dp, height: Dp = 429.5.dp,
        fontScale: Float = 1f, referenceInsets: Boolean = true,
        onAction: (IncompleteLearningAction) -> Unit = {},
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale)) {
                RlTheme {
                    Box(Modifier.requiredSize(width, height)
                        .then(if (referenceInsets) Modifier.consumeWindowInsets(WindowInsets.safeDrawing) else Modifier)
                        .testTag("incomplete-learning-fit-viewport")) {
                        IncompleteLearningScreen(IncompleteLearningUiState(entries = entries, loading = false), onAction)
                    }
                }
            }
        }
    }

    companion object {
        private val referenceEntries = listOf(
            IncompleteLearningEntry("learning-1", OrdinarySourceType.DIARY, "9月2日日记", 2, "LR-06", "整句会说", ResumeLearningKind.ORDINARY),
            IncompleteLearningEntry("learning-2", OrdinarySourceType.QUICK_RECORDING, "本机录音 · 01:24", 1, "LR-04", "手写抄写", ResumeLearningKind.ORDINARY),
            IncompleteLearningEntry("learning-3", OrdinarySourceType.AI_SCENE, "AI场景 · 面试前", 3, "LR-08", "完整书写", ResumeLearningKind.ORDINARY),
        )
    }
}
