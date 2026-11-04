package com.reallanguage.app.feature.billing.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.billing.domain.PaymentMethod
import com.reallanguage.app.feature.billing.domain.RechargeOption
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RechargeScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersSeparatedRechargeControlsAndDispatchesRealActions() {
        val actions = mutableListOf<RechargeAction>()
        val state = RechargeUiState(
            originId = "billing",
            balanceAmountFen = 3_680,
            recommendedOptions = listOf(
                RechargeOption(1_000, "轻量体验"),
                RechargeOption(3_000, "日常练习"),
                RechargeOption(5_000, "稳定积累"),
                RechargeOption(10_000, "长期使用"),
            ),
            selectedAmountFen = 3_000,
            selectedMethod = PaymentMethod.WECHAT_PAY,
            loading = false,
        )

        composeRule.setContent { RlTheme { RechargeScreen(state, actions::add) } }

        composeRule.onNodeWithTag("recharge-amount-3000").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithText("￥36.80").assertIsDisplayed()
        composeRule.onNodeWithTag("recharge-custom-amount").assertIsDisplayed()
        composeRule.onNodeWithTag("recharge-payment-WECHAT_PAY").assertIsDisplayed()
        composeRule.onNodeWithText("确认充值 ￥30.00").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("ledger-back").assertIsDisplayed().performClick()

        assertEquals(
            listOf(RechargeAction.SubmitRequested, RechargeAction.ReturnRequested),
            actions,
        )
    }
}
