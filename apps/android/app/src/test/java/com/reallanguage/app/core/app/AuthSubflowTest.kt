package com.reallanguage.app.core.app

import com.reallanguage.app.feature.auth.domain.AgreementKind
import com.reallanguage.app.feature.auth.presentation.AuthEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthSubflowTest {
    @Test fun registrationEffectsMapToExactAccountSubroutes() {
        assertEquals(AuthSubroute.AgeIneligible, authSubroute(AuthEffect.OpenAgeIneligible))
        assertEquals(
            AuthSubroute.EmailVerification("registration-1", "learner@example.com"),
            authSubroute(AuthEffect.OpenEmailVerification("registration-1", "learner@example.com")),
        )
        assertEquals(
            AuthSubroute.Agreement(AgreementKind.USER_AGREEMENT),
            authSubroute(AuthEffect.OpenTerms),
        )
        assertEquals(
            AuthSubroute.Agreement(AgreementKind.PRIVACY_POLICY),
            authSubroute(AuthEffect.OpenPrivacyPolicy),
        )
        assertEquals(AuthSubroute.PasswordReset, authSubroute(AuthEffect.OpenForgotPassword))
        assertNull(authSubroute(AuthEffect.Authenticated(settingsCompleted = true)))
    }
}
