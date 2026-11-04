package com.reallanguage.app.feature.auth.domain

data class RegistrationCommand(
    val email: String,
    val password: String,
    val birthYearMonth: String,
    val acceptedTerms: Boolean,
    val acceptedPrivacyPolicy: Boolean,
)

enum class PasswordRejectionReason {
    TOO_SHORT,
    TOO_LONG,
    BLOCKED,
}

sealed interface RegistrationStartResult {
    data object AgeIneligible : RegistrationStartResult

    data class PasswordRejected(
        val reason: PasswordRejectionReason,
    ) : RegistrationStartResult

    data class EmailVerificationRequired(
        val registrationId: String,
    ) : RegistrationStartResult

    data class Failed(
        val reason: RegistrationFailureReason,
        val retryable: Boolean,
    ) : RegistrationStartResult
}

enum class RegistrationFailureReason {
    NETWORK,
    SERVICE,
}

fun interface RegistrationRepository {
    suspend fun startRegistration(command: RegistrationCommand): RegistrationStartResult
}
