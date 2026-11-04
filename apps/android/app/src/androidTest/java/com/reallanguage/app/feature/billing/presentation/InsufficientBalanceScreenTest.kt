package com.reallanguage.app.feature.billing.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.billing.domain.BalanceInsufficientContext
import com.reallanguage.app.feature.billing.domain.BalanceOriginState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class InsufficientBalanceScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun separatesLedgerAndPausedOperationAndDispatchesBothChoices() {
        val actions = mutableListOf<InsufficientBalanceAction>()
        val state = InsufficientBalanceUiState(
            context = BalanceInsufficientContext("scene-1", BalanceOriginState.UNFIXED_DIARY_OR_AI_SCENE),
            balanceAmountFen = 18,
        )

        composeRule.setContent { RlTheme { InsufficientBalanceScreen(state, actions::add) } }

        composeRule.onNodeWithTag("insufficient-balance-ledger").assertIsDisplayed()
        composeRule.onNodeWithTag("insufficient-balance-paused-operation").assertIsDisplayed()
        composeRule.onNodeWithText("￥0.18").assertIsDisplayed()
        composeRule.onNodeWithText("场景生成").assertIsDisplayed()
        composeRule.onNodeWithTag("insufficient-balance-recharge").performClick()
        composeRule.onNodeWithTag("insufficient-balance-leave").performClick()

        assertEquals(
            listOf(InsufficientBalanceAction.RechargeRequested, InsufficientBalanceAction.LeaveRequested),
            actions,
        )
    }
}
