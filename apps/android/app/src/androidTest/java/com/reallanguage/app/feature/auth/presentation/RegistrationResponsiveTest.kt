package com.reallanguage.app.feature.auth.presentation

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlKeyboardEditingHost
import com.reallanguage.app.core.designsystem.RlTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses the real composable and callbacks; never submits to an external server. */
class RegistrationResponsiveTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(fontScale: Float, state: AuthUiState, action: (AuthAction) -> Unit) {
        rule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                RlTheme { RlKeyboardEditingHost { AuthScreen(state, action) } }
            }
        }
    }

    @Test fun doubleFontCanReachBothAgreementsAndPrimaryActionsWithoutClippedGlyphs() {
        val actions = mutableListOf<AuthAction>()
        show(2f, AuthUiState(mode = AuthMode.REGISTRATION, birthYearMonth = "1992-09", submitEnabled = true), actions::add)
        listOf("用户协议", "隐私政策").forEach { label ->
            val checkbox = rule.onNodeWithTag("auth-consent-$label")
            checkbox.performScrollTo().assertIsDisplayed().performClick()
            val text = rule.onNodeWithText(label, useUnmergedTree = true)
            text.performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            layouts.forEach { layout ->
                for (line in 0 until layout.lineCount) {
                    assertTrue(layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f)
                    assertTrue(layout.getLineBottom(line) <= layout.size.height + 1f)
                }
            }
        }
        rule.onNodeWithTag("registration-submit").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText("返回登录").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf(AuthAction.TermsAcceptanceChanged(true), AuthAction.PrivacyAcceptanceChanged(true),
            AuthAction.SubmitRequested, AuthAction.LoginRequested), actions)
    }

    @Test fun busyRegistrationCannotChangeConsentsOrRevealPassword() {
        val actions = mutableListOf<AuthAction>()
        show(1f, AuthUiState(mode = AuthMode.REGISTRATION, submitting = true, submitEnabled = true), actions::add)
        rule.onNodeWithTag("auth-password-visibility").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("auth-consent-用户协议").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("auth-consent-隐私政策").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("registration-submit").performScrollTo().assertIsNotEnabled()
        assertTrue(actions.isEmpty())
    }

    @Test fun independentPaperFieldRetainsWholeImeReplacementRatherThanAppending() {
        var state by mutableStateOf(AuthUiState(mode = AuthMode.REGISTRATION))
        rule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        rule.setContent {
            RlTheme { RlKeyboardEditingHost {
                AuthScreen(state, { action ->
                    if (action is AuthAction.EmailChanged) state = state.copy(email = action.value)
                })
            } }
        }
        val email = rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("registration-email-row")))
        email.performScrollTo().performTextReplacement("first@example.invalid")
        email.performTextReplacement("changed@example.invalid")
        rule.runOnIdle { assertEquals("changed@example.invalid", state.email) }
    }

    @Test fun visualAgreementLinksStillOpenTheCorrectDocumentAndTargetsStaySeparate() {
        val actions = mutableListOf<AuthAction>()
        show(1f, AuthUiState(mode = AuthMode.REGISTRATION, birthYearMonth = "1992-09"), actions::add)
        val terms = rule.onNodeWithTag("auth-consent-用户协议").getUnclippedBoundsInRoot()
        val privacy = rule.onNodeWithTag("auth-consent-隐私政策").getUnclippedBoundsInRoot()
        assertTrue((terms.right - terms.left).value >= 48f && (terms.bottom - terms.top).value >= 48f)
        assertTrue((privacy.right - privacy.left).value >= 48f && (privacy.bottom - privacy.top).value >= 48f)
        assertTrue("Consent targets must not overlap", terms.bottom <= privacy.top)
        listOf("用户协议", "隐私政策").forEach { label ->
            val link = rule.onNodeWithText(label, useUnmergedTree = true)
            link.performScrollTo().assertIsDisplayed()
            val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
            val visibleLabel = link.fetchSemanticsNode().boundsInRoot.center
            rule.onRoot().performTouchInput { click(visibleLabel - root.topLeft) }
        }
        assertEquals(listOf(AuthAction.TermsRequested, AuthAction.PrivacyPolicyRequested), actions)
    }
}
