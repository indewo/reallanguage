package com.reallanguage.app.feature.auth.presentation

enum class PasswordResetStep {
    EMAIL_CONFIRMATION,
    WAITING_VERIFICATION,
    NEW_PASSWORD,
    SUCCESS,
}

enum class PasswordResetError {
    INVALID_EMAIL,
    PASSWORD_TOO_SHORT,
    PASSWORD_TOO_LONG,
    PASSWORD_BLOCKED,
    INVALID_LINK,
    NETWORK,
    SERVICE,
}

enum class PasswordResetCompletionDestination {
    LOGIN,
    INITIAL_SETTINGS,
    HOME,
}

data class PasswordResetUiState(
    val step: PasswordResetStep = PasswordResetStep.EMAIL_CONFIRMATION,
    val email: String = "",
    val newPassword: String = "",
    val challengeId: String? = null,
    val resetToken: String? = null,
    val operationInProgress: Boolean = false,
    val error: PasswordResetError? = null,
    val completionDestination: PasswordResetCompletionDestination? = null,
)

sealed interface PasswordResetAction {
    data class EmailChanged(val value: String) : PasswordResetAction
    data object SendRequested : PasswordResetAction
    data object ChangeEmailRequested : PasswordResetAction
    data class VerificationLinkOpened(
        val token: String,
        val challengeId: String? = null,
    ) : PasswordResetAction
    data class PasswordChanged(val value: String) : PasswordResetAction
    data object ResetRequested : PasswordResetAction
    data object CompleteRequested : PasswordResetAction
    data object ReturnToLoginRequested : PasswordResetAction
}

sealed interface PasswordResetEffect {
    data object OpenLogin : PasswordResetEffect
    data object OpenInitialSettings : PasswordResetEffect
    data object OpenHome : PasswordResetEffect
}
