package com.reallanguage.app.core.navigation

import androidx.lifecycle.ViewModelStore
import com.reallanguage.app.MainDispatcherRule
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Navigation ownership only: delayed in-memory startup results, no session credentials or network. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppCoordinatorRequestOwnershipTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val models = ViewModelStore()
    private var nextModel = 0

    @After fun clearModels() = models.clear()

    @Test fun loggedOutCannotBeOverwrittenByTheOldSuccessfulStartup() = runTest {
        for (settingsCompleted in listOf(true, false)) {
            assertLateResultIsIgnored(AppCoordinatorAction.LoggedOut, AppRoute.Login,
                AppStartupResult.SignedIn(settingsCompleted))
        }
    }

    @Test fun loggedOutCannotBeOverwrittenByTheOldConnectionFailure() = runTest {
        assertLateResultIsIgnored(AppCoordinatorAction.LoggedOut, AppRoute.Login,
            AppStartupResult.ConnectionUnavailable)
    }

    @Test fun authenticatedReadySessionKeepsHomeWhenAnOldStartupReturns() = runTest {
        for (late in listOf(AppStartupResult.ConnectionUnavailable, AppStartupResult.SignedOut,
            AppStartupResult.SignedIn(settingsCompleted = false))) {
            assertLateResultIsIgnored(AppCoordinatorAction.Authenticated(settingsCompleted = true), AppRoute.Home, late)
        }
    }

    @Test fun authenticatedIncompleteSessionKeepsSettingsWhenAnOldStartupReturns() = runTest {
        for (late in listOf(AppStartupResult.SignedIn(settingsCompleted = true), AppStartupResult.SignedOut,
            AppStartupResult.ConnectionUnavailable)) {
            assertLateResultIsIgnored(AppCoordinatorAction.Authenticated(settingsCompleted = false), AppRoute.InitialSettings, late)
        }
    }

    @Test fun completedInitialSettingsCannotBeUndoneByAnOldStartupResult() = runTest {
        for (late in listOf(AppStartupResult.SignedIn(settingsCompleted = false), AppStartupResult.ConnectionUnavailable)) {
            assertLateResultIsIgnored(AppCoordinatorAction.InitialSettingsCompleted, AppRoute.Home, late)
        }
    }

    @Test fun genuinelyNonCancellableLateCallbacksCannotReplaceNewerNavigation() = runTest {
        val cases = listOf(
            Triple(AppCoordinatorAction.LoggedOut, AppRoute.Login, AppStartupResult.SignedIn(settingsCompleted = true)),
            Triple(AppCoordinatorAction.Authenticated(settingsCompleted = true), AppRoute.Home, AppStartupResult.ConnectionUnavailable),
            Triple(AppCoordinatorAction.Authenticated(settingsCompleted = false), AppRoute.InitialSettings, AppStartupResult.SignedIn(settingsCompleted = true)),
            Triple(AppCoordinatorAction.InitialSettingsCompleted, AppRoute.Home, AppStartupResult.ConnectionUnavailable),
        )
        for ((action, expectedRoute, late) in cases) {
            var callback: Continuation<AppStartupResult>? = null
            var released = false
            var calls = 0
            val coordinator = model {
                calls++
                // Deliberately not suspendCancellableCoroutine: cancelling a Job cannot
                // unregister this callback. The result still needs an ownership check.
                suspendCoroutine { callback = it }
            }
            try {
                runCurrent()
                assertEquals(1, calls)
                assertTrue(coordinator.uiState.value.resolving)
                coordinator.onAction(action)
                val current = AppCoordinatorUiState(resolving = false, route = expectedRoute)
                assertEquals(current, coordinator.uiState.value)
                val continuation = checkNotNull(callback)
                released = true
                continuation.resume(late)
                runCurrent()
                assertEquals("Late callback after $action must not replace the current route", current, coordinator.uiState.value)
                assertEquals(1, calls)
            } finally {
                if (!released) callback?.resume(late)
                models.clear()
                runCurrent()
            }
        }
    }

    @Test fun ordinarySingleStartupStillPublishesItsOwnResultOnce() = runTest {
        var calls = 0
        val coordinator = model { calls++; AppStartupResult.SignedIn(settingsCompleted = true) }
        assertTrue(coordinator.uiState.value.resolving)
        runCurrent()
        assertEquals(AppCoordinatorUiState(resolving = false, route = AppRoute.Home), coordinator.uiState.value)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test fun repeatedStartupBeforeDispatchAndWhilePendingStillUsesOneRead() = runTest {
        val result = CompletableDeferred<AppStartupResult>()
        var calls = 0
        val coordinator = model { calls++; result.await() }
        try {
            repeat(3) { coordinator.onAction(AppCoordinatorAction.RetryStartupRequested) }
            runCurrent()
            assertEquals(1, calls)
            repeat(3) { coordinator.onAction(AppCoordinatorAction.RetryStartupRequested) }
            runCurrent()
            assertEquals(1, calls)
            assertTrue(coordinator.uiState.value.resolving)
            result.complete(AppStartupResult.SignedOut)
            runCurrent()
            assertEquals(AppRoute.Login, coordinator.uiState.value.route)
            assertFalse(coordinator.uiState.value.resolving)
        } finally {
            result.complete(AppStartupResult.SignedOut)
            models.clear()
            runCurrent()
        }
    }

    @Test fun aCompletedConnectionFailureStillAllowsOneExplicitFreshRetry() = runTest {
        val retry = CompletableDeferred<AppStartupResult>()
        var calls = 0
        val coordinator = model {
            calls++
            if (calls == 1) AppStartupResult.ConnectionUnavailable else retry.await()
        }
        try {
            runCurrent()
            assertEquals(AppRoute.ConnectionError, coordinator.uiState.value.route)
            repeat(3) { coordinator.onAction(AppCoordinatorAction.RetryStartupRequested) }
            runCurrent()
            assertEquals(2, calls)
            assertTrue(coordinator.uiState.value.resolving)
            retry.complete(AppStartupResult.SignedIn(settingsCompleted = true))
            runCurrent()
            assertEquals(AppCoordinatorUiState(resolving = false, route = AppRoute.Home), coordinator.uiState.value)
        } finally {
            retry.complete(AppStartupResult.SignedOut)
            models.clear()
            runCurrent()
        }
    }

    private suspend fun TestScope.assertLateResultIsIgnored(
        action: AppCoordinatorAction,
        expectedRoute: AppRoute,
        late: AppStartupResult,
    ) {
        val response = CompletableDeferred<AppStartupResult>()
        var calls = 0
        val coordinator = model { calls++; response.await() }
        try {
            runCurrent()
            assertEquals(1, calls)
            assertTrue(coordinator.uiState.value.resolving)
            coordinator.onAction(action)
            val current = AppCoordinatorUiState(resolving = false, route = expectedRoute)
            assertEquals(current, coordinator.uiState.value)
            response.complete(late)
            runCurrent()
            assertEquals("The result belongs to the startup preceding $action", current, coordinator.uiState.value)
            assertEquals(1, calls)
        } finally {
            response.complete(late)
            models.clear()
            runCurrent()
        }
    }

    private fun model(resolve: suspend () -> AppStartupResult) = AppCoordinatorViewModel(
        AppStartupRepository { resolve() },
    ).also { models.put("startup-owner-${nextModel++}", it) }
}
