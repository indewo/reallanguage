package com.reallanguage.app.feature.safety.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import org.junit.Rule
import org.junit.Test

class ManualReviewResultScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun approvedResultCanEnterFullSentenceSplit() {
        val actions = mutableListOf<ManualReviewResultAction>()
        composeRule.setContent {
            RlTheme {
                ManualReviewResultScreen(
                    state = ManualReviewResultUiState(
                        outcome = ManualReviewOutcome.APPROVED,
                        summaryZh = "本次内容已通过人工复核。",
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText("人工复核结果").assertIsDisplayed()
        composeRule.onNodeWithText("本次内容已通过人工复核。").assertIsDisplayed()
        composeRule.onNodeWithText("进入全量拆句").assertIsDisplayed()
        composeRule.onNodeWithText("下一步示意：进入拆句后选择重点句").assertIsDisplayed()
        composeRule.onNodeWithText("返回安全处理").assertDoesNotExist()
        listOf(
            "review-approved-guidance",
            "review-approved-certificate",
            "review-approved-next-step",
        ).forEach { composeRule.onNodeWithTag(it).assertIsDisplayed() }
        composeRule.onNodeWithText("进入全量拆句").performClick()
        assert(actions == listOf(ManualReviewResultAction.OpenFullSentenceSplitRequested))
    }

    @Test
    fun rejectedResultShowsReasonAndReturnsToSafetyHandling() {
        val actions = mutableListOf<ManualReviewResultAction>()
        composeRule.setContent {
            RlTheme {
                ManualReviewResultScreen(
                    state = ManualReviewResultUiState(
                        outcome = ManualReviewOutcome.NOT_APPROVED,
                        summaryZh = "本次内容未通过人工复核。",
                        reasonZh = "内容仍包含需要处理的具体信息。",
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText("本次内容未通过人工复核。").assertIsDisplayed()
        composeRule.onAllNodesWithText("内容仍包含需要处理的具体信息。")[0].assertIsDisplayed()
        composeRule.onNodeWithText("返回安全处理").assertIsDisplayed()
        composeRule.onNodeWithText("进入全量拆句").assertDoesNotExist()
        listOf(
            "review-rejected-guidance",
            "review-rejected-opinion",
            "review-rejected-source",
            "review-rejected-explanation",
        ).forEach { composeRule.onNodeWithTag(it).assertIsDisplayed() }
        composeRule.onNodeWithText("返回安全处理").performClick()
        assert(actions == listOf(ManualReviewResultAction.ReturnToSafetyHandlingRequested))
    }
}
