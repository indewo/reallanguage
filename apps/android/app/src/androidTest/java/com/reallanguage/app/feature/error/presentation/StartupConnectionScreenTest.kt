package com.reallanguage.app.feature.error.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.reallanguage.app.core.designsystem.RlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StartupConnectionScreenTest {
    @get:Rule val rule = createComposeRule()
    @Test fun startupRetainsOnlyItsRetryAction() {
        var retries = 0
        rule.setContent { RlTheme { StartupConnectionScreen { retries++ } } }
        rule.onNodeWithText("连接不可用").assertIsDisplayed()
        rule.onNodeWithTag("startup-connection-retry").assertIsDisplayed().performClick()
        rule.onAllNodesWithText("返回").assertCountEquals(0)
        assertEquals(1, retries)
    }
    @Test fun largeFontKeepsActionVisibleAndEntireCopyReachable() {
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                RlTheme { StartupConnectionScreen {} }
            }
        }
        rule.onNodeWithTag("startup-connection-retry").assertIsDisplayed()
        rule.onNodeWithText("当前暂时无法连接服务。\n请检查网络后重新连接。").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("startup-connection-retry").assertIsDisplayed().performClick()
    }
}
