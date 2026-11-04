package com.reallanguage.app.feature.settings.presentation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.settings.domain.*
import org.junit.Rule
import org.junit.Test

class SettingsConflictScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private val original = InitialSettingsDraft(formalityLevel = 3, speechSpeedLevel = 3)
    private val message = "设置已在其他位置更改，本次草稿尚未保存。请返回首页后重新进入设置，读取最新值再修改。"
    @Test fun immediateConflictExplainsTheReloadExitAndCannotOfferBlindResubmission() {
        composeRule.setContent { RlTheme { SubsequentSettingsScreen(SubsequentSettingsUiState(
            original = original, draft = original.copy(speechSpeedLevel = 4), loading = false,
            error = SubsequentSettingsError.VERSION_CONFLICT), {}) } }
        composeRule.onNodeWithTag("settings-save-status").assertIsDisplayed().assertTextEquals(message)
        composeRule.onNodeWithTag("subsequent-settings-save").assertIsNotEnabled()
        composeRule.onNodeWithTag("subsequent-settings-return").assertIsDisplayed().assertIsEnabled()
    }
    @Test fun scopeConflictKeepsTheReturnExitWithoutClaimingAnythingWasSaved() {
        composeRule.setContent { RlTheme { SettingsEffectScopeScreen(SettingsEffectScopeUiState(original,
            original.copy(formalityLevel = 4), SettingsSourceContext.NoCurrentContent,
            error = SettingsEffectScopeError.VERSION_CONFLICT, saveProgress = SettingsSaveProgress(false, false)), {}) } }
        composeRule.onNodeWithTag("settings-effect-save-status").assertIsDisplayed().assertTextEquals(message)
        composeRule.onNodeWithTag("settings-effect-confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("settings-effect-cancel").assertTextEquals("返回").assertIsEnabled()
    }
}
