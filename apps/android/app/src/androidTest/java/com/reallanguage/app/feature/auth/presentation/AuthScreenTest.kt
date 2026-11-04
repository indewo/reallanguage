package com.reallanguage.app.feature.auth.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import org.junit.Before
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class AuthScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before fun useProductionWindow() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        composeRule.waitUntil(5_000) { composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test
    fun loginShowsOnlyTheConfirmedAccountEntryActions() {
        val actions = mutableListOf<AuthAction>()
        composeRule.setContent {
            RlTheme {
                AuthScreen(
                    state = AuthUiState(mode = AuthMode.LOGIN, submitEnabled = true),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText("欢迎回来").assertIsDisplayed()
        composeRule.onNodeWithTag("login-submit").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("login-create-account").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("忘记密码").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("出生年月").assertDoesNotExist()
        assertEquals(listOf(AuthAction.SubmitRequested, AuthAction.RegistrationRequested, AuthAction.ForgotPasswordRequested), actions)
    }

    @Test
    fun registrationShowsTheConfirmedFieldsAndDisablesInvalidSubmission() {
        composeRule.setContent {
            RlTheme {
                AuthScreen(
                    state = AuthUiState(
                        mode = AuthMode.REGISTRATION,
                        submitEnabled = false,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("邮箱").assertIsDisplayed()
        composeRule.onNodeWithText("密码（至少 4 位）").assertIsDisplayed()
        composeRule.onNodeWithText("出生年月").assertIsDisplayed()
        composeRule.onNodeWithTag("birth-year-picker").performClick()
        composeRule.onNodeWithText("选择出生年月").assertIsDisplayed()
        composeRule.onNodeWithText("用户协议").assertIsDisplayed()
        composeRule.onNodeWithText("隐私政策").assertIsDisplayed()
        composeRule.onNodeWithText("提交注册").assertIsNotEnabled()
        composeRule.onNodeWithText("返回登录").assertIsDisplayed()
    }

    @Test
    fun registrationKeepsTheCompletePrimaryFlowVisibleInLandscape() {
        composeRule.setContent {
            RlTheme {
                AuthScreen(
                    state = AuthUiState(
                        mode = AuthMode.REGISTRATION,
                        email = "learner@example.com",
                        password = "safe-password",
                        birthYearMonth = "1992-09",
                        acceptedTerms = true,
                        acceptedPrivacyPolicy = true,
                        submitEnabled = true,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("创建你的学习档案").assertIsDisplayed()
        composeRule.onNodeWithText("提交注册").assertIsDisplayed()
        composeRule.onNodeWithText("返回登录").assertIsDisplayed()
    }

    @Test
    fun registrationUsesTheApprovedEditorialRowStructure() {
        composeRule.setContent {
            RlTheme {
                AuthScreen(
                    state = AuthUiState(
                        mode = AuthMode.REGISTRATION,
                        email = "learner@example.com",
                        password = "safe-password",
                        birthYearMonth = "1992-09",
                        acceptedTerms = true,
                        acceptedPrivacyPolicy = true,
                        submitEnabled = true,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag("registration-editorial-intro").assertIsDisplayed()
        composeRule.onNodeWithTag("registration-paper-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("registration-email-row").assertIsDisplayed()
        composeRule.onNodeWithTag("registration-password-row").assertIsDisplayed()
        composeRule.onNodeWithTag("registration-birth-row").assertIsDisplayed()
        composeRule.onNodeWithTag("registration-consent-block").assertIsDisplayed()
        composeRule.onNodeWithTag("registration-actions").assertIsDisplayed()
    }
}
