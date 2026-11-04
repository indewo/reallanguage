package com.reallanguage.app.core.app

import com.reallanguage.app.feature.auth.domain.AgreementKind
import com.reallanguage.app.feature.auth.presentation.AuthEffect

sealed interface AuthSubroute {
    data object Entry : AuthSubroute
    data object AgeIneligible : AuthSubroute
    data class EmailVerification(val registrationId: String, val email: String) : AuthSubroute
    data class Agreement(val kind: AgreementKind) : AuthSubroute
    data object PasswordReset : AuthSubroute
}

fun authSubroute(effect: AuthEffect): AuthSubroute? = when (effect) {
    AuthEffect.OpenAgeIneligible -> AuthSubroute.AgeIneligible
    is AuthEffect.OpenEmailVerification -> AuthSubroute.EmailVerification(
        effect.registrationId,
        effect.email,
    )
    AuthEffect.OpenTerms -> AuthSubroute.Agreement(AgreementKind.USER_AGREEMENT)
    AuthEffect.OpenPrivacyPolicy -> AuthSubroute.Agreement(AgreementKind.PRIVACY_POLICY)
    AuthEffect.OpenForgotPassword -> AuthSubroute.PasswordReset
    is AuthEffect.Authenticated -> null
}
