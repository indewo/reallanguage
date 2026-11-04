package com.reallanguage.app.core.session

import com.reallanguage.app.core.navigation.AppStartupResult
import com.reallanguage.app.feature.auth.domain.LoginCommand
import com.reallanguage.app.feature.auth.domain.LoginFailureReason
import com.reallanguage.app.feature.auth.domain.LoginResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionSessionRepositoryTest {
    @Test fun successfulLoginPersistsOnlyRefreshStateAndCachesAccessToken() = runTest {
        val api = FakeSessionApi(authenticated("account-1", settingsCompleted = false))
        val store = FakeCredentialStore()
        val runtime = SessionRuntime()
        val repository = repository(api, store, runtime)

        assertEquals(
            LoginResult.Authenticated("account-1", settingsCompleted = false),
            repository.login(LoginCommand("learner@example.com", "password")),
        )
        assertEquals(StoredSession("account-1", "refresh-1", false), store.value)
        assertEquals("access-1", runtime.accessToken)
    }

    @Test fun invalidLoginAndTransportFailuresMapWithoutSavingCredentials() = runTest {
        listOf(
            SessionApiResult.InvalidOrExpired to LoginResult.InvalidCredentials,
            SessionApiResult.Failed(SessionFailure.NETWORK) to
                LoginResult.Failed(LoginFailureReason.NETWORK),
            SessionApiResult.Failed(SessionFailure.SERVICE) to
                LoginResult.Failed(LoginFailureReason.SERVICE),
        ).forEach { (apiResult, expected) ->
            val store = FakeCredentialStore()
            val repository = repository(FakeSessionApi(apiResult), store, SessionRuntime())
            assertEquals(expected, repository.login(LoginCommand("learner@example.com", "password")))
            assertNull(store.value)
        }
    }

    @Test fun coldStartRotatesStoredRefreshTokenAndRoutesBySettingsState() = runTest {
        val store = FakeCredentialStore(StoredSession("account-old", "refresh-old", true))
        val repository = repository(
            FakeSessionApi(authenticated("account-1", settingsCompleted = false)),
            store,
            SessionRuntime(),
        )

        assertEquals(AppStartupResult.SignedIn(false), repository.resolve())
        assertEquals("refresh-1", store.value?.refreshToken)
    }

    @Test fun invalidRefreshClearsLocalSessionWhileConnectionFailureRetainsIt() = runTest {
        val invalidStore = FakeCredentialStore(StoredSession("account-1", "refresh-old", true))
        val invalid = repository(FakeSessionApi(SessionApiResult.InvalidOrExpired), invalidStore, SessionRuntime())
        assertEquals(AppStartupResult.SignedOut, invalid.resolve())
        assertNull(invalidStore.value)

        val failedStore = FakeCredentialStore(StoredSession("account-1", "refresh-old", true))
        val failed = repository(
            FakeSessionApi(SessionApiResult.Failed(SessionFailure.NETWORK)),
            failedStore,
            SessionRuntime(),
        )
        assertEquals(AppStartupResult.ConnectionUnavailable, failed.resolve())
        assertEquals("refresh-old", failedStore.value?.refreshToken)
    }

    @Test fun logoutClearsCredentialsOnlyAfterServerRevocationSucceeds() = runTest {
        val store = FakeCredentialStore(StoredSession("account-1", "refresh-old", true))
        val api = FakeSessionApi(SessionApiResult.InvalidOrExpired, revokeSucceeded = false)
        val repository = repository(api, store, SessionRuntime())
        assertEquals(SessionLogoutResult.Failed, repository.logout())
        assertTrue(store.value != null)

        api.revokeSucceeded = true
        assertEquals(SessionLogoutResult.LoggedOut, repository.logout())
        assertNull(store.value)
    }

    private fun repository(
        api: SessionApi,
        store: SessionCredentialStore,
        runtime: SessionRuntime,
    ) = ProductionSessionRepository(
        api = api,
        credentials = store,
        runtime = runtime,
        installationIdentity = InstallationIdentity { "installation-1" },
    )

    private fun authenticated(accountId: String, settingsCompleted: Boolean) =
        SessionApiResult.Authenticated(
            accountId = accountId,
            accessToken = "access-1",
            refreshToken = "refresh-1",
            settingsCompleted = settingsCompleted,
        )
}

private class FakeCredentialStore(initial: StoredSession? = null) : SessionCredentialStore {
    var value = initial
    override suspend fun load(): StoredSession? = value
    override suspend fun save(session: StoredSession) { value = session }
    override suspend fun clear() { value = null }
}

private class FakeSessionApi(
    var result: SessionApiResult,
    var revokeSucceeded: Boolean = true,
) : SessionApi {
    override suspend fun login(email: String, password: String, deviceId: String) = result
    override suspend fun refresh(refreshToken: String, deviceId: String) = result
    override suspend fun revoke(refreshToken: String) = revokeSucceeded
}
