package com.reallanguage.app.feature.home.presentation

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.home.domain.AppMessage
import com.reallanguage.app.feature.home.domain.AppMessageTarget
import com.reallanguage.app.feature.home.domain.MessageKind
import com.reallanguage.app.feature.home.domain.MessageTargetState
import org.junit.Rule
import org.junit.Test

/** Exact production decision summaries from KyselyAdminReviewStore, not inferred from keywords. */
class MessagesStatusTruthTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun productionApprovedSummaryShowsApproved() {
        showsStatus("本次内容已通过人工复核。", MessageKind.MANUAL_REVIEW_RESULT, "已通过")
    }

    @Test fun productionFurtherDeidentificationSummaryCannotShowReadyToLearn() {
        showsStatus("本次内容需要进一步脱敏处理。", MessageKind.MANUAL_REVIEW_RESULT, "需修改")
    }

    @Test fun productionKeptBlockedSummaryCannotShowReadyToLearn() {
        showsStatus("本次内容维持安全拦截。", MessageKind.MANUAL_REVIEW_RESULT, "需修改")
    }

    @Test fun completedManualReviewWithoutDecisionCannotClaimApproved() {
        showsStatus("人工复核已完成", MessageKind.MANUAL_REVIEW_RESULT, "去查看")
    }

    @Test fun unknownManualReviewSummaryCannotClaimLearningEligibility() {
        showsStatus("结果更新，请查看详情。", MessageKind.MANUAL_REVIEW_RESULT, "去查看")
    }

    @Test fun genericSystemSummaryContainingReviewCannotClaimApproval() {
        showsStatus("系统审核记录更新", MessageKind.SYSTEM, "去查看")
    }

    @Test fun unknownSystemNoticeMentioningRejectionIsNotItselfARejectedDecision() {
        showsStatus("系统通知：人工审核未通过的说明已更新", MessageKind.SYSTEM, "去查看")
    }

    @Test fun exactExistingLearningReadyDesignLabelRemainsReadyToLearn() {
        showsStatus("学习内容已生成", MessageKind.SYSTEM, "可学习")
    }

    private fun showsStatus(summary: String, kind: MessageKind, expected: String) {
        val message = AppMessage("isolated-status-message", kind, summary, "隔离状态验证", 1L, false,
            AppMessageTarget("manual-review", "isolated-status-review"), MessageTargetState.AVAILABLE)
        composeRule.setContent { RlTheme { MessagesScreen(MessagesUiState(listOf(message), loading = false)) {} } }
        composeRule.onNodeWithText(summary).assertExists()
        composeRule.onNodeWithText(expected, substring = false).assertExists()
        listOf("已通过", "需修改", "去查看", "可学习").filterNot { it == expected }.forEach {
            composeRule.onNodeWithText(it, substring = false).assertDoesNotExist()
        }
    }
}
