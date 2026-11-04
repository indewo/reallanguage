package com.reallanguage.app.feature.home.presentation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.home.domain.IncompleteLearningEntry
import com.reallanguage.app.feature.home.domain.OrdinarySourceType
import com.reallanguage.app.feature.home.domain.ResumeLearningKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class IncompleteLearningScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun failedReadExplainsTheStateWithoutClaimingDataLossAndKeepsRetryAndReturn() {
        val actions = mutableListOf<IncompleteLearningAction>()
        composeRule.setContent {
            RlTheme {
                IncompleteLearningScreen(IncompleteLearningUiState(loading = false, loadFailed = true), actions::add)
            }
        }
        composeRule.onNodeWithTag("incomplete-learning-load-failed").assertIsDisplayed()
        composeRule.onNodeWithText("学习进度暂时无法读取").assertIsDisplayed()
        composeRule.onNodeWithText("请重试读取；如果仍未成功，可以返回首页后再进入。").assertIsDisplayed()
        composeRule.onNodeWithTag("incomplete-learning-empty").assertDoesNotExist()
        composeRule.onNodeWithText("重置", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("删除", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("incomplete-learning-retry").performClick()
        composeRule.onNodeWithTag("incomplete-learning-return-home").performClick()
        assertEquals(listOf(IncompleteLearningAction.RetryRequested, IncompleteLearningAction.ReturnHomeRequested), actions)
    }

    @Test
    fun successfullyReadEmptyListIsClearlyEmptyNotAnErrorOrResetPrompt() {
        composeRule.setContent {
            RlTheme { IncompleteLearningScreen(IncompleteLearningUiState(loading = false), {}) }
        }
        composeRule.onNodeWithTag("incomplete-learning-empty").assertIsDisplayed()
        composeRule.onNodeWithText("暂无未完成学习").assertIsDisplayed()
        composeRule.onNodeWithTag("incomplete-learning-load-failed").assertDoesNotExist()
        composeRule.onNodeWithTag("incomplete-learning-loading").assertDoesNotExist()
        composeRule.onNodeWithTag("incomplete-learning-retry").assertDoesNotExist()
        composeRule.onNodeWithText("重置", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("删除", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("incomplete-learning-return-home").assertIsDisplayed()
    }

    @Test
    fun rendersThreeIndependentProgressCardsAndDispatchesResumeAndHome() {
        val actions = mutableListOf<IncompleteLearningAction>()
        val entries = listOf(
            IncompleteLearningEntry("learning-1", OrdinarySourceType.DIARY, "9 月 2 日日记", 2, "LR-06", "整句会说", ResumeLearningKind.ORDINARY),
            IncompleteLearningEntry("learning-2", OrdinarySourceType.QUICK_RECORDING, "本机录音 · 01:24", 1, "LR-04", "手写抄写", ResumeLearningKind.ORDINARY),
            IncompleteLearningEntry("learning-3", OrdinarySourceType.AI_SCENE, "AI 场景 · 面试前", 3, "LR-08", "整句会说", ResumeLearningKind.ORDINARY),
        )

        composeRule.setContent {
            RlTheme {
                IncompleteLearningScreen(
                    IncompleteLearningUiState(entries = entries, loading = false),
                    actions::add,
                )
            }
        }

        composeRule.onNodeWithTag("incomplete-learning-artwork").assertIsDisplayed()
        composeRule.onAllNodesWithTag("incomplete-learning-card", useUnmergedTree = true)
            .assertCountEquals(3)
        composeRule.onAllNodesWithTag("incomplete-learning-progress", useUnmergedTree = true)
            .assertCountEquals(3)
        composeRule.onNodeWithTag("incomplete-learning-resume-learning-1").performClick()
        composeRule.onNodeWithTag("incomplete-learning-return-home").performClick()

        assertEquals(
            listOf(
                IncompleteLearningAction.ResumeRequested("learning-1"),
                IncompleteLearningAction.ReturnHomeRequested,
            ),
            actions,
        )
    }
}
