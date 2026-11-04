package com.reallanguage.app.core.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class AppCoordinatorViewModel(
    private val startupRepository: AppStartupRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(AppCoordinatorUiState())
    val uiState: StateFlow<AppCoordinatorUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<AppCoordinatorEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var startupJob: Job? = null
    private var navigationGeneration = 0L

    init { resolveStartup() }

    fun onAction(action: AppCoordinatorAction) {
        when (action) {
            AppCoordinatorAction.RetryStartupRequested -> resolveStartup()
            is AppCoordinatorAction.Authenticated -> replaceNavigation(
                if (action.settingsCompleted) AppRoute.Home else AppRoute.InitialSettings,
            )
            AppCoordinatorAction.InitialSettingsCompleted -> replaceNavigation(AppRoute.Home)
            AppCoordinatorAction.LoggedOut -> replaceNavigation(AppRoute.Login)
            AppCoordinatorAction.BackRequested -> {
                if (mutableUiState.value.route == AppRoute.Home) {
                    effectChannel.trySend(AppCoordinatorEffect.RequestHomeExitConfirmation)
                }
            }
        }
    }

    private fun resolveStartup() {
        if (mutableUiState.value.resolving) return
        val generation = ++navigationGeneration
        mutableUiState.value = AppCoordinatorUiState(resolving = true)
        startupJob = viewModelScope.launch {
            val result = try {
                startupRepository.resolve()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                AppStartupResult.ConnectionUnavailable
            }
            // Cancellation alone cannot revoke a non-cooperative callback.
            if (generation != navigationGeneration) return@launch
            show(
                when (result) {
                    AppStartupResult.SignedOut -> AppRoute.Login
                    is AppStartupResult.SignedIn -> {
                        if (result.settingsCompleted) AppRoute.Home else AppRoute.InitialSettings
                    }
                    AppStartupResult.ConnectionUnavailable -> AppRoute.ConnectionError
                },
            )
        }
    }

    private fun replaceNavigation(route: AppRoute) {
        navigationGeneration++
        startupJob?.cancel()
        show(route)
    }

    override fun onCleared() {
        navigationGeneration++
        startupJob?.cancel()
        super.onCleared()
    }

    private fun show(route: AppRoute) {
        mutableUiState.value = AppCoordinatorUiState(resolving = false, route = route)
    }
}
