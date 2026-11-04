package com.reallanguage.app.feature.learning.presentation

import androidx.compose.ui.test.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import org.junit.Rule
import org.junit.Test

class ChapterEntryScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @org.junit.Before fun landscape() {
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        composeRule.waitUntil(5_000) { composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test fun realThreeLineDialogueFitsOnePageWithAlignedColumnsAndStandardApostrophes() {
        val chapter = FixedLearningChapter("real", 1, listOf(
            FixedLearningSentence("s1", "Yeah, I get really happy when I’ m eating.", "用户：嗯，吃饭时我会很开心。", true),
            FixedLearningSentence("s2", "That’ s nice.", "对方：那挺好的。", false),
            FixedLearningSentence("s3", "Just enjoy it for now.", "你就先享受吧。", false),
        ))
        composeRule.setContent { RlTheme {
            CompositionLocalProvider(LocalLearningExitAction provides {}) {
                ChapterEntryScreen(ChapterEntryUiState(chapter = chapter), {})
            }
        } }

        composeRule.onNodeWithText("Yeah, I get really happy when I'm eating.").assertIsDisplayed()
        composeRule.onNodeWithText("That's nice.").assertIsDisplayed()
        composeRule.onAllNodes(hasText("I’ m") or hasText("That’ s"), useUnmergedTree = true).assertCountEquals(0)
        val scroll = composeRule.onNodeWithTag("chapter_entry_sentences").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
        org.junit.Assert.assertEquals("Normal three-line overview must fit without scrolling", 0f, scroll.maxValue(), 1f)
        composeRule.onNodeWithText("从基础讲解开始，每一步都使用同一组固定句子。").assertDoesNotExist()

        val dividerCenters = (1..3).map { index ->
            composeRule.onNodeWithTag("learning-divider-s$index").assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot.center.x
        }
        dividerCenters.drop(1).forEach { center ->
            org.junit.Assert.assertEquals("每一行必须共用同一条中英文分栏线", dividerCenters.first(), center, 1f)
        }
        (1..3).forEach { index ->
            val english = composeRule.onNodeWithTag("learning-english-s$index").fetchSemanticsNode().boundsInRoot.center.y
            val chinese = composeRule.onNodeWithTag("learning-chinese-s$index").fetchSemanticsNode().boundsInRoot.center.y
            org.junit.Assert.assertEquals("第 $index 行中英文必须纵向居中对齐", english, chinese, 2f)
        }
        (0..7).forEach { index ->
            val bounds = composeRule.onNodeWithTag("chapter_entry_stage_0_$index").assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue("Learning path cannot overlap the pinned action", bounds.bottom <= composeRule.onNodeWithTag("chapter_entry_start").fetchSemanticsNode().boundsInRoot.top)
        }
        composeRule.onNodeWithTag("chapter_entry_start").assertIsDisplayed()
    }

    @Test
    fun showsFixedChapterJourneyAndStartsWithRealAction() {
        val actions = mutableListOf<ChapterEntryAction>()
        val chapter = FixedLearningChapter(
            "c1",
            1,
            listOf(
                FixedLearningSentence("s1", "I was nervous.", "我本来有点紧张。", false),
                FixedLearningSentence("s2", "I explained my idea clearly.", "我把想法讲清楚了。", true),
            ),
        )

        composeRule.setContent {
            RlTheme { ChapterEntryScreen(ChapterEntryUiState(chapter = chapter), actions::add) }
        }

        composeRule.onNodeWithText("第 1 章 · 共同学习").assertIsDisplayed()
        composeRule.onNodeWithText("重点句 1 / 2").assertIsDisplayed()
        composeRule.onNodeWithTag("chapter_entry_sentences").assertIsDisplayed()
        composeRule.onNodeWithTag("chapter_entry_stages").assertIsDisplayed()
        composeRule.onNodeWithText("基础讲解").assertIsDisplayed()
        composeRule.onNodeWithText("场景回应").assertIsDisplayed()
        composeRule.onNodeWithTag("chapter_entry_start").performClick()
        assert(actions.contains(ChapterEntryAction.StartRequested))
    }

    @Test
    fun focusBadgeLivesAboveSentenceAndNeverOverlapsTheExitAction() {
        val chapter = FixedLearningChapter(
            "c1", 1,
            listOf(FixedLearningSentence("s1", "It's a beautiful day today.", "今天是美好的一天。", true)),
        )
        composeRule.setContent { RlTheme {
            CompositionLocalProvider(LocalLearningExitAction provides {}) {
                ChapterEntryScreen(ChapterEntryUiState(chapter = chapter), {})
            }
        } }
        val heading = composeRule.onNodeWithText("第 1 章 · 共同学习").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val badge = composeRule.onNodeWithTag("chapter_entry_focus_badge").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val exit = composeRule.onNodeWithTag("learning-header-exit").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("重点句角标应位于标题下方", badge.top >= heading.bottom)
        org.junit.Assert.assertTrue("重点句角标不得与退出学习重叠", badge.top >= exit.bottom || badge.right <= exit.left)
    }

    @Test
    fun enlargedTextStillKeepsFocusBadgeSeparateFromExitAction() {
        val chapter = FixedLearningChapter(
            "c1", 1,
            listOf(FixedLearningSentence("s1", "It's a beautiful day today.", "今天是美好的一天。", true)),
        )
        composeRule.setContent { RlTheme {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1.5f)) {
                CompositionLocalProvider(LocalLearningExitAction provides {}) {
                    ChapterEntryScreen(ChapterEntryUiState(chapter = chapter), {})
                }
            }
        } }
        val badge = composeRule.onNodeWithTag("chapter_entry_focus_badge").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val exit = composeRule.onNodeWithTag("learning-header-exit").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("放大字体后重点句角标仍不得与退出学习重叠", badge.top >= exit.bottom || badge.right <= exit.left)
        composeRule.onNodeWithTag("chapter_entry_start").assertIsDisplayed()
    }
}
