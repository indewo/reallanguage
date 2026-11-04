package com.reallanguage.app.feature.auth.domain

data class LoginCommand(val email: String, val password: String)

enum class LoginFailureReason { NETWORK, SERVICE }

sealed interface LoginResult {
    data class Authenticated(
        val accountId: String,
        val settingsCompleted: Boolean,
    ) : LoginResult

    data object InvalidCredentials : LoginResult
    data class Failed(val reason: LoginFailureReason) : LoginResult
}

fun interface LoginRepository {
    suspend fun login(command: LoginCommand): LoginResult
}

data class LoginFormValidation(
    val normalizedEmail: String,
    val emailValid: Boolean,
    val passwordProvided: Boolean,
) {
    val isValid: Boolean = emailValid && passwordProvided
}

class LoginFormValidator(private val emailSyntaxValidator: EmailSyntaxValidator) {
    fun validate(email: String, password: String): LoginFormValidation {
        val normalizedEmail = email.trim().lowercase()
        return LoginFormValidation(
            normalizedEmail = normalizedEmail,
            emailValid = emailSyntaxValidator.isValid(normalizedEmail),
            passwordProvided = password.isNotEmpty(),
        )
    }
}
