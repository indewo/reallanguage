package com.reallanguage.app.feature.auth.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.auth.domain.CurrentYearMonth
import com.reallanguage.app.feature.auth.domain.EmailSyntaxValidator
import com.reallanguage.app.feature.auth.domain.LoginCommand
import com.reallanguage.app.feature.auth.domain.LoginFailureReason
import com.reallanguage.app.feature.auth.domain.LoginFormValidator
import com.reallanguage.app.feature.auth.domain.LoginRepository
import com.reallanguage.app.feature.auth.domain.LoginResult
import com.reallanguage.app.feature.auth.domain.PasswordRejectionReason
import com.reallanguage.app.feature.auth.domain.RegistrationCommand
import com.reallanguage.app.feature.auth.domain.RegistrationFormValidator
import com.reallanguage.app.feature.auth.domain.RegistrationFailureReason
import com.reallanguage.app.feature.auth.domain.RegistrationRepository
import com.reallanguage.app.feature.auth.domain.RegistrationStartResult
import java.text.Normalizer
import java.time.YearMonth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `opening registration does not paint untouched fields as errors`() = runTest {
        val viewModel = viewModel()

        viewModel.onAction(AuthAction.RegistrationRequested)

        assertNull(viewModel.uiState.value.emailError)
        assertNull(viewModel.uiState.value.passwordError)
        assertNull(viewModel.uiState.value.birthYearMonthError)
        assertNull(viewModel.uiState.value.termsError)
        assertNull(viewModel.uiState.value.privacyPolicyError)
    }

    @Test
    fun `registration form enables submit only after every confirmed condition is valid`() = runTest {
        val viewModel = viewModel()

        viewModel.onAction(AuthAction.RegistrationRequested)
        fillValidRegistration(viewModel)

        assertEquals(AuthMode.REGISTRATION, viewModel.uiState.value.mode)
        assertTrue(viewModel.uiState.value.submitEnabled)

        viewModel.onAction(AuthAction.PrivacyAcceptanceChanged(false))

        assertFalse(viewModel.uiState.value.submitEnabled)
        assertEquals(AuthFieldError.PRIVACY_POLICY_REQUIRED, viewModel.uiState.value.privacyPolicyError)
    }

    @Test
    fun `valid login submits credentials once and routes by settings status`() = runTest {
        val loginRepository = FakeLoginRepository(
            LoginResult.Authenticated(accountId = "account-1", settingsCompleted = false),
        )
        val viewModel = viewModel(loginRepository = loginRepository)
        viewModel.onAction(AuthAction.EmailChanged("learner@example.com"))
        viewModel.onAction(AuthAction.PasswordChanged("correct-password"))
        assertTrue(viewModel.uiState.value.submitEnabled)
        val effect = async { viewModel.effects.first() }

        viewModel.onAction(AuthAction.SubmitRequested)
        advanceUntilIdle()

        assertEquals(
            listOf(LoginCommand("learner@example.com", "correct-password")),
            loginRepository.commands,
        )
        assertEquals(AuthEffect.Authenticated(settingsCompleted = false), effect.await())
    }

    @Test
    fun `invalid credentials stay on login with one neutral error`() = runTest {
        val viewModel = viewModel(loginRepository = FakeLoginRepository(LoginResult.InvalidCredentials))
        viewModel.onAction(AuthAction.EmailChanged("learner@example.com"))
        viewModel.onAction(AuthAction.PasswordChanged("wrong-password"))
        viewModel.onAction(AuthAction.SubmitRequested)
        advanceUntilIdle()
        assertEquals(AuthSubmitError.INVALID_CREDENTIALS, viewModel.uiState.value.submitError)
        assertEquals("learner@example.com", viewModel.uiState.value.email)
        assertFalse(viewModel.uiState.value.submitting)
    }

    @Test
    fun `login network and service failures remain retryable without clearing input`() = runTest {
        listOf(
            LoginFailureReason.NETWORK to AuthSubmitError.NETWORK,
            LoginFailureReason.SERVICE to AuthSubmitError.SERVICE,
        ).forEach { (reason, expected) ->
            val viewModel = viewModel(
                loginRepository = FakeLoginRepository(LoginResult.Failed(reason)),
            )
            viewModel.onAction(AuthAction.EmailChanged("learner@example.com"))
            viewModel.onAction(AuthAction.PasswordChanged("correct-password"))
            viewModel.onAction(AuthAction.SubmitRequested)
            advanceUntilIdle()
            assertEquals(expected, viewModel.uiState.value.submitError)
            assertEquals("correct-password", viewModel.uiState.value.password)
        }
    }

    @Test
    fun `unexpected login repository exception becomes visible service failure`() = runTest {
        val viewModel = viewModel(
            loginRepository = LoginRepository { throw IllegalStateException("adapter failed") },
        )
        viewModel.onAction(AuthAction.EmailChanged("learner@example.com"))
        viewModel.onAction(AuthAction.PasswordChanged("correct-password"))

        viewModel.onAction(AuthAction.SubmitRequested)
        advanceUntilIdle()

        assertEquals(AuthSubmitError.SERVICE, viewModel.uiState.value.submitError)
        assertFalse(viewModel.uiState.value.submitting)
    }

    @Test
    fun `submitting login prevents duplicate requests`() = runTest {
        val pending = CompletableDeferred<LoginResult>()
        val repository = FakeLoginRepository(pendingResult = pending)
        val viewModel = viewModel(loginRepository = repository)
        viewModel.onAction(AuthAction.EmailChanged("learner@example.com"))
        viewModel.onAction(AuthAction.PasswordChanged("correct-password"))
        viewModel.onAction(AuthAction.SubmitRequested)
        advanceUntilIdle()
        viewModel.onAction(AuthAction.SubmitRequested)
        assertEquals(1, repository.commands.size)
        pending.complete(LoginResult.Authenticated("account-1", true))
        advanceUntilIdle()
    }

    @Test
    fun `invalid registration never reaches repository and exposes field errors`() = runTest {
        val repository = FakeRegistrationRepository()
        val viewModel = viewModel(repository)
        viewModel.onAction(AuthAction.RegistrationRequested)

        viewModel.onAction(AuthAction.SubmitRequested)
        advanceUntilIdle()

        assertTrue(repository.commands.isEmpty())
        assertEquals(AuthFieldError.INVALID_EMAIL, viewModel.uiState.value.emailError)
        assertEquals(AuthFieldError.PASSWORD_TOO_SHORT, viewModel.uiState.value.passwordError)
        assertEquals(AuthFieldError.INVALID_BIRTH_YEAR_MONTH, viewModel.uiState.value.birthYearMonthError)
    }

    @Test
    fun `agreement errors stay visible after the user rechecks both agreements`() = runTest {
        val viewModel = viewModel()
        viewModel.onAction(AuthAction.RegistrationRequested)
        fillValidRegistration(viewModel)

        viewModel.onAction(AuthAction.TermsAcceptanceChanged(false))
        viewModel.onAction(AuthAction.TermsAcceptanceChanged(true))
        viewModel.onAction(AuthAction.PrivacyAcceptanceChanged(false))
        viewModel.onAction(AuthAction.PrivacyAcceptanceChanged(true))

        assertTrue(viewModel.uiState.value.showValidationErrors)
        assertNull(viewModel.uiState.value.termsError)
        assertNull(viewModel.uiState.value.privacyPolicyError)
        assertTrue(viewModel.uiState.value.submitEnabled)
    }

    @Test
    fun `overlong local password is mapped before registration submission`() = runTest {
        val repository = FakeRegistrationRepository()
        val viewModel = viewModel(repository)
        viewModel.onAction(AuthAction.RegistrationRequested)
        fillValidRegistration(viewModel, password = "a".repeat(73))

        viewModel.onAction(AuthAction.SubmitRequested)
        advanceUntilIdle()

        assertEquals(AuthFieldError.PASSWORD_TOO_LONG, viewModel.uiState.value.passwordError)
        assertTrue(repository.commands.isEmpty())
    }

    @Test
    fun `valid registration submits the normalized password once`() = runTest {
        val repository = FakeRegistrationRepository(
            result = RegistrationStartResult.EmailVerificationRequired("registration-1"),
        )
        val viewModel = viewModel(repository)
        viewModel.onAction(AuthAction.RegistrationRequested)
        fillValidRegistration(viewModel, password = "e\u0301abcdefghijklmn")

        viewModel.onAction(AuthAction.SubmitRequested)
        advanceUntilIdle()

        assertEquals(1, repository.commands.size)
        assertEquals(
            Normalizer.normalize("e\u0301abcdefghijklmn", Normalizer.Form.NFC),
            repository.commands.single().password,
        )
        assertFalse(viewModel.uiState.value.submitting)
    }

    @Test
    fun `submitting state prevents duplicate registration requests`() = runTest {
        val pendingResult = CompletableDeferred<RegistrationStartResult>()
        val repository = FakeRegistrationRepository(pendingResult = pendingResult)
        val viewModel = viewModel(repository)
        viewModel.onAction(AuthAction.RegistrationRequested)
        fillValidRegistration(viewModel)

        viewModel.onAction(AuthAction.SubmitRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.submitting)

        viewModel.onAction(AuthAction.SubmitRequested)
        advanceUntilIdle()
        assertEquals(1, repository.commands.size)

        pendingResult.complete(RegistrationStartResult.EmailVerificationRequired("registration-1"))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.submitting)
    }

    @Test
    fun `age rejection opens only the ineligible destination`() = runTest {
        val viewModel = viewModel(
            FakeRegistrationRepository(result = RegistrationStartResult.AgeIneligible),
        )
        viewModel.onAction(AuthAction.RegistrationRequested)
        fillValidRegistration(viewModel)
        val effect = async { viewModel.effects.first() }

        viewModel.onAction(AuthAction.SubmitRequested)
        advanceUntilIdle()

        assertEquals(AuthEffect.OpenAgeIneligible, effect.await())
        assertFalse(viewModel.uiState.value.submitting)
    }

    @Test
    fun `local underage check opens ineligible destination without contacting server`() = runTest {
        val repository = FakeRegistrationRepository()
        val viewModel = viewModel(repository)
        viewModel.onAction(AuthAction.RegistrationRequested)
        fillValidRegistration(viewModel)
        viewModel.onAction(AuthAction.BirthYearMonthChanged("2012-10"))
        val effect = async { viewModel.effects.first() }

        assertTrue(viewModel.uiState.value.submitEnabled)
        viewModel.onAction(AuthAction.SubmitRequested)
        advanceUntilIdle()

        assertEquals(AuthEffect.OpenAgeIneligible, effect.await())
        assertTrue(repository.commands.isEmpty())
    }

    @Test
    fun `eligible registration opens email verification with server id and entered email`() = runTest {
        val viewModel = viewModel(
            FakeRegistrationRepository(
                result = RegistrationStartResult.EmailVerificationRequired("registration-1"),
            ),
        )
        viewModel.onAction(AuthAction.RegistrationRequested)
        fillValidRegistration(viewModel)
        val effect = async { viewModel.effects.first() }

        viewModel.onAction(AuthAction.SubmitRequested)
        advanceUntilIdle()

        assertEquals(
            AuthEffect.OpenEmailVerification("registration-1", "learner@example.com"),
            effect.await(),
        )
    }

    @Test
    fun `server password rejection remains on registration and marks password`() = runTest {
        val viewModel = viewModel(
            FakeRegistrationRepository(
                result = RegistrationStartResult.PasswordRejected(PasswordRejectionReason.BLOCKED),
            ),
        )
        viewModel.onAction(AuthAction.RegistrationRequested)
        fillValidRegistration(viewModel)

        viewModel.onAction(AuthAction.SubmitRequested)
        advanceUntilIdle()

        assertEquals(AuthFieldError.PASSWORD_BLOCKED, viewModel.uiState.value.passwordError)
        assertNull(viewModel.uiState.value.submitError)
        assertFalse(viewModel.uiState.value.submitting)
    }

    @Test
    fun `repository failure preserves input and exposes retryable failure`() = runTest {
        val viewModel = viewModel(
            FakeRegistrationRepository(
                result = RegistrationStartResult.Failed(
                    reason = RegistrationFailureReason.NETWORK,
                    retryable = true,
                ),
            ),
        )
        viewModel.onAction(AuthAction.RegistrationRequested)
        fillValidRegistration(viewModel)

        viewModel.onAction(AuthAction.SubmitRequested)
        advanceUntilIdle()

        assertEquals("learner@example.com", viewModel.uiState.value.email)
        assertEquals(AuthSubmitError.NETWORK, viewModel.uiState.value.submitError)
        assertTrue(viewModel.uiState.value.submitEnabled)
        assertFalse(viewModel.uiState.value.submitting)
    }

    @Test
    fun `agreement details effect keeps every registration field`() = runTest {
        val viewModel = viewModel()
        viewModel.onAction(AuthAction.RegistrationRequested)
        fillValidRegistration(viewModel)
        val effect = async { viewModel.effects.first() }

        viewModel.onAction(AuthAction.TermsRequested)
        advanceUntilIdle()

        assertEquals(AuthEffect.OpenTerms, effect.await())
        assertEquals("learner@example.com", viewModel.uiState.value.email)
        assertTrue(viewModel.uiState.value.acceptedTerms)
        assertTrue(viewModel.uiState.value.acceptedPrivacyPolicy)
    }

    @Test
    fun `confirmed secondary actions emit only their matching destinations`() = runTest {
        val viewModel = viewModel()
        val effects = async { viewModel.effects.take(3).toList() }

        viewModel.onAction(AuthAction.ForgotPasswordRequested)
        viewModel.onAction(AuthAction.TermsRequested)
        viewModel.onAction(AuthAction.PrivacyPolicyRequested)
        advanceUntilIdle()

        assertEquals(
            listOf(
                AuthEffect.OpenForgotPassword,
                AuthEffect.OpenTerms,
                AuthEffect.OpenPrivacyPolicy,
            ),
            effects.await(),
        )
    }

    @Test
    fun `returning to login uses login validation and never submits registration`() = runTest {
        val repository = FakeRegistrationRepository()
        val viewModel = viewModel(repository)
        viewModel.onAction(AuthAction.RegistrationRequested)
        viewModel.onAction(AuthAction.SubmitRequested)
        assertEquals(AuthFieldError.INVALID_EMAIL, viewModel.uiState.value.emailError)

        viewModel.onAction(AuthAction.LoginRequested)
        viewModel.onAction(AuthAction.EmailChanged("invalid"))
        viewModel.onAction(AuthAction.SubmitRequested)
        advanceUntilIdle()

        assertEquals(AuthMode.LOGIN, viewModel.uiState.value.mode)
        assertEquals(AuthFieldError.INVALID_EMAIL, viewModel.uiState.value.emailError)
        assertFalse(viewModel.uiState.value.submitEnabled)
        assertTrue(repository.commands.isEmpty())
    }

    @Test
    fun `all server password length rejections map to the password field`() = runTest {
        listOf(
            PasswordRejectionReason.TOO_SHORT to AuthFieldError.PASSWORD_TOO_SHORT,
            PasswordRejectionReason.TOO_LONG to AuthFieldError.PASSWORD_TOO_LONG,
        ).forEach { (reason, expectedError) ->
            val viewModel = viewModel(
                FakeRegistrationRepository(
                    result = RegistrationStartResult.PasswordRejected(reason),
                ),
            )
            viewModel.onAction(AuthAction.RegistrationRequested)
            fillValidRegistration(viewModel)

            viewModel.onAction(AuthAction.SubmitRequested)
            advanceUntilIdle()

            assertEquals(expectedError, viewModel.uiState.value.passwordError)
        }
    }

    @Test
    fun `non retryable repository failure exposes service failure`() = runTest {
        val viewModel = viewModel(
            FakeRegistrationRepository(
                result = RegistrationStartResult.Failed(
                    reason = RegistrationFailureReason.SERVICE,
                    retryable = false,
                ),
            ),
        )
        viewModel.onAction(AuthAction.RegistrationRequested)
        fillValidRegistration(viewModel)

        viewModel.onAction(AuthAction.SubmitRequested)
        advanceUntilIdle()

        assertEquals(AuthSubmitError.SERVICE, viewModel.uiState.value.submitError)
    }

    @Test
    fun `unexpected repository exception becomes visible service failure`() = runTest {
        val viewModel = viewModel(
            RegistrationRepository { throw IllegalStateException("adapter failed") },
        )
        viewModel.onAction(AuthAction.RegistrationRequested)
        fillValidRegistration(viewModel)

        viewModel.onAction(AuthAction.SubmitRequested)
        advanceUntilIdle()

        assertEquals(AuthSubmitError.SERVICE, viewModel.uiState.value.submitError)
        assertFalse(viewModel.uiState.value.submitting)
    }

    private fun viewModel(
        repository: RegistrationRepository = FakeRegistrationRepository(),
        loginRepository: LoginRepository = FakeLoginRepository(),
    ) = AuthViewModel(
        validator = RegistrationFormValidator(
            currentYearMonth = CurrentYearMonth { YearMonth.of(2026, 9) },
            emailSyntaxValidator = EmailSyntaxValidator { it == "learner@example.com" },
        ),
        registrationRepository = repository,
        loginValidator = LoginFormValidator(
            EmailSyntaxValidator { it == "learner@example.com" },
        ),
        loginRepository = loginRepository,
    )

    private fun fillValidRegistration(
        viewModel: AuthViewModel,
        password: String = "abcdefghijklmno",
    ) {
        viewModel.onAction(AuthAction.EmailChanged("learner@example.com"))
        viewModel.onAction(AuthAction.PasswordChanged(password))
        viewModel.onAction(AuthAction.BirthYearMonthChanged("2012-09"))
        viewModel.onAction(AuthAction.TermsAcceptanceChanged(true))
        viewModel.onAction(AuthAction.PrivacyAcceptanceChanged(true))
    }
}

private class FakeLoginRepository(
    private val result: LoginResult = LoginResult.Authenticated("account-default", true),
    private val pendingResult: CompletableDeferred<LoginResult>? = null,
) : LoginRepository {
    val commands = mutableListOf<LoginCommand>()
    override suspend fun login(command: LoginCommand): LoginResult {
        commands += command
        return pendingResult?.await() ?: result
    }
}

private class FakeRegistrationRepository(
    private val result: RegistrationStartResult =
        RegistrationStartResult.EmailVerificationRequired("registration-default"),
    private val pendingResult: CompletableDeferred<RegistrationStartResult>? = null,
) : RegistrationRepository {
    val commands = mutableListOf<RegistrationCommand>()

    override suspend fun startRegistration(command: RegistrationCommand): RegistrationStartResult {
        commands += command
        return pendingResult?.await() ?: result
    }
}
