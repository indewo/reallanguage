package com.reallanguage.app.feature.billing.presentation

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.reallanguage.app.core.designsystem.RlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BillingEditorialLayoutTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun scrollingLedgerNeverMovesItsHeadingOrBackControl() {
        var backCount = 0
        composeRule.setContent {
            RlTheme { BillingEditorialPage("AI 费用与记录", { backCount++ }) {
                repeat(40) { Text("费用明细 $it") }
                BillingAction("查看记录", {}, Modifier.testTag("ledger-test-action"))
            } }
        }
        val heading = composeRule.onNodeWithTag("ledger-fixed-heading").fetchSemanticsNode().boundsInRoot
        val back = composeRule.onNodeWithTag("ledger-back").fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("ledger-scroll-content").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        composeRule.waitForIdle()
        assertEquals(heading, composeRule.onNodeWithTag("ledger-fixed-heading").fetchSemanticsNode().boundsInRoot)
        assertEquals(back, composeRule.onNodeWithTag("ledger-back").fetchSemanticsNode().boundsInRoot)
        composeRule.onNodeWithTag("ledger-test-action").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("ledger-back").assertIsDisplayed().performClick()
        assertEquals(1, backCount)
    }
}
