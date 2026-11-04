package com.reallanguage.app.core.navigation

sealed interface AppStartupResult {
    data object SignedOut : AppStartupResult
    data class SignedIn(val settingsCompleted: Boolean) : AppStartupResult
    data object ConnectionUnavailable : AppStartupResult
}

fun interface AppStartupRepository {
    suspend fun resolve(): AppStartupResult
}
