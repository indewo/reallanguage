package com.reallanguage.app.feature.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.auth.domain.PasswordRejectionReason
import com.reallanguage.app.feature.auth.domain.LoginCommand
import com.reallanguage.app.feature.auth.domain.LoginFailureReason
import com.reallanguage.app.feature.auth.domain.LoginFormValidator
import com.reallanguage.app.feature.auth.domain.LoginRepository
import com.reallanguage.app.feature.auth.domain.LoginResult
import com.reallanguage.app.feature.auth.domain.RegistrationCommand
import com.reallanguage.app.feature.auth.domain.RegistrationFieldError
import com.reallanguage.app.feature.auth.domain.RegistrationFailureReason
import com.reallanguage.app.feature.auth.domain.RegistrationFormInput
import com.reallanguage.app.feature.auth.domain.RegistrationFormValidation
import com.reallanguage.app.feature.auth.domain.RegistrationFormValidator
import com.reallanguage.app.feature.auth.domain.RegistrationRepository
import com.reallanguage.app.feature.auth.domain.RegistrationStartResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AuthViewModel(
    private val validator: RegistrationFormValidator,
    private val registrationRepository: RegistrationRepository,
    private val loginValidator: LoginFormValidator,
    private val loginRepository: LoginRepository,
) : ViewModel() {
    private var departed = false
    private var requestGeneration = 0L
    private var requestJob: Job? = null
    private val mutableUiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = mutableUiState.asStateFlow()

    private val effectChannel = Channel<AuthEffect>(capacity = Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    fun onAction(action: AuthAction) {
        if (departed) return
        when (action) {
            is AuthAction.EmailChanged -> updateRegistrationForm { copy(email = action.value) }
            is AuthAction.PasswordChanged -> updateRegistrationForm { copy(password = action.value) }
            is AuthAction.BirthYearMonthChanged ->
                updateRegistrationForm { copy(birthYearMonth = action.value) }
            is AuthAction.TermsAcceptanceChanged ->
                updateRegistrationForm {
                    copy(
                        acceptedTerms = action.accepted,
                        showValidationErrors = showValidationErrors || !action.accepted,
                    )
                }
            is AuthAction.PrivacyAcceptanceChanged ->
                updateRegistrationForm {
                    copy(
                        acceptedPrivacyPolicy = action.accepted,
                        showValidationErrors = showValidationErrors || !action.accepted,
                    )
                }
            AuthAction.SubmitRequested -> {
                if (mutableUiState.value.mode == AuthMode.LOGIN) submitLogin() else submitRegistration()
            }
            AuthAction.RegistrationRequested -> switchToRegistration()
            AuthAction.LoginRequested -> {
                cancelSubmission()
                mutableUiState.update {
                validatedLoginState(it.copy(
                    mode = AuthMode.LOGIN,
                    submitError = null,
                    emailError = null,
                    passwordError = null,
                    birthYearMonthError = null,
                    termsError = null,
                    privacyPolicyError = null,
                ))
                }
            }
            AuthAction.ForgotPasswordRequested -> { cancelSubmission(); emit(AuthEffect.OpenForgotPassword) }
            AuthAction.TermsRequested -> { cancelSubmission(); emit(AuthEffect.OpenTerms) }
            AuthAction.PrivacyPolicyRequested -> { cancelSubmission(); emit(AuthEffect.OpenPrivacyPolicy) }
        }
    }

    private fun switchToRegistration() {
        cancelSubmission()
        mutableUiState.update { current ->
            validatedState(
                current.copy(
                    mode = AuthMode.REGISTRATION,
                    submitError = null,
                    showValidationErrors = false,
                ),
            )
        }
    }

    private fun updateRegistrationForm(change: AuthUiState.() -> AuthUiState) {
        if (mutableUiState.value.submitting) return
        mutableUiState.update { current ->
            val changed = current.change().copy(submitError = null)
            if (changed.mode == AuthMode.REGISTRATION) validatedState(changed) else validatedLoginState(changed)
        }
    }

    private fun submitLogin() {
        val current = mutableUiState.value
        if (current.mode != AuthMode.LOGIN || current.submitting) return
        val validation = loginValidator.validate(current.email, current.password)
        mutableUiState.value = validatedLoginState(current, validation)
        if (!validation.isValid) return
        mutableUiState.update { it.copy(submitting = true, submitEnabled = false, submitError = null) }
        launchSubmission { generation ->
            val result = try {
                loginRepository.login(LoginCommand(validation.normalizedEmail, current.password))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                LoginResult.Failed(LoginFailureReason.SERVICE)
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrent(generation)) return@launchSubmission
            when (result) {
                is LoginResult.Authenticated -> {
                    mutableUiState.update { validatedLoginState(it.copy(submitting = false)) }
                    effectChannel.send(AuthEffect.Authenticated(result.settingsCompleted))
                }
                LoginResult.InvalidCredentials -> mutableUiState.update {
                    validatedLoginState(it.copy(submitting = false)).copy(
                        submitError = AuthSubmitError.INVALID_CREDENTIALS,
                    )
                }
                is LoginResult.Failed -> mutableUiState.update {
                    validatedLoginState(it.copy(submitting = false)).copy(
                        submitError = when (result.reason) {
                            LoginFailureReason.NETWORK -> AuthSubmitError.NETWORK
                            LoginFailureReason.SERVICE -> AuthSubmitError.SERVICE
                        },
                    )
                }
            }
        }
    }

    private fun submitRegistration() {
        val current = mutableUiState.value.copy(showValidationErrors = true)
        if (current.mode != AuthMode.REGISTRATION || current.submitting) return

        val validation = validate(current)
        mutableUiState.value = validatedState(current, validation)
        if (validation.errors == setOf(RegistrationFieldError.AGE_INELIGIBLE)) {
            emit(AuthEffect.OpenAgeIneligible)
            return
        }
        if (!validation.isValid) return

        val normalizedPassword = checkNotNull(validation.normalizedPassword)
        mutableUiState.update { it.copy(submitting = true, submitEnabled = false, submitError = null) }
        launchSubmission { generation ->
            val result = try {
                registrationRepository.startRegistration(
                    RegistrationCommand(
                        email = current.email,
                        password = normalizedPassword,
                        birthYearMonth = current.birthYearMonth,
                        acceptedTerms = current.acceptedTerms,
                        acceptedPrivacyPolicy = current.acceptedPrivacyPolicy,
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                RegistrationStartResult.Failed(
                    reason = RegistrationFailureReason.SERVICE,
                    retryable = false,
                )
            }
            currentCoroutineContext().ensureActive()
            if (isCurrent(generation)) handleRegistrationResult(result, current.email)
        }
    }

    private suspend fun handleRegistrationResult(result: RegistrationStartResult, email: String) {
        when (result) {
            RegistrationStartResult.AgeIneligible -> {
                finishSubmission()
                effectChannel.send(AuthEffect.OpenAgeIneligible)
            }
            is RegistrationStartResult.EmailVerificationRequired -> {
                finishSubmission()
                effectChannel.send(
                    AuthEffect.OpenEmailVerification(result.registrationId, email),
                )
            }
            is RegistrationStartResult.PasswordRejected -> {
                mutableUiState.update { state ->
                    validatedState(state.copy(submitting = false)).copy(
                        passwordError = when (result.reason) {
                            PasswordRejectionReason.TOO_SHORT -> AuthFieldError.PASSWORD_TOO_SHORT
                            PasswordRejectionReason.TOO_LONG -> AuthFieldError.PASSWORD_TOO_LONG
                            PasswordRejectionReason.BLOCKED -> AuthFieldError.PASSWORD_BLOCKED
                        },
                    )
                }
            }
            is RegistrationStartResult.Failed -> {
                mutableUiState.update { state ->
                    validatedState(state.copy(submitting = false)).copy(
                        submitError = when (result.reason) {
                            RegistrationFailureReason.NETWORK -> AuthSubmitError.NETWORK
                            RegistrationFailureReason.SERVICE -> AuthSubmitError.SERVICE
                        },
                    )
                }
            }
        }
    }

    private fun finishSubmission() {
        mutableUiState.update { validatedState(it.copy(submitting = false)) }
    }

    private fun isCurrent(generation: Long) = !departed && requestGeneration == generation

    private fun launchSubmission(block: suspend (Long) -> Unit) {
        val generation = ++requestGeneration
        requestJob = viewModelScope.launch {
            if (!isCurrent(generation)) return@launch
            try { block(generation) } finally {
                if (isCurrent(generation) && mutableUiState.value.submitting) releaseBusy()
            }
        }
    }

    private fun releaseBusy() {
        mutableUiState.update {
            val ready = it.copy(submitting = false)
            if (ready.mode == AuthMode.LOGIN) validatedLoginState(ready) else validatedState(ready)
        }
    }

    private fun cancelSubmission() {
        requestGeneration++
        requestJob?.cancel()
        requestJob = null
        releaseBusy()
    }

    override fun onCleared() { departed = true; cancelSubmission(); super.onCleared() }

    private fun validatedState(
        state: AuthUiState,
        validation: RegistrationFormValidation = validate(state),
    ): AuthUiState = state.copy(
        emailError = validation.errorFor(
            RegistrationFieldError.INVALID_EMAIL,
            AuthFieldError.INVALID_EMAIL,
        ).takeIf { state.showValidationErrors || state.email.isNotBlank() },
        passwordError = validation.passwordError()
            .takeIf { state.showValidationErrors || state.password.isNotBlank() },
        birthYearMonthError = validation.birthYearMonthError()
            .takeIf { state.showValidationErrors || state.birthYearMonth.isNotBlank() },
        termsError = validation.errorFor(
            RegistrationFieldError.TERMS_REQUIRED,
            AuthFieldError.TERMS_REQUIRED,
        ).takeIf { state.showValidationErrors },
        privacyPolicyError = validation.errorFor(
            RegistrationFieldError.PRIVACY_POLICY_REQUIRED,
            AuthFieldError.PRIVACY_POLICY_REQUIRED,
        ).takeIf { state.showValidationErrors },
        submitEnabled = validation.errors.all {
            it == RegistrationFieldError.AGE_INELIGIBLE
        } && !state.submitting,
    )

    private fun validate(state: AuthUiState) = validator.validate(
        RegistrationFormInput(
            email = state.email,
            password = state.password,
            birthYearMonth = state.birthYearMonth,
            acceptedTerms = state.acceptedTerms,
            acceptedPrivacyPolicy = state.acceptedPrivacyPolicy,
        ),
    )

    private fun validatedLoginState(
        state: AuthUiState,
        validation: com.reallanguage.app.feature.auth.domain.LoginFormValidation =
            loginValidator.validate(state.email, state.password),
    ): AuthUiState = state.copy(
        emailError = AuthFieldError.INVALID_EMAIL.takeUnless { validation.emailValid },
        passwordError = AuthFieldError.PASSWORD_REQUIRED.takeUnless { validation.passwordProvided },
        submitEnabled = validation.isValid && !state.submitting,
    )

    private fun RegistrationFormValidation.errorFor(
        error: RegistrationFieldError,
        uiError: AuthFieldError,
    ): AuthFieldError? = if (error in errors) uiError else null

    private fun RegistrationFormValidation.passwordError(): AuthFieldError? = when {
        RegistrationFieldError.PASSWORD_TOO_SHORT in errors -> AuthFieldError.PASSWORD_TOO_SHORT
        RegistrationFieldError.PASSWORD_TOO_LONG in errors -> AuthFieldError.PASSWORD_TOO_LONG
        else -> null
    }

    private fun RegistrationFormValidation.birthYearMonthError(): AuthFieldError? = when {
        RegistrationFieldError.INVALID_BIRTH_YEAR_MONTH in errors ->
            AuthFieldError.INVALID_BIRTH_YEAR_MONTH
        else -> null
    }

    private fun emit(effect: AuthEffect) {
        effectChannel.trySend(effect)
    }
}
