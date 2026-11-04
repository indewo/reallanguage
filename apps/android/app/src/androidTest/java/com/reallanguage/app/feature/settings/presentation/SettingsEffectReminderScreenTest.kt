package com.reallanguage.app.feature.settings.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import org.junit.Before
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.settings.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsEffectReminderScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    @Before fun useProductionWindow() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        composeRule.waitUntil(5_000) { composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }
    private val original = InitialSettingsDraft(formalityLevel = 3)
    private fun state() = SettingsEffectScopeUiState(original, original.copy(formalityLevel = 4),
        SettingsSourceContext.FixedContent("isolated-source", "中性测试内容"))

    @Test fun partialSaveShowsSavedAndUnconfirmedSidesAndRetryCannotChangeCommittedScope() {
        val actions = mutableListOf<SettingsEffectScopeAction>()
        composeRule.setContent { RlTheme { SettingsEffectScopeScreen(state().copy(
            error = SettingsEffectScopeError.APPLY_FAILED, saveProgress = SettingsSaveProgress(true, false)), actions::add) } }
        composeRule.onNodeWithTag("settings-effect-save-status").assertIsDisplayed()
            .assertTextEquals("学习设置已保存；每日提醒尚未确认保存，请重试。")
        composeRule.onAllNodesWithTag("settings-effect-option").apply {
            get(0).assertIsNotEnabled(); get(1).assertIsNotEnabled()
        }
        composeRule.onNodeWithTag("settings-effect-confirm").assertTextContains("重试").performClick()
        assertEquals(listOf(SettingsEffectScopeAction.RetryRequested), actions)
    }

    @Test fun busyCardTapAndBackCannotChangePendingChoiceOrCancelSubmission() {
        val current = mutableStateOf(state().copy(applying = true))
        val actions = mutableListOf<SettingsEffectScopeAction>()
        composeRule.setContent { RlTheme { SettingsEffectScopeScreen(current.value, actions::add) } }
        composeRule.onAllNodesWithTag("settings-effect-option")[1].assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag("ledger-back").assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag("settings-effect-cancel").assertIsNotEnabled()
        composeRule.onNodeWithTag("settings-effect-confirm").assertIsNotEnabled()
        assertTrue(actions.isEmpty())
        composeRule.runOnIdle { current.value = state() }
        composeRule.onNodeWithTag("settings-effect-confirm").performClick()
        assertEquals(listOf(SettingsEffectScopeAction.ReprocessRequested), actions)
    }

    @Test fun remoteFailureDoesNotPretendReminderWasSubmittedOrEitherSideSaved() {
        composeRule.setContent { RlTheme { SettingsEffectScopeScreen(state().copy(
            error = SettingsEffectScopeError.APPLY_FAILED, saveProgress = SettingsSaveProgress(false, false)), {}) } }
        composeRule.onNodeWithTag("settings-effect-save-status").assertIsDisplayed()
            .assertTextEquals("学习设置尚未确认保存；每日提醒尚未提交，请重试。")
    }

    @Test fun doubleFontGrowsChoiceCardsWithoutClippingDescriptionsAndKeepsFooterReachable() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                RlTheme { SettingsEffectScopeScreen(state(), {}) }
            }
        }
        val description = composeRule.onNodeWithText("当前流程不变，新设置用于之后的内容", useUnmergedTree = true)
        description.performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        description.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
        val descriptionBounds = description.fetchSemanticsNode().boundsInRoot
        val cardBounds = composeRule.onAllNodesWithTag("settings-effect-option")[1].fetchSemanticsNode().boundsInRoot
        assertTrue("Description must not escape its paper card", descriptionBounds.bottom <= cardBounds.bottom)
        composeRule.onNodeWithTag("settings-effect-cancel").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-effect-confirm").assertIsDisplayed()
    }

    @Test fun returningAfterPartialSaveDoesNotPromiseToCancelAlreadySavedSettings() {
        val actions = mutableListOf<SettingsEffectScopeAction>()
        val current = mutableStateOf(state().copy(error = SettingsEffectScopeError.APPLY_FAILED,
            saveProgress = SettingsSaveProgress(true, false)))
        composeRule.setContent { RlTheme { SettingsEffectScopeScreen(current.value, actions::add) } }
        composeRule.onNodeWithTag("settings-effect-cancel").assertTextEquals("返回").performClick()
        assertEquals(listOf(SettingsEffectScopeAction.CancelRequested), actions)
        composeRule.runOnIdle { current.value = state() }
        composeRule.onNodeWithTag("settings-effect-cancel").assertTextEquals("取消修改")
    }
}
