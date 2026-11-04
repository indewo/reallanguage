package com.reallanguage.app.core.navigation

enum class AppRoute {
    Login,
    InitialSettings,
    Home,
    ConnectionError,
}

data class AppCoordinatorUiState(
    val resolving: Boolean = false,
    val route: AppRoute? = null,
)

sealed interface AppCoordinatorAction {
    data object RetryStartupRequested : AppCoordinatorAction
    data class Authenticated(val settingsCompleted: Boolean) : AppCoordinatorAction
    data object InitialSettingsCompleted : AppCoordinatorAction
    data object LoggedOut : AppCoordinatorAction
    data object BackRequested : AppCoordinatorAction
}

sealed interface AppCoordinatorEffect {
    data object RequestHomeExitConfirmation : AppCoordinatorEffect
}
