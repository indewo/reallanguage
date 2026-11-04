package com.reallanguage.app.feature.safety.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.safety.domain.SourceType
import org.junit.Rule
import org.junit.Test

class ManualReviewPendingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pendingReviewLocksOnlyThisContentAndAllowsReturningHome() {
        val actions = mutableListOf<ManualReviewPendingAction>()
        composeRule.setContent {
            RlTheme {
                ManualReviewPendingScreen(
                    state = ManualReviewPendingUiState(SourceType.DIARY),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText("人工复核").assertIsDisplayed()
        composeRule.onNodeWithText("已提交，等待结果").assertIsDisplayed()
        composeRule.onNodeWithText("本次日记内容正在等待复核").assertIsDisplayed()
        composeRule.onNodeWithText("只锁定这一份内容，你仍可创建和学习其他内容。").assertIsDisplayed()
        composeRule.onNodeWithText("返回首页").assertIsDisplayed()
        composeRule.onNodeWithText("继续学习本内容").assertDoesNotExist()
        listOf(
            "review-pending-guidance",
            "review-pending-dossier",
            "review-pending-certificate",
            "review-pending-notice",
            "review-pending-ticket",
        ).forEach { composeRule.onNodeWithTag(it).assertIsDisplayed() }

        composeRule.onNodeWithText("返回首页").performClick()
        assert(actions == listOf(ManualReviewPendingAction.ReturnHomeRequested))
    }
}
