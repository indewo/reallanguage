package com.reallanguage.app.feature.auth.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import org.junit.Before
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RegistrationDestinationScreenTest {
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
    fun ageIneligibleOffersOnlyReturnToLogin() {
        var returnCount = 0
        composeRule.setContent {
            RlTheme {
                AgeIneligibleScreen(onReturnToLogin = { returnCount += 1 })
            }
        }

        composeRule.onNodeWithText("暂时无法注册").assertIsDisplayed()
        composeRule.onNodeWithText("RealLanguage 面向 14 岁及以上用户提供服务。").assertIsDisplayed()
        composeRule.onNodeWithText("你可以返回登录，或在符合年龄要求后再创建账号。").assertIsDisplayed()
        composeRule.onNodeWithTag("age-editorial-intro").assertIsDisplayed()
        composeRule.onNodeWithTag("age-admission-guide").assertIsDisplayed()
        composeRule.onNodeWithTag("age-paper-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("age-return-action").assertIsDisplayed()
        composeRule.onNodeWithText("返回登录").performClick()
        composeRule.onNodeWithText("重新发送").assertDoesNotExist()
        composeRule.onNodeWithText("继续").assertDoesNotExist()
        assertEquals(1, returnCount)
    }

    @Test
    fun waitingVerificationShowsTargetEmailAndOnlyConfirmedActions() {
        val actions = mutableListOf<EmailVerificationAction>()
        composeRule.setContent {
            RlTheme {
                EmailVerificationScreen(
                    state = EmailVerificationUiState(
                        email = "learner@example.com",
                        status = EmailVerificationStatus.WAITING,
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText("验证你的邮箱").assertIsDisplayed()
        composeRule.onNodeWithText("验证邮件已发送，请在邮件中完成验证。").assertIsDisplayed()
        composeRule.onNodeWithTag("email-verification-editorial").assertIsDisplayed()
        composeRule.onNodeWithTag("email-verification-paper-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("email-verification-resend").assertIsDisplayed()
        composeRule.onNodeWithTag("email-verification-change-email").assertIsDisplayed()
        composeRule.onNodeWithText("learner@example.com").assertIsDisplayed()
        composeRule.onNodeWithTag("email-verification-resend").performClick()
        composeRule.onNodeWithTag("email-verification-change-email").performClick()
        composeRule.onNodeWithText("跳过").assertDoesNotExist()
        composeRule.onNodeWithText("进入首页").assertDoesNotExist()
        assertEquals(
            listOf(
                EmailVerificationAction.ResendRequested,
                EmailVerificationAction.ChangeEmailRequested,
            ),
            actions,
        )
    }

    @Test
    fun resendCooldownDisablesResendAndShowsRemainingTime() {
        composeRule.setContent {
            RlTheme {
                EmailVerificationScreen(
                    state = EmailVerificationUiState(
                        email = "learner@example.com",
                        status = EmailVerificationStatus.RESEND_COOLDOWN,
                        resendCooldownSeconds = 28,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag("email-verification-resend").assertIsNotEnabled()
        composeRule.onNodeWithText("28 秒后可重新发送").assertIsDisplayed()
    }

    @Test
    fun verificationNetworkFailureIsVisibleWithoutLosingTheTargetEmail() {
        composeRule.setContent {
            RlTheme {
                EmailVerificationScreen(
                    state = EmailVerificationUiState(
                        email = "learner@example.com",
                        status = EmailVerificationStatus.WAITING,
                        submitError = AuthSubmitError.NETWORK,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("网络连接失败，请稍后重试").assertIsDisplayed()
        composeRule.onNodeWithText("learner@example.com").assertIsDisplayed()
        composeRule.onNodeWithTag("email-verification-resend").assertIsDisplayed()
    }
}
