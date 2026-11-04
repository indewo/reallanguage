package com.reallanguage.app.core.session

import com.reallanguage.app.core.navigation.AppStartupResult
import com.reallanguage.app.feature.auth.domain.LoginCommand
import com.reallanguage.app.feature.auth.domain.LoginFailureReason
import com.reallanguage.app.feature.auth.domain.LoginResult
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountDeletionSessionIntegrityTest {
    @Test fun oldReceiptCannotClearNewStoredOrRuntimeAccount() = runTest {
        val old = owner(); val current = owner()
        val store = Store(session(current)); val runtime = runtime(current)
        val deleted = mutableListOf<String>()
        repository(Api(), store, runtime, deleted).clearAfterAccountDeletion(old)
        assertEquals(current, store.value?.accountId)
        assertEquals(current, runtime.accountId)
        assertEquals(0, store.clearCalls)
        assertFalse(deleted.contains(current))
    }

    @Test fun oldPersistedCredentialsCanBeClearedWithoutClearingNewRuntimeAccount() = runTest {
        val old = owner(); val current = owner()
        val store = Store(session(old)); val runtime = runtime(current)
        repository(Api(), store, runtime).clearAfterAccountDeletion(old)
        assertNull(store.value)
        assertEquals(current, runtime.accountId)
    }

    @Test fun unreadablePersistentOwnerCannotBeGuessedFromRuntime() = runTest {
        val old = owner(); val current = owner()
        val store = Store(session(current)).apply { failLoad = true }
        val runtime = runtime(old)
        val failure = runCatching { repository(Api(), store, runtime).clearAfterAccountDeletion(old) }
        assertTrue(failure.isFailure)
        assertEquals(0, store.clearCalls)
        assertEquals(current, store.value?.accountId)
    }

    @Test fun silentCredentialClearFailureDoesNotCompleteDeletionOrNotifyObserver() = runTest {
        val old = owner(); val store = Store(session(old)).apply { ignoreClear = true }
        val runtime = runtime(old); val deleted = mutableListOf<String>()
        val failure = runCatching { repository(Api(), store, runtime, deleted).clearAfterAccountDeletion(old) }
        assertTrue(failure.isFailure)
        assertEquals(old, store.value?.accountId)
        assertTrue(deleted.isEmpty())
    }

    @Test fun thrownCredentialClearFailureDoesNotNotifyCompletedDeletion() = runTest {
        val old = owner(); val store = Store(session(old)).apply { failClear = true }
        val deleted = mutableListOf<String>()
        assertTrue(runCatching { repository(Api(), store, runtime(old), deleted).clearAfterAccountDeletion(old) }.isFailure)
        assertEquals(old, store.value?.accountId)
        assertTrue(deleted.isEmpty())
    }

    @Test fun separateRepositoryInstancesSerializeCleanupReadAndNewAccountSave() = runTest {
        val old = owner(); val current = owner()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val store = Store(session(old)).apply {
            firstLoad = { captured -> entered.complete(Unit); release.await(); captured }
        }
        val runtime = runtime(old)
        val cleanup = async { repository(Api(), store, runtime).clearAfterAccountDeletion(old) }
        entered.await()
        val login = async { repository(Api(authenticated(current)), store, runtime).login(command()) }
        runCurrent()
        release.complete(Unit)
        cleanup.await()
        assertEquals(LoginResult.Authenticated(current, true), login.await())
        assertEquals(current, store.value?.accountId)
        assertEquals(current, runtime.accountId)
    }

    @Test fun lateAuthenticationCannotRestoreConfirmedDeletedAccount() = runTest {
        val old = owner(); val store = Store(); val runtime = SessionRuntime()
        val response = CompletableDeferred<SessionApiResult>()
        val api = Api().apply { loginReply = response }
        val login = async { repository(api, store, runtime).login(command()) }
        runCurrent()
        DeletedAccountLocalAccess.revoke(old)
        response.complete(authenticated(old))
        assertEquals(LoginResult.Failed(LoginFailureReason.SERVICE), login.await())
        assertNull(store.value); assertNull(runtime.accountId)
    }

    @Test fun lateRefreshCannotRestoreConfirmedDeletedAccount() = runTest {
        val old = owner(); val store = Store(session(old)); val runtime = runtime(old)
        val response = CompletableDeferred<SessionApiResult>()
        val api = Api().apply { refreshReply = response }
        val repository = repository(api, store, runtime)
        val refresh = async { repository.resolve() }
        runCurrent()
        DeletedAccountLocalAccess.revoke(old)
        repository.clearAfterAccountDeletion(old)
        response.complete(authenticated(old))
        assertEquals(AppStartupResult.ConnectionUnavailable, refresh.await())
        assertNull(store.value); assertNull(runtime.accountId)
    }

    @Test fun ordinaryLogoutDoesNotPermanentlyRevokeAccountOrPreventNewLogin() = runTest {
        val account = owner(); val store = Store(session(account)); val runtime = runtime(account)
        val repository = repository(Api(authenticated(account)), store, runtime)
        assertEquals(SessionLogoutResult.LoggedOut, repository.logout())
        assertEquals(LoginResult.Authenticated(account, true), repository.login(command()))
        assertEquals(account, store.value?.accountId)
        assertEquals(account, runtime.accountId)
    }

    private class Store(var value: StoredSession? = null) : SessionCredentialStore {
        var failLoad = false; var failClear = false; var ignoreClear = false
        var clearCalls = 0
        var firstLoad: (suspend (StoredSession?) -> StoredSession?)? = null
        override suspend fun load(): StoredSession? {
            check(!failLoad)
            val captured = value
            val hook = firstLoad.also { firstLoad = null }
            return if (hook == null) captured else hook(captured)
        }
        override suspend fun save(session: StoredSession) { value = session }
        override suspend fun clear() { clearCalls++; check(!failClear); if (!ignoreClear) value = null }
    }

    private class Api(var result: SessionApiResult = SessionApiResult.InvalidOrExpired) : SessionApi {
        var loginReply: CompletableDeferred<SessionApiResult>? = null
        var refreshReply: CompletableDeferred<SessionApiResult>? = null
        override suspend fun login(email: String, password: String, deviceId: String) = loginReply?.await() ?: result
        override suspend fun refresh(refreshToken: String, deviceId: String) = refreshReply?.await() ?: result
        override suspend fun revoke(refreshToken: String) = true
    }

    private fun repository(api: SessionApi, store: SessionCredentialStore, runtime: SessionRuntime, deleted: MutableList<String> = mutableListOf()) =
        ProductionSessionRepository(api, store, runtime, InstallationIdentity { "isolated-session-integrity" }, object : SessionLocalLifecycle {
            override suspend fun signedOut(accountId: String) = Unit
            override suspend fun accountReplaced(previousAccountId: String) = Unit
            override suspend fun accountDeleted(accountId: String) { deleted += accountId }
        })

    private fun owner() = "session-integrity-${UUID.randomUUID()}"
    private fun session(account: String) = StoredSession(account, "isolated-refresh", true)
    private fun runtime(account: String) = SessionRuntime().apply { update("isolated-access", account) }
    private fun authenticated(account: String) = SessionApiResult.Authenticated(account, "isolated-access", "isolated-refresh", true)
    private fun command() = LoginCommand("session-fixture@example.invalid", "isolated-test-password")
}
