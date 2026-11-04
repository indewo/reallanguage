package com.reallanguage.app.feature.auth.domain

enum class PasswordResetFailureReason {
    NETWORK,
    SERVICE,
}

sealed interface PasswordResetRequestResult {
    data class Sent(val challengeId: String) : PasswordResetRequestResult
    data class Failed(
        val reason: PasswordResetFailureReason = PasswordResetFailureReason.SERVICE,
        val retryable: Boolean,
    ) : PasswordResetRequestResult
}

sealed interface PasswordResetVerificationResult {
    data class Verified(val resetToken: String) : PasswordResetVerificationResult
    data object InvalidLink : PasswordResetVerificationResult
    data class Failed(
        val reason: PasswordResetFailureReason,
        val retryable: Boolean,
    ) : PasswordResetVerificationResult
}

sealed interface PasswordResetResult {
    data class Completed(
        val sessionEstablished: Boolean,
        val initialSettingsComplete: Boolean,
    ) : PasswordResetResult

    data class PasswordRejected(val reason: PasswordRejectionReason) : PasswordResetResult
    data class Failed(
        val reason: PasswordResetFailureReason,
        val retryable: Boolean,
    ) : PasswordResetResult
}

interface PasswordResetRepository {
    suspend fun request(email: String, commandId: String): PasswordResetRequestResult
    suspend fun verify(challengeId: String, token: String): PasswordResetVerificationResult
    suspend fun reset(
        resetToken: String,
        newPassword: String,
        commandId: String,
    ): PasswordResetResult
}
