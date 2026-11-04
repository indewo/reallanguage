package com.reallanguage.app.feature.safety.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.reallanguage.app.core.designsystem.RlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ManualReviewUnavailableScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun unavailableTargetHasAnExplicitExitWithoutRetryOrStaleLearningActions() {
        val actions = mutableListOf<ManualReviewResultAction>()
        composeRule.setContent { RlTheme { ManualReviewResultScreen(
            ManualReviewResultUiState(outcome = ManualReviewOutcome.APPROVED, summaryZh = "不应显示的旧通过结果", loading = false, unavailable = true), actions::add) } }
        composeRule.onNodeWithTag("manual-review-unavailable").assertIsDisplayed().assertTextEquals("对应内容已不可用。")
        composeRule.onNodeWithTag("manual-review-return-messages").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("重试").assertDoesNotExist()
        composeRule.onNodeWithText("进入全量拆句").assertDoesNotExist()
        composeRule.onNodeWithText("返回安全处理").assertDoesNotExist()
        composeRule.onNodeWithText("不应显示的旧通过结果").assertDoesNotExist()
        assertEquals(listOf(ManualReviewResultAction.ReturnMessagesRequested), actions)
    }

    @Test fun smallViewportAndDoubleFontKeepTheExplicitReturnReachable() {
        val actions = mutableListOf<ManualReviewResultAction>()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                RlTheme { Box(Modifier.size(420.dp, 320.dp)) {
                    ManualReviewResultScreen(ManualReviewResultUiState(loading = false, unavailable = true), actions::add)
                } }
            }
        }
        composeRule.onNodeWithTag("manual-review-return-messages").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(listOf(ManualReviewResultAction.ReturnMessagesRequested), actions)
    }

    @Test fun approvedAndRejectedResultsKeepTheirPrimaryActionsAlongsideReturnMessages() {
        val actions = mutableListOf<ManualReviewResultAction>()
        val outcome = mutableStateOf(ManualReviewOutcome.APPROVED)
        composeRule.setContent { RlTheme { ManualReviewResultScreen(
            ManualReviewResultUiState(outcome = outcome.value, summaryZh = "本次复核摘要", reasonZh = "本次复核说明", loading = false), actions::add) } }
        composeRule.onNodeWithText("进入全量拆句").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag("manual-review-return-messages").assertIsDisplayed().assertTextEquals("返回消息").performClick()
        assertEquals(listOf(ManualReviewResultAction.ReturnMessagesRequested), actions)
        composeRule.runOnIdle { outcome.value = ManualReviewOutcome.NOT_APPROVED }
        composeRule.onNodeWithText("返回安全处理").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag("manual-review-return-messages").assertIsDisplayed().assertTextEquals("返回消息").performClick()
        assertEquals(listOf(ManualReviewResultAction.ReturnMessagesRequested, ManualReviewResultAction.ReturnMessagesRequested), actions)
    }
}
