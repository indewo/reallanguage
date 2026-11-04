package com.reallanguage.app.feature.home.domain

data class AccountSnapshot(val email: String)

sealed interface AccountSnapshotResult {
    data class Ready(val snapshot: AccountSnapshot) : AccountSnapshotResult
    data object Failed : AccountSnapshotResult
}

sealed interface LogoutResult {
    data object LoggedOut : LogoutResult
    data object Failed : LogoutResult
}

interface AccountRepository {
    suspend fun load(): AccountSnapshotResult
    suspend fun logout(commandId: String): LogoutResult
}
