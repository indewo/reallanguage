package com.reallanguage.app.core.navigation

import com.reallanguage.app.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppCoordinatorViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun signedOutLaunchOpensLoginAsRoot() = runTest {
        val viewModel = viewModel(AppStartupResult.SignedOut)
        advanceUntilIdle()
        assertEquals(AppRoute.Login, viewModel.uiState.value.route)
        assertFalse(viewModel.uiState.value.resolving)
    }

    @Test fun validSessionWithoutSettingsStartsAtFirstSettingsStep() = runTest {
        val viewModel = viewModel(AppStartupResult.SignedIn(settingsCompleted = false))
        advanceUntilIdle()
        assertEquals(AppRoute.InitialSettings, viewModel.uiState.value.route)
    }

    @Test fun validReadySessionAlwaysColdStartsAtHome() = runTest {
        val viewModel = viewModel(AppStartupResult.SignedIn(settingsCompleted = true))
        advanceUntilIdle()
        assertEquals(AppRoute.Home, viewModel.uiState.value.route)
    }

    @Test fun failedStartupUsesGlobalConnectionStateAndCanRetry() = runTest {
        val repository = FakeRepository(AppStartupResult.ConnectionUnavailable)
        val viewModel = AppCoordinatorViewModel(repository)
        advanceUntilIdle()
        assertEquals(AppRoute.ConnectionError, viewModel.uiState.value.route)
        assertEquals(1, repository.calls)
        repository.result = AppStartupResult.SignedOut
        viewModel.onAction(AppCoordinatorAction.RetryStartupRequested)
        advanceUntilIdle()
        assertEquals(AppRoute.Login, viewModel.uiState.value.route)
        assertEquals(2, repository.calls)
    }

    @Test fun authenticationAndSettingsCompletionFollowConfirmedStartupFlow() = runTest {
        val viewModel = viewModel(AppStartupResult.SignedOut)
        advanceUntilIdle()
        viewModel.onAction(AppCoordinatorAction.Authenticated(settingsCompleted = false))
        assertEquals(AppRoute.InitialSettings, viewModel.uiState.value.route)
        viewModel.onAction(AppCoordinatorAction.InitialSettingsCompleted)
        assertEquals(AppRoute.Home, viewModel.uiState.value.route)
    }

    @Test fun logoutClearsNavigationBackToLogin() = runTest {
        val viewModel = viewModel(AppStartupResult.SignedIn(settingsCompleted = true))
        advanceUntilIdle()
        viewModel.onAction(AppCoordinatorAction.LoggedOut)
        assertEquals(AppRoute.Login, viewModel.uiState.value.route)
    }

    @Test fun pageAndSystemBackShareOneCoordinatorAction() = runTest {
        val viewModel = viewModel(AppStartupResult.SignedIn(settingsCompleted = true))
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(AppCoordinatorAction.BackRequested)
        assertEquals(AppCoordinatorEffect.RequestHomeExitConfirmation, effect.await())
    }

    @Test fun duplicateStartupWhileResolvingCannotRunTwice() = runTest {
        val repository = DelayedRepository()
        val viewModel = AppCoordinatorViewModel(repository)
        runCurrent()
        viewModel.onAction(AppCoordinatorAction.RetryStartupRequested)
        assertTrue(viewModel.uiState.value.resolving)
        assertEquals(1, repository.calls)
        repository.complete(AppStartupResult.SignedOut)
        advanceUntilIdle()
    }

    private fun viewModel(result: AppStartupResult) = AppCoordinatorViewModel(FakeRepository(result))

    private class FakeRepository(var result: AppStartupResult) : AppStartupRepository {
        var calls = 0
        override suspend fun resolve(): AppStartupResult {
            calls++
            return result
        }
    }

    private class DelayedRepository : AppStartupRepository {
        private val result = kotlinx.coroutines.CompletableDeferred<AppStartupResult>()
        var calls = 0
        override suspend fun resolve(): AppStartupResult {
            calls++
            return result.await()
        }
        fun complete(value: AppStartupResult) = result.complete(value)
    }
}
