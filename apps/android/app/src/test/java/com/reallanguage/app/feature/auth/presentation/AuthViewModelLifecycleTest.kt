package com.reallanguage.app.feature.auth.presentation

import androidx.lifecycle.ViewModelStore
import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.auth.domain.CurrentYearMonth
import com.reallanguage.app.feature.auth.domain.EmailSyntaxValidator
import com.reallanguage.app.feature.auth.domain.LoginFormValidator
import com.reallanguage.app.feature.auth.domain.LoginRepository
import com.reallanguage.app.feature.auth.domain.LoginResult
import com.reallanguage.app.feature.auth.domain.RegistrationFormValidator
import com.reallanguage.app.feature.auth.domain.RegistrationRepository
import com.reallanguage.app.feature.auth.domain.RegistrationStartResult
import java.time.YearMonth
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Public actions and real ViewModelStore disposal. Memory-only repository callbacks; this
 * protects UI ownership, not the separate ProductionSessionRepository credential commit. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelLifecycleTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun aLateLoginCannotNavigateAfterOpeningRegistrationOrPasswordReset() = runTest {
        for (leave in listOf(AuthAction.RegistrationRequested, AuthAction.ForgotPasswordRequested)) {
            withFixture { f ->
                begin(f, AuthMode.LOGIN)
                f.model.onAction(leave)
                runCurrent()
                val departed = f.model.uiState.value
                f.login.release()
                runCurrent()
                assertEquals(if (leave == AuthAction.ForgotPasswordRequested)
                    listOf(AuthEffect.OpenForgotPassword) else emptyList<AuthEffect>(), f.effects)
                assertEquals("A late login cannot rewrite the destination's form", departed, f.model.uiState.value)
            }
        }
    }

    @Test fun aLateRegistrationCannotReopenVerificationAfterReturningToLogin() = runTest {
        withFixture { f ->
            begin(f, AuthMode.REGISTRATION)
            f.model.onAction(AuthAction.LoginRequested)
            runCurrent()
            val returned = f.model.uiState.value
            f.registration.release()
            runCurrent()
            assertTrue("The departed registration must not emit OpenEmailVerification", f.effects.isEmpty())
            assertEquals(AuthMode.LOGIN, f.model.uiState.value.mode)
            assertEquals(returned, f.model.uiState.value)
        }
    }

    @Test fun changingModesReleasesTheOldBusyStateAndLateResultsCannotUnlockTheNewRequest() = runTest {
        for (oldMode in AuthMode.entries) {
            withFixture { f ->
                begin(f, oldMode)
                val nextMode = if (oldMode == AuthMode.LOGIN) AuthMode.REGISTRATION else AuthMode.LOGIN
                f.model.onAction(actionFor(nextMode))
                assertFalse("Leaving $oldMode must release its busy state", f.model.uiState.value.submitting)
                fill(f.model, SECOND_EMAIL)
                assertTrue(f.model.uiState.value.submitEnabled)
                f.model.onAction(AuthAction.SubmitRequested)
                runCurrent()
                assertEquals(1, f.gate(nextMode).requests)
                val currentPending = f.model.uiState.value
                assertTrue(currentPending.submitting)

                f.gate(oldMode).release()
                runCurrent()
                assertEquals("An old response cannot finish the new operation", currentPending, f.model.uiState.value)
                assertTrue(f.effects.isEmpty())
                assertEquals(1, f.gate(oldMode).requests)
                assertEquals(1, f.gate(nextMode).requests)

                f.gate(nextMode).release()
                runCurrent()
                assertFalse(f.model.uiState.value.submitting)
                assertEquals(listOf(if (nextMode == AuthMode.LOGIN) AuthEffect.Authenticated(true)
                    else AuthEffect.OpenEmailVerification(REGISTRATION_ID, SECOND_EMAIL)), f.effects)
            }
        }
    }

    @Test fun leavingAnEntryCancelsItsCooperativeRequestWithoutDroppingTheDraft() = runTest {
        for (mode in AuthMode.entries) {
            withFixture(cooperative = true) { f ->
                begin(f, mode)
                f.model.onAction(if (mode == AuthMode.LOGIN) AuthAction.ForgotPasswordRequested else AuthAction.LoginRequested)
                runCurrent()
                assertEquals(1, f.gate(mode).cancellations)
                assertFalse(f.model.uiState.value.submitting)
                assertEquals(FIRST_EMAIL, f.model.uiState.value.email)
                assertEquals(null, f.model.uiState.value.submitError)
                assertEquals(if (mode == AuthMode.LOGIN) listOf(AuthEffect.OpenForgotPassword)
                    else emptyList<AuthEffect>(), f.effects)
            }
        }
    }

    @Test fun clearingTheStoreCancelsCooperativeLoginAndRegistrationWithoutNavigating() = runTest {
        for (mode in AuthMode.entries) {
            withFixture(cooperative = true) { f ->
                begin(f, mode)
                f.store.clear()
                runCurrent()
                assertEquals(1, f.gate(mode).cancellations)
                assertTrue(f.effects.isEmpty())
                assertEquals(null, f.model.uiState.value.submitError)
            }
        }
    }

    @Test fun clearingTheStoreRejectsNonCooperatingLateResultsFromBothOperations() = runTest {
        for (mode in AuthMode.entries) {
            withFixture { f ->
                begin(f, mode)
                f.store.clear()
                runCurrent()
                val disposed = f.model.uiState.value
                f.gate(mode).release()
                runCurrent()
                assertEquals("Disposed $mode state cannot be completed by a late callback", disposed, f.model.uiState.value)
                assertTrue(f.effects.isEmpty())
                assertEquals(1, f.gate(mode).requests)
            }
        }
    }

    private fun TestScope.begin(f: Fixture, mode: AuthMode) {
        f.model.onAction(actionFor(mode))
        fill(f.model, FIRST_EMAIL)
        assertTrue(f.model.uiState.value.submitEnabled)
        f.model.onAction(AuthAction.SubmitRequested)
        runCurrent()
        assertTrue(f.model.uiState.value.submitting)
        assertEquals(1, f.gate(mode).requests)
    }

    private fun fill(model: AuthViewModel, email: String) {
        model.onAction(AuthAction.EmailChanged(email))
        model.onAction(AuthAction.PasswordChanged("ISOLATED_FORM_PASSWORD"))
        if (model.uiState.value.mode == AuthMode.REGISTRATION) {
            model.onAction(AuthAction.BirthYearMonthChanged("2000-01"))
            model.onAction(AuthAction.TermsAcceptanceChanged(true))
            model.onAction(AuthAction.PrivacyAcceptanceChanged(true))
        }
    }

    private fun actionFor(mode: AuthMode) = if (mode == AuthMode.LOGIN) AuthAction.LoginRequested else AuthAction.RegistrationRequested
    private suspend fun TestScope.withFixture(cooperative: Boolean = false, assertions: suspend (Fixture) -> Unit) {
        val f = Fixture(cooperative)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.model.effects.toList(f.effects) }
        try { assertions(f) } finally {
            f.store.clear(); f.login.release(); f.registration.release(); runCurrent()
        }
    }

    private class Gate<T>(private val result: T, private val cooperative: Boolean) {
        private val reply = CompletableDeferred<T>()
        private var callback: Continuation<T>? = null
        var requests = 0
        var cancellations = 0
        suspend fun request(): T {
            requests++
            return try {
                if (cooperative) reply.await() else suspendCoroutine { callback = it }
            } catch (cancelled: CancellationException) { cancellations++; throw cancelled }
        }
        fun release() { reply.complete(result); callback?.also { callback = null }?.resume(result) }
    }
    private class Fixture(cooperative: Boolean) {
        val login = Gate<LoginResult>(LoginResult.Authenticated("isolated-owner", true), cooperative)
        val registration = Gate<RegistrationStartResult>(RegistrationStartResult.EmailVerificationRequired(REGISTRATION_ID), cooperative)
        val effects = mutableListOf<AuthEffect>()
        val store = ViewModelStore()
        private val emailValidator = EmailSyntaxValidator { it.endsWith("@example.invalid") }
        val model = AuthViewModel(
            RegistrationFormValidator(CurrentYearMonth { YearMonth.of(2026, 9) }, emailValidator),
            RegistrationRepository { registration.request() },
            LoginFormValidator(emailValidator), LoginRepository { login.request() },
        ).also { store.put("isolated-auth-entry", it) }
        fun gate(mode: AuthMode): Gate<*> = if (mode == AuthMode.LOGIN) login else registration
    }
    private companion object {
        const val FIRST_EMAIL = "auth-lifecycle-one@example.invalid"
        const val SECOND_EMAIL = "auth-lifecycle-two@example.invalid"
        const val REGISTRATION_ID = "isolated-new-registration"
    }
}
