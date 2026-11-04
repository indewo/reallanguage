package com.reallanguage.app.feature.auth.presentation

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.reallanguage.app.core.designsystem.RlTheme
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class BirthYearMonthDialogTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun confirmUsesTheSelectedRealYearAndMonth() {
        var confirmed: YearMonth? = null
        composeRule.setContent { RlTheme { BirthYearMonthDialog(YearMonth.of(1992, 9), {}, { confirmed = it }) } }
        composeRule.onNodeWithTag("birth-dialog-year").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithText("1991 年").performScrollTo().performClick()
        composeRule.onNodeWithTag("birth-dialog-month").performClick()
        composeRule.onNodeWithText("10 月").performScrollTo().performClick()
        composeRule.onNodeWithTag("birth-dialog-confirm").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(YearMonth.of(1991, 10), confirmed)
    }

    @Test fun cancelNeverCommitsTheDraftSelection() {
        var confirmed: YearMonth? = null
        var dismissals = 0
        composeRule.setContent { RlTheme { BirthYearMonthDialog(YearMonth.of(1992, 9), { dismissals++ }, { confirmed = it }) } }
        composeRule.onNodeWithTag("birth-dialog-month").performClick()
        composeRule.onNodeWithText("10 月").performScrollTo().performClick()
        composeRule.onNodeWithTag("birth-dialog-cancel").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, dismissals)
        assertNull(confirmed)
    }

    @Test fun registrationRetainsSmallGlyphsInsideAccessibleTouchTargets() {
        composeRule.setContent { RlTheme { AuthScreen(AuthUiState(mode = AuthMode.REGISTRATION), {}) } }
        composeRule.onNodeWithTag("auth-password-visibility").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        composeRule.onNodeWithTag("birth-year-picker").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("auth-consent-用户协议").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        composeRule.onNodeWithTag("auth-consent-隐私政策").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }
}
