package com.reallanguage.app.feature.auth.presentation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import org.junit.Before
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.auth.domain.AgreementDocument
import com.reallanguage.app.feature.auth.domain.AgreementKind
import com.reallanguage.app.feature.auth.domain.RegistrationDraftSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AgreementDetailScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before fun useProductionWindow() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        composeRule.waitUntil(5_000) { composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test
    fun rendersAgreementNavigationAndPreservesRegistrationReturnAction() {
        val actions = mutableListOf<AgreementDetailAction>()
        val state = AgreementDetailUiState(
            kind = AgreementKind.USER_AGREEMENT,
            registrationDraft = RegistrationDraftSnapshot("a@b.com", "secret", "2000-01", true, false),
            document = AgreementDocument(
                AgreementKind.USER_AGREEMENT,
                "RealLanguage 用户协议",
                "v1.26",
                listOf("服务说明正文", "账号与年龄正文", "用户内容正文"),
            ),
            updatedDateZh = "2026 年 9 月 3 日",
            loading = false,
        )

        composeRule.setContent { RlTheme { AgreementDetailScreen(state, actions::add) } }

        composeRule.onNodeWithTag("agreement-toc").assertIsDisplayed()
        state.document!!.sections.forEach { section ->
            composeRule.onNodeWithTag("agreement-section-list").performScrollToNode(hasText(section))
            composeRule.onNodeWithText(section).assertIsDisplayed()
        }
        composeRule.onNodeWithTag("agreement-toc-item-0").performClick()
        composeRule.onNodeWithText("服务说明正文").assertIsDisplayed()
        composeRule.onNodeWithText("更新日期：2026 年 9 月 3 日").assertIsDisplayed()
        composeRule.onNodeWithTag("agreement-return").performClick()

        assertEquals(listOf(AgreementDetailAction.ReturnRequested), actions)
    }
}
