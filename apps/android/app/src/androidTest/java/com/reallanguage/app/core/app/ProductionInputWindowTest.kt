package com.reallanguage.app.core.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.auth.presentation.AuthAction
import com.reallanguage.app.feature.auth.presentation.AuthMode
import com.reallanguage.app.feature.auth.presentation.AuthScreen
import com.reallanguage.app.feature.auth.presentation.AuthUiState
import com.reallanguage.app.feature.auth.presentation.PasswordResetAction
import com.reallanguage.app.feature.auth.presentation.PasswordResetScreen
import com.reallanguage.app.feature.auth.presentation.PasswordResetStep
import com.reallanguage.app.feature.auth.presentation.PasswordResetUiState
import com.reallanguage.app.feature.diary.presentation.ConfirmedDiaryInputValidator
import com.reallanguage.app.feature.diary.presentation.DiaryInputAction
import com.reallanguage.app.feature.diary.presentation.DiaryInputScreen
import com.reallanguage.app.feature.diary.presentation.DiaryInputViewModel
import java.util.UUID
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Actual system IME and Main's window policy; isolated screen inputs, no account/network/storage. */
class ProductionInputWindowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var stage = "setup"
    private var primaryPhase = "not started"
    private var safeDiagnostics: (() -> String)? = null

    @Before fun useTheMainWindowPolicyInLandscape() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    @Test fun loginEmailAndPrimaryActionRemainUsableWithActualIme() = withoutSensitiveDiagnostics {
        val transientPassword = UUID.randomUUID().toString()
        val state = mutableStateOf(AuthUiState(password = transientPassword, submitEnabled = true))
        var submissions = 0
        safeDiagnostics = { loginWindowNumbers(submissions) }
        compose.setContent {
            RlTheme {
                AuthScreen(state.value, { action ->
                    when (action) {
                        is AuthAction.EmailChanged -> state.value = state.value.copy(email = action.value)
                        is AuthAction.PasswordChanged -> state.value = state.value.copy(password = action.value)
                        AuthAction.SubmitRequested -> submissions++
                        else -> Unit
                    }
                })
            }
        }
        stage = "login email input"
        focusAndType(compose.onNode(hasSetTextAction() and hasText("邮箱")), "window-login@example.invalid")
        stage = "login primary action while IME is visible"
        clickPrimaryWithIme(compose.onNodeWithText("登录"))
        compose.runOnIdle {
            assertEquals(1, submissions)
            assertEquals("window-login@example.invalid", state.value.email)
            assertTrue("The in-memory password must remain unchanged", state.value.password == transientPassword)
        }
    }

    @Test fun captureEmptyPasswordLoginWithActualImeForLayoutAudit() = withoutSensitiveDiagnostics {
        val state = mutableStateOf(AuthUiState(submitEnabled = false))
        compose.setContent {
            RlTheme {
                AuthScreen(state.value, { action ->
                    if (action is AuthAction.EmailChanged) {
                        state.value = state.value.copy(email = action.value)
                    }
                })
            }
        }
        stage = "controlled empty-password login screenshot"
        focusAndType(compose.onNode(hasSetTextAction() and hasText("邮箱")), "window-layout@example.invalid")
        compose.runOnIdle {
            assertTrue("No password may be entered or captured", state.value.password.isEmpty())
            assertFalse(state.value.submitEnabled)
            assertEquals("window-layout@example.invalid", state.value.email)
        }
        assertActualIme()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(instrumentation.targetContext.getExternalFilesDir(null), "login-empty-password-ime.png")
                .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun registrationRealEmailAndPasswordFieldsKeepPrimaryActionReachableWithActualIme() = withoutSensitiveDiagnostics {
        val transientPassword = UUID.randomUUID().toString()
        val state = mutableStateOf(AuthUiState(
            mode = AuthMode.REGISTRATION,
            birthYearMonth = "1990-01",
            acceptedTerms = true,
            acceptedPrivacyPolicy = true,
            submitEnabled = true,
        ))
        var submissions = 0
        compose.setContent {
            RlTheme {
                AuthScreen(state.value, { action ->
                    when (action) {
                        is AuthAction.EmailChanged -> state.value = state.value.copy(email = action.value)
                        is AuthAction.PasswordChanged -> state.value = state.value.copy(password = action.value)
                        AuthAction.SubmitRequested -> submissions++
                        else -> Unit
                    }
                })
            }
        }
        stage = "registration email input"
        focusAndType(compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("registration-email-row"))),
            "window-registration@example.invalid")
        stage = "registration protected password input"
        focusAndType(compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("registration-password-row"))),
            transientPassword)
        stage = "registration primary action while IME is visible"
        clickPrimaryWithIme(compose.onNodeWithText("提交注册"))
        compose.runOnIdle {
            assertEquals(1, submissions)
            assertEquals("window-registration@example.invalid", state.value.email)
            assertTrue("The actual protected input must be committed", state.value.password == transientPassword)
            assertTrue(state.value.acceptedTerms && state.value.acceptedPrivacyPolicy)
        }
    }

    @Test fun resetNewPasswordAndPrimaryActionRemainUsableWithActualIme() = withoutSensitiveDiagnostics {
        val transientPassword = UUID.randomUUID().toString()
        val state = mutableStateOf(PasswordResetUiState(
            step = PasswordResetStep.NEW_PASSWORD,
            email = "window-reset@example.invalid",
        ))
        var submissions = 0
        compose.setContent {
            RlTheme {
                PasswordResetScreen(state.value, { action ->
                    when (action) {
                        is PasswordResetAction.PasswordChanged -> state.value = state.value.copy(newPassword = action.value)
                        PasswordResetAction.ResetRequested -> submissions++
                        else -> Unit
                    }
                })
            }
        }
        stage = "reset protected new-password input"
        focusAndType(compose.onNode(hasSetTextAction()), transientPassword)
        stage = "reset primary action while IME is visible"
        clickPrimaryWithIme(compose.onNodeWithText("完成密码重置"))
        compose.runOnIdle {
            assertEquals(1, submissions)
            assertTrue("The new protected input must be committed", state.value.newPassword == transientPassword)
        }
    }

    @Test fun longDiaryCanReturnFromOpenKeyboardAndContinueEditingWithoutLosingInput() = withoutSensitiveDiagnostics {
        val text = (1..8).joinToString("\n") {
            "第${it}段，我把今天会议中的想法认真整理下来，并补充了需要继续讨论的细节，之后准备与同事沟通。"
        }
        lateinit var viewModel: DiaryInputViewModel
        var submissions = 0
        var backRequests = 0
        var continueRequests = 0
        var discardRequests = 0
        compose.runOnUiThread { viewModel = DiaryInputViewModel(ConfirmedDiaryInputValidator) }
        compose.setContent {
            RlTheme {
                DiaryInputScreen(viewModel.uiState.collectAsState().value, { action ->
                    when (action) {
                        DiaryInputAction.SubmitRequested -> submissions++
                        DiaryInputAction.BackRequested -> backRequests++
                        DiaryInputAction.ContinueEditingRequested -> continueRequests++
                        DiaryInputAction.DiscardConfirmed -> discardRequests++
                        else -> Unit
                    }
                    viewModel.onAction(action)
                })
            }
        }
        stage = "long diary input"
        focusAndType(compose.onNodeWithTag("diary-text-input"), text)
        stage = "diary return with IME open"
        assertActualIme()
        val back = compose.onNodeWithContentDescription("返回")
        scrollIfSupported(back)
        back.assertIsDisplayed().assertIsEnabled().performTouchInput { click(center) }
        compose.waitUntil(3_000) { viewModel.uiState.value.showDiscardConfirmation }
        stage = "continue editing in discard dialog"
        val continueEditing = compose.onNodeWithText("继续编辑")
        continueEditing.assertIsDisplayed().assertIsEnabled().performTouchInput { click(center) }
        compose.waitUntil(3_000) { !viewModel.uiState.value.showDiscardConfirmation }
        compose.runOnIdle {
            assertEquals(1, backRequests)
            assertEquals(1, continueRequests)
            assertEquals(0, discardRequests)
            assertEquals(0, submissions)
            assertEquals(text, viewModel.uiState.value.text)
            assertFalse(viewModel.uiState.value.submitting)
        }
        stage = "diary resumes editing and accepts appended input"
        val input = compose.onNodeWithTag("diary-text-input")
        focusAndType(input, " 继续编辑", cursorAt = text.length)
        stage = "diary primary action while IME is visible"
        clickPrimaryWithIme(compose.onNodeWithTag("diary-submit"))
        compose.runOnIdle {
            assertEquals(1, submissions)
            assertEquals(0, discardRequests)
            assertTrue("All original diary paragraphs must survive the dialog", viewModel.uiState.value.text.contains(text))
            assertTrue(viewModel.uiState.value.text.contains("继续编辑"))
        }
    }

    private fun focusAndType(input: SemanticsNodeInteraction, text: String, cursorAt: Int? = null) {
        scrollIfSupported(input)
        input.assertIsDisplayed().performClick()
        compose.waitUntil(8_000) { actualImeVisible() }
        cursorAt?.let { input.performTextInputSelection(TextRange(it)) }
        input.performTextInput(text)
        compose.waitForIdle()
        assertActualIme()
    }

    private fun clickPrimaryWithIme(button: SemanticsNodeInteraction) {
        primaryPhase = "scroll"
        scrollIfSupported(button)
        primaryPhase = "IME visibility"
        assertActualIme()
        primaryPhase = "displayed and enabled"
        button.assertIsDisplayed().assertIsEnabled()
        primaryPhase = "full primary height"
        val bounds = button.fetchSemanticsNode().boundsInRoot
        assertTrue("The primary action must not be a clipped sliver",
            bounds.height >= 40 * compose.activity.resources.displayMetrics.density)
        // A physical touch must reach the real button, not invoke its semantic callback behind IME.
        primaryPhase = "physical touch"
        button.performTouchInput { click(center) }
        compose.waitForIdle()
        primaryPhase = "after physical touch"
    }

    private fun scrollIfSupported(node: SemanticsNodeInteraction) {
        var ancestor = node.fetchSemanticsNode().parent
        while (ancestor != null) {
            if (ancestor.config.contains(SemanticsActions.ScrollBy)) {
                node.performScrollTo()
                return
            }
            ancestor = ancestor.parent
        }
    }

    private fun actualImeVisible(): Boolean =
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun assertActualIme() {
        assertTrue("The system keyboard must stay open during this check", actualImeVisible())
    }

    private fun loginWindowNumbers(submissions: Int): String {
        val nodes = try {
            val matches = compose.onAllNodesWithText("登录").fetchSemanticsNodes()
            "matches=${matches.size}; nodes=" + matches.joinToString("|") { node ->
                val bounds = node.boundsInRoot
                val enabled = !node.config.contains(SemanticsProperties.Disabled)
                val clickable = node.config.contains(SemanticsActions.OnClick)
                "[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}; enabled=$enabled; click=$clickable]"
            }
        } catch (failure: Throwable) {
            "nodesUnavailable=${failure.javaClass.simpleName}"
        }
        val view = compose.activity.window.decorView
        val insets = ViewCompat.getRootWindowInsets(view)
        val visible = android.graphics.Rect()
        val origin = IntArray(2)
        view.getWindowVisibleDisplayFrame(visible)
        view.getLocationOnScreen(origin)
        return "$nodes; actions=$submissions; phase=$primaryPhase; " +
            "window=${view.width}x${view.height}; origin=${origin[0]},${origin[1]}; " +
            "visible=${visible.left},${visible.top},${visible.right},${visible.bottom}; " +
            "ime=${insets?.isVisible(WindowInsetsCompat.Type.ime())}; " +
            "imeBottom=${insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom}; " +
            "density=${compose.activity.resources.displayMetrics.density}; " +
            "softInputMode=${compose.activity.window.attributes.softInputMode}"
    }

    private inline fun withoutSensitiveDiagnostics(block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            // Compose failures can dump EditableText. Never attach them, capture screenshots, or log inputs.
            val numbers = try { safeDiagnostics?.invoke().orEmpty() }
            catch (diagnosticFailure: Throwable) { "diagnosticsUnavailable=${diagnosticFailure.javaClass.simpleName}" }
            throw AssertionError("Production input window failed at $stage (${failure.javaClass.simpleName}); $numbers; input diagnostics omitted")
        }
    }
}
