package com.reallanguage.app.feature.auth.domain

sealed interface VerificationEmailResendResult {
    data class Resent(val retryAfterSeconds: Int) : VerificationEmailResendResult
    data class Cooldown(val retryAfterSeconds: Int) : VerificationEmailResendResult
    data object NotAvailable : VerificationEmailResendResult
    data class Failed(
        val reason: RegistrationFailureReason,
        val retryable: Boolean,
    ) : VerificationEmailResendResult
}

sealed interface RegistrationVerificationStatusResult {
    data object Waiting : RegistrationVerificationStatusResult
    data object Verified : RegistrationVerificationStatusResult
    data object NotAvailable : RegistrationVerificationStatusResult
    data class Failed(val reason: RegistrationFailureReason) : RegistrationVerificationStatusResult
}

interface VerificationEmailRepository {
    suspend fun resend(registrationId: String): VerificationEmailResendResult
    suspend fun status(registrationId: String): RegistrationVerificationStatusResult
}
