package com.reallanguage.app.feature.auth.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import org.junit.Before
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.reallanguage.app.core.designsystem.RlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PasswordResetScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before fun useProductionWindow() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        composeRule.waitUntil(5_000) { composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test
    fun waitingVerificationMatchesThreeStepResetAndExposesEveryRealAction() {
        val actions = mutableListOf<PasswordResetAction>()
        composeRule.setContent {
            RlTheme {
                PasswordResetScreen(
                    state = PasswordResetUiState(
                        step = PasswordResetStep.WAITING_VERIFICATION,
                        email = "indewo@outlook.com",
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag("password-reset-progress").assertIsDisplayed()
        composeRule.onNodeWithTag("password-reset-visual-panel").assertIsDisplayed()
        composeRule.onNodeWithTag("password-reset-link-expiry-note").assertIsDisplayed()
        composeRule.onNodeWithTag("password-reset-postal-mark").assertIsDisplayed()
        composeRule.onNodeWithText("第 2 步 / 共 3 步").assertIsDisplayed()
        composeRule.onNodeWithText("indewo@outlook.com").assertIsDisplayed()
        composeRule.onNodeWithTag("password-reset-resend").performClick()
        composeRule.onNodeWithTag("password-reset-change-email").performClick()
        composeRule.onNodeWithTag("password-reset-return-login").assertIsDisplayed().performClick()

        assertEquals(
            listOf(
                PasswordResetAction.SendRequested,
                PasswordResetAction.ChangeEmailRequested,
                PasswordResetAction.ReturnToLoginRequested,
            ),
            actions,
        )
    }

    @Test fun emailStateKeepsTheRealInputAndSendAction() {
        val actions = mutableListOf<PasswordResetAction>()
        composeRule.setContent { RlTheme { PasswordResetScreen(PasswordResetUiState(email = "learner@example.com"), actions::add) } }
        composeRule.onNodeWithTag("password-reset-send-email").assertIsDisplayed()
        composeRule.onNodeWithTag("password-reset-email-input").performTextReplacement("other@example.com")
        composeRule.onNodeWithTag("password-reset-send-email").performScrollTo().performClick()
        assertEquals(PasswordResetAction.EmailChanged("other@example.com"), actions.first())
        assertEquals(PasswordResetAction.SendRequested, actions.last())
    }

    @Test fun newPasswordStateDispatchesOnlyExistingPasswordAndResetActions() {
        val actions = mutableListOf<PasswordResetAction>()
        composeRule.setContent { RlTheme { PasswordResetScreen(PasswordResetUiState(step = PasswordResetStep.NEW_PASSWORD), actions::add) } }
        composeRule.onNodeWithTag("password-reset-submit").assertIsDisplayed()
        composeRule.onNodeWithTag("password-reset-new-password-input").performTextReplacement("safe-new-password")
        composeRule.onNodeWithTag("password-reset-submit").performScrollTo().performClick()
        assertEquals(PasswordResetAction.PasswordChanged("safe-new-password"), actions.first())
        assertEquals(PasswordResetAction.ResetRequested, actions.last())
    }

    @Test fun successStatePreservesTheRealCompletionDestinationAction() {
        val actions = mutableListOf<PasswordResetAction>()
        composeRule.setContent { RlTheme { PasswordResetScreen(PasswordResetUiState(step = PasswordResetStep.SUCCESS,
            completionDestination = PasswordResetCompletionDestination.LOGIN), actions::add) } }
        composeRule.onNodeWithText("密码已成功重置。").assertIsDisplayed()
        composeRule.onNodeWithTag("password-reset-complete").assertIsDisplayed().performClick()
        assertEquals(listOf(PasswordResetAction.CompleteRequested), actions)
    }
}
