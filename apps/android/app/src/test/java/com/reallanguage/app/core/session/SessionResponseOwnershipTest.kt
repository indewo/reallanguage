package com.reallanguage.app.core.session

import com.reallanguage.app.core.navigation.AppStartupResult
import com.reallanguage.app.feature.auth.domain.LoginCommand
import com.reallanguage.app.feature.auth.domain.LoginResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionResponseOwnershipTest {
    @Test fun expiredOldRefreshCannotClearNewLoginOrReturnSignedOut() = runTest {
        val store = Store(); val runtime = SessionRuntime().apply { update("old-access", "old-owner") }
        val api = Api(); val repository = repository(api, store, runtime)
        val refresh = async { repository.resolve() }
        runCurrent()
        assertEquals(LoginResult.Authenticated("new-owner", true), repository.login(command()))
        api.refreshResult.complete(SessionApiResult.InvalidOrExpired)
        assertEquals(AppStartupResult.ConnectionUnavailable, refresh.await())
        assertEquals("new-owner", store.value?.accountId)
        assertEquals("new-owner", runtime.accountId)
    }

    @Test fun lateOldRevocationCannotClearNewLoginOrReportNewSessionLoggedOut() = runTest {
        val store = Store(); val runtime = SessionRuntime().apply { update("old-access", "old-owner") }
        val api = Api(); val repository = repository(api, store, runtime)
        val logout = async { repository.logout() }
        runCurrent()
        assertEquals(LoginResult.Authenticated("new-owner", true), repository.login(command()))
        api.revocationResult.complete(true)
        assertEquals(SessionLogoutResult.Failed, logout.await())
        assertEquals("new-owner", store.value?.accountId)
        assertEquals("new-owner", runtime.accountId)
    }

    private class Store : SessionCredentialStore {
        var value: StoredSession? = StoredSession("old-owner", "old-refresh", true)
        override suspend fun load() = value
        override suspend fun save(session: StoredSession) { value = session }
        override suspend fun clear() { value = null }
    }
    private class Api : SessionApi {
        val refreshResult = CompletableDeferred<SessionApiResult>()
        val revocationResult = CompletableDeferred<Boolean>()
        override suspend fun login(email: String, password: String, deviceId: String) =
            SessionApiResult.Authenticated("new-owner", "new-access", "new-refresh", true)
        override suspend fun refresh(refreshToken: String, deviceId: String) = refreshResult.await()
        override suspend fun revoke(refreshToken: String) = revocationResult.await()
    }
    private fun repository(api: SessionApi, store: SessionCredentialStore, runtime: SessionRuntime) =
        ProductionSessionRepository(api, store, runtime, InstallationIdentity { "isolated-response-ownership" })
    private fun command() = LoginCommand("response-fixture@example.invalid", "isolated-test-password")
}
