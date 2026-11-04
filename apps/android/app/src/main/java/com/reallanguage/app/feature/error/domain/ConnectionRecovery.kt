package com.reallanguage.app.feature.error.domain

data class ConnectionResumePoint(
    val pageId: String,
    val positionToken: String,
    val temporaryTextInput: String? = null,
)

sealed interface ConnectionRecoveryResult {
    data object Restored : ConnectionRecoveryResult
    data object Unavailable : ConnectionRecoveryResult
    data object LoginRequired : ConnectionRecoveryResult
    data object InitialSettingsRequired : ConnectionRecoveryResult
}

interface ConnectionRecoveryRepository {
    suspend fun verifyNetworkAndAccount(): ConnectionRecoveryResult
    fun canNavigateToLogin(): Boolean = true
}
