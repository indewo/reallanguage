package com.reallanguage.app.feature.auth.domain

enum class AgreementKind { USER_AGREEMENT, PRIVACY_POLICY }

data class AgreementDocument(
    val kind: AgreementKind,
    val titleZh: String,
    val version: String,
    val sections: List<String>,
)

data class RegistrationDraftSnapshot(
    val email: String,
    val password: String,
    val birthYearMonth: String,
    val acceptedTerms: Boolean,
    val acceptedPrivacyPolicy: Boolean,
)

sealed interface AgreementDocumentResult {
    data class Ready(val document: AgreementDocument) : AgreementDocumentResult
    data object Failed : AgreementDocumentResult
}

interface AgreementDocumentRepository {
    suspend fun load(kind: AgreementKind): AgreementDocumentResult
}
