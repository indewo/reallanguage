package com.reallanguage.app.feature.home.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.home.domain.AccountSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AccountScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersPassportSectionsAndDispatchesEveryAccountAction() {
        val actions = mutableListOf<AccountAction>()
        composeRule.setContent {
            RlTheme {
                AccountScreen(
                    AccountUiState(account = AccountSnapshot("learner@example.com"), loading = false),
                    actions::add,
                )
            }
        }

        composeRule.onNodeWithTag("account-passport-artwork").assertIsDisplayed()
        composeRule.onNodeWithTag("account-info-section").assertIsDisplayed()
        composeRule.onNodeWithTag("account-recordings").performClick()
        composeRule.onNodeWithTag("account-billing").performClick()
        composeRule.onNodeWithTag("account-delete").performScrollTo().performClick()
        composeRule.onNodeWithTag("account-logout").performClick()
        composeRule.onNodeWithTag("account-return-home").performClick()

        assertEquals(
            listOf(
                AccountAction.LocalRecordingsRequested,
                AccountAction.BillingRequested,
                AccountAction.DeleteAccountRequested,
                AccountAction.LogoutRequested,
                AccountAction.ReturnHomeRequested,
            ),
            actions,
        )
    }
}
