package com.reallanguage.app.feature.billing.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RefundScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersSeparatedRefundInformationAndDispatchesAvailableActions() {
        val actions = mutableListOf<RefundAction>()
        val state = RefundUiState(
            refundableAmountFen = 3_000,
            refundMethodLabelZh = "微信支付",
            loading = false,
        )

        composeRule.setContent { RlTheme { RefundScreen(state, actions::add) } }

        composeRule.onAllNodesWithText("￥30.00").assertCountEquals(2)
        composeRule.onNodeWithTag("refund-form").assertIsDisplayed()
        composeRule.onNodeWithTag("refund-support").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("refund-submit").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("ledger-back").assertIsDisplayed().performClick()

        assertEquals(
            listOf(
                RefundAction.MinorRechargeSupportRequested,
                RefundAction.SubmitRequested,
                RefundAction.ReturnBillingRequested,
            ),
            actions,
        )
    }
}
