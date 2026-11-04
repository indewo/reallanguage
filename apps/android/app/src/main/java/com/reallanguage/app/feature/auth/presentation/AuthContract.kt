package com.reallanguage.app.feature.auth.presentation

enum class AuthMode {
    LOGIN,
    REGISTRATION,
}

enum class AuthFieldError {
    INVALID_EMAIL,
    PASSWORD_TOO_SHORT,
    PASSWORD_TOO_LONG,
    PASSWORD_BLOCKED,
    INVALID_BIRTH_YEAR_MONTH,
    AGE_INELIGIBLE,
    TERMS_REQUIRED,
    PRIVACY_POLICY_REQUIRED,
    PASSWORD_REQUIRED,
}

enum class AuthSubmitError {
    INVALID_CREDENTIALS,
    NETWORK,
    SERVICE,
}

data class AuthUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    val email: String = "",
    val password: String = "",
    val birthYearMonth: String = "",
    val acceptedTerms: Boolean = false,
    val acceptedPrivacyPolicy: Boolean = false,
    val emailError: AuthFieldError? = null,
    val passwordError: AuthFieldError? = null,
    val birthYearMonthError: AuthFieldError? = null,
    val termsError: AuthFieldError? = null,
    val privacyPolicyError: AuthFieldError? = null,
    val submitError: AuthSubmitError? = null,
    val submitEnabled: Boolean = false,
    val submitting: Boolean = false,
    val showValidationErrors: Boolean = false,
)

sealed interface AuthAction {
    data class EmailChanged(val value: String) : AuthAction
    data class PasswordChanged(val value: String) : AuthAction
    data class BirthYearMonthChanged(val value: String) : AuthAction
    data class TermsAcceptanceChanged(val accepted: Boolean) : AuthAction
    data class PrivacyAcceptanceChanged(val accepted: Boolean) : AuthAction
    data object SubmitRequested : AuthAction
    data object RegistrationRequested : AuthAction
    data object LoginRequested : AuthAction
    data object ForgotPasswordRequested : AuthAction
    data object TermsRequested : AuthAction
    data object PrivacyPolicyRequested : AuthAction
}

sealed interface AuthEffect {
    data class Authenticated(val settingsCompleted: Boolean) : AuthEffect
    data object OpenAgeIneligible : AuthEffect
    data class OpenEmailVerification(
        val registrationId: String,
        val email: String,
    ) : AuthEffect
    data object OpenForgotPassword : AuthEffect
    data object OpenTerms : AuthEffect
    data object OpenPrivacyPolicy : AuthEffect
}
