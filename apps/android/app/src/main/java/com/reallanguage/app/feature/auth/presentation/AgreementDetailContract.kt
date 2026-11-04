package com.reallanguage.app.feature.auth.presentation

import com.reallanguage.app.feature.auth.domain.AgreementDocument
import com.reallanguage.app.feature.auth.domain.AgreementKind
import com.reallanguage.app.feature.auth.domain.RegistrationDraftSnapshot

data class AgreementDetailUiState(
    val kind: AgreementKind,
    val registrationDraft: RegistrationDraftSnapshot,
    val document: AgreementDocument? = null,
    val updatedDateZh: String? = null,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
)

sealed interface AgreementDetailAction {
    data object RetryRequested : AgreementDetailAction
    data object ReturnRequested : AgreementDetailAction
}

sealed interface AgreementDetailEffect {
    data class ReturnToRegistration(val draft: RegistrationDraftSnapshot) : AgreementDetailEffect
}
