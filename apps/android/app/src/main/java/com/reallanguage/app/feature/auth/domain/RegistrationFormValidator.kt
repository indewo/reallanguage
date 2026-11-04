package com.reallanguage.app.feature.auth.domain

import java.text.Normalizer
import java.time.DateTimeException
import java.time.YearMonth

data class RegistrationFormInput(
    val email: String,
    val password: String,
    val birthYearMonth: String,
    val acceptedTerms: Boolean,
    val acceptedPrivacyPolicy: Boolean,
)

enum class RegistrationFieldError {
    INVALID_EMAIL,
    PASSWORD_TOO_SHORT,
    PASSWORD_TOO_LONG,
    INVALID_BIRTH_YEAR_MONTH,
    AGE_INELIGIBLE,
    TERMS_REQUIRED,
    PRIVACY_POLICY_REQUIRED,
}

data class RegistrationFormValidation(
    val normalizedPassword: String?,
    val errors: Set<RegistrationFieldError>,
) {
    val isValid: Boolean = errors.isEmpty()
}

fun interface CurrentYearMonth {
    fun current(): YearMonth
}

fun interface EmailSyntaxValidator {
    fun isValid(email: String): Boolean
}

class RegistrationFormValidator(
    private val currentYearMonth: CurrentYearMonth,
    private val emailSyntaxValidator: EmailSyntaxValidator,
) {
    fun validate(input: RegistrationFormInput): RegistrationFormValidation {
        val errors = linkedSetOf<RegistrationFieldError>()
        if (!emailSyntaxValidator.isValid(input.email)) {
            errors += RegistrationFieldError.INVALID_EMAIL
        }

        val normalizedPassword = Normalizer.normalize(input.password, Normalizer.Form.NFC)
        val passwordCodePoints = normalizedPassword.codePointCount(0, normalizedPassword.length)
        when {
            passwordCodePoints < MINIMUM_PASSWORD_CODE_POINTS ->
                errors += RegistrationFieldError.PASSWORD_TOO_SHORT
            passwordCodePoints > MAXIMUM_PASSWORD_CODE_POINTS ->
                errors += RegistrationFieldError.PASSWORD_TOO_LONG
        }

        val birthYearMonth = parseYearMonth(input.birthYearMonth)
        if (birthYearMonth == null) {
            errors += RegistrationFieldError.INVALID_BIRTH_YEAR_MONTH
        } else if (birthYearMonth.plusYears(MINIMUM_AGE_YEARS).isAfter(currentYearMonth.current())) {
            errors += RegistrationFieldError.AGE_INELIGIBLE
        }

        if (!input.acceptedTerms) errors += RegistrationFieldError.TERMS_REQUIRED
        if (!input.acceptedPrivacyPolicy) {
            errors += RegistrationFieldError.PRIVACY_POLICY_REQUIRED
        }

        return RegistrationFormValidation(
            normalizedPassword = normalizedPassword.takeIf {
                passwordCodePoints in MINIMUM_PASSWORD_CODE_POINTS..MAXIMUM_PASSWORD_CODE_POINTS
            },
            errors = errors,
        )
    }

    private fun parseYearMonth(value: String): YearMonth? = try {
        YearMonth.parse(value)
    } catch (_: DateTimeException) {
        null
    }

    private companion object {
        const val MINIMUM_PASSWORD_CODE_POINTS = 4
        const val MAXIMUM_PASSWORD_CODE_POINTS = 64
        const val MINIMUM_AGE_YEARS = 14L
    }
}
