package com.reallanguage.app.feature.settings.presentation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import org.junit.Before
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft
import com.reallanguage.app.feature.settings.domain.SettingsSourceContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsEffectScopeScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before fun useProductionWindow() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        composeRule.waitUntil(5_000) { composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test
    fun fixedContentShowsOnlyTwoValidScopesAndConfirmsSelection() {
        val actions = mutableListOf<SettingsEffectScopeAction>()
        val original = InitialSettingsDraft(formalityLevel = 2)
        composeRule.setContent {
            RlTheme {
                SettingsEffectScopeScreen(
                    state = SettingsEffectScopeUiState(
                        originalSettings = original,
                        proposedSettings = original.copy(formalityLevel = 3),
                        sourceContext = SettingsSourceContext.FixedContent("source-1", "昨晚的日记"),
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag("settings-effect-guide").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-effect-confirm-note").assertIsDisplayed()
        composeRule.onAllNodesWithTag("settings-effect-option", useUnmergedTree = true)
            .assertCountEquals(2)
        composeRule.onNodeWithTag("settings-effect-confirm").performClick()
        composeRule.onNodeWithTag("settings-effect-cancel").performClick()

        assertEquals(
            listOf(SettingsEffectScopeAction.ReprocessRequested, SettingsEffectScopeAction.CancelRequested),
            actions,
        )
    }
}
