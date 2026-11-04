package com.reallanguage.app.feature.billing.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import org.junit.Before
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.billing.domain.AiChargeRecord
import com.reallanguage.app.feature.billing.domain.AiChargeType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AiBillingScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before fun useProductionWindow() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        composeRule.waitUntil(5_000) { composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test
    fun eachRealAttemptKeepsItsOwnCallTypeAndModelEvenWhenOtherValuesAreIdentical() {
        val labels = mapOf(
            AiChargeType.INITIAL_CALL to "首次调用",
            AiChargeType.AUTOMATIC_RETRY to "自动重试",
            AiChargeType.USER_RETRY to "手动重试",
            AiChargeType.PROVIDER_SWITCH to "供应商切换",
        )
        val charges = labels.keys.mapIndexed { index, type ->
            AiChargeRecord("attempt-$index", 42, 1_788_400_000_000,
                "actual-model-$index", type, "生成英语表达")
        }
        composeRule.setContent { RlTheme { AiBillingScreen(AiBillingUiState(3_680, charges, loading = false)) {} } }
        charges.forEach { charge ->
            val row = hasAnyAncestor(hasTestTag("ai-charge-${charge.chargeId}"))
            for (value in listOf(charge.operationLabelZh, charge.modelSource, "-￥0.42", labels.getValue(charge.callType))) {
                composeRule.onNode(hasText(value) and row, useUnmergedTree = true).assertExists()
            }
            labels.filterKeys { it != charge.callType }.values.forEach { other ->
                composeRule.onNode(hasText(other) and row, useUnmergedTree = true).assertDoesNotExist()
            }
        }
        composeRule.onAllNodesWithText("生成英语表达", useUnmergedTree = true).assertCountEquals(4)
    }

    @Test
    fun rendersActualLedgerValuesAndReturnsToAccount() {
        val actions = mutableListOf<AiBillingAction>()
        val state = AiBillingUiState(
            balanceAmountFen = 3_680,
            charges = listOf(
                AiChargeRecord(
                    chargeId = "charge-1",
                    amountFen = 42,
                    occurredAtEpochMillis = 1_788_400_000_000,
                    modelSource = "OpenAI",
                    callType = AiChargeType.INITIAL_CALL,
                    operationLabelZh = "日记整理",
                ),
            ),
            loading = false,
        )

        composeRule.setContent { RlTheme { AiBillingScreen(state, actions::add) } }

        composeRule.onAllNodesWithText("￥36.80").assertCountEquals(2)
        composeRule.onNodeWithTag("ai-billing-ledger").assertIsDisplayed()
        composeRule.onNodeWithTag("ai-charge-charge-1").assertIsDisplayed()
        composeRule.onNodeWithText("日记整理").assertIsDisplayed()
        composeRule.onNodeWithText("OpenAI").assertIsDisplayed()
        composeRule.onNodeWithText("-￥0.42").assertIsDisplayed()
        composeRule.onNodeWithTag("ledger-back").assertIsDisplayed().performClick()
        assertEquals(listOf(AiBillingAction.ReturnAccountRequested), actions)
    }
}
