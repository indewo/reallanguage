package com.reallanguage.app.feature.auth.domain

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationFormValidatorTest {
    private val validator = RegistrationFormValidator(
        currentYearMonth = { YearMonth.of(2026, 9) },
        emailSyntaxValidator = { it == "learner@example.com" },
    )

    @Test
    fun `accepts the exact fourteen-year month boundary`() {
        val result = validator.validate(validInput(birthYearMonth = "2012-09"))

        assertTrue(result.isValid)
        assertEquals("a sufficiently long password", result.normalizedPassword)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `rejects a learner below the fourteen-year month boundary`() {
        val result = validator.validate(validInput(birthYearMonth = "2012-10"))

        assertFalse(result.isValid)
        assertTrue(result.errors.contains(RegistrationFieldError.AGE_INELIGIBLE))
    }

    @Test
    fun `rejects invalid calendar months without an age calculation`() {
        val result = validator.validate(validInput(birthYearMonth = "2012-13"))

        assertFalse(result.isValid)
        assertEquals(
            setOf(RegistrationFieldError.INVALID_BIRTH_YEAR_MONTH),
            result.errors,
        )
    }

    @Test
    fun `counts normalized Unicode code points rather than UTF-16 units`() {
        val accepted = validator.validate(validInput(password = "🔐".repeat(4)))
        val rejected = validator.validate(validInput(password = "🔐".repeat(3)))
        val decomposed = validator.validate(
            validInput(password = "e\u0301".repeat(4)),
        )

        assertTrue(accepted.isValid)
        assertTrue(rejected.errors.contains(RegistrationFieldError.PASSWORD_TOO_SHORT))
        assertEquals("é".repeat(4), decomposed.normalizedPassword)
    }

    @Test
    fun `rejects passwords above sixty-four code points`() {
        val result = validator.validate(validInput(password = "🔐".repeat(65)))

        assertTrue(result.errors.contains(RegistrationFieldError.PASSWORD_TOO_LONG))
    }

    @Test
    fun `requires valid email and both confirmed agreements`() {
        val result = validator.validate(
            validInput(
                email = "invalid",
                acceptedTerms = false,
                acceptedPrivacyPolicy = false,
            ),
        )

        assertEquals(
            setOf(
                RegistrationFieldError.INVALID_EMAIL,
                RegistrationFieldError.TERMS_REQUIRED,
                RegistrationFieldError.PRIVACY_POLICY_REQUIRED,
            ),
            result.errors,
        )
    }

    private fun validInput(
        email: String = "learner@example.com",
        password: String = "a sufficiently long password",
        birthYearMonth: String = "2000-01",
        acceptedTerms: Boolean = true,
        acceptedPrivacyPolicy: Boolean = true,
    ) = RegistrationFormInput(
        email = email,
        password = password,
        birthYearMonth = birthYearMonth,
        acceptedTerms = acceptedTerms,
        acceptedPrivacyPolicy = acceptedPrivacyPolicy,
    )
}
