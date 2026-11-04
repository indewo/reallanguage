package com.reallanguage.app.core.session

import com.reallanguage.app.core.navigation.AppStartupResult
import com.reallanguage.app.feature.auth.domain.LoginCommand
import com.reallanguage.app.feature.auth.domain.LoginResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProductionSessionLocalLifecycleTest {
    private val store = Store(StoredSession("old-owner", "isolated-refresh", true))
    private val runtime = SessionRuntime().apply { update("isolated-access", "old-owner") }
    private val api = Api()
    private val events = mutableListOf<String>()
    private var observerFailure: Exception? = null
    private val lifecycle = object : SessionLocalLifecycle {
        override suspend fun signedOut(accountId: String) { observe("signedOut:$accountId") }
        override suspend fun accountDeleted(accountId: String) { observe("deleted:$accountId") }
        override suspend fun accountReplaced(previousAccountId: String) { observe("replaced:$previousAccountId") }
        private fun observe(event: String) {
            if (event.startsWith("replaced:")) {
                assertEquals("new-owner", store.value?.accountId); assertEquals("new-owner", runtime.accountId)
            } else { assertNull(store.value); assertNull(runtime.accountId); assertNull(runtime.accessToken) }
            events += event
            observerFailure?.let { throw it }
        }
    }
    private fun repository() = ProductionSessionRepository(api, store, runtime, InstallationIdentity { "isolated-installation" }, lifecycle)

    @Test fun `invalid refresh signs out exact stored owner after clearing both local session states`() = runTest {
        api.result = SessionApiResult.InvalidOrExpired
        assertEquals(AppStartupResult.SignedOut, repository().resolve())
        assertEquals(listOf("signedOut:old-owner"), events)
        assertNull(store.value); assertNull(runtime.accountId)
    }

    @Test fun `missing credentials clear stale runtime owner and notify once without refreshing`() = runTest {
        store.value = null
        assertEquals(AppStartupResult.SignedOut, repository().resolve())
        assertNull(runtime.accountId)
        assertEquals(listOf("signedOut:old-owner"), events)
        assertEquals(0, api.refreshCalls)
        assertEquals(AppStartupResult.SignedOut, repository().resolve())
        assertEquals(listOf("signedOut:old-owner"), events)
    }

    @Test fun `successful logout notifies once and repeated signed out logout has no owner to cancel`() = runTest {
        assertEquals(SessionLogoutResult.LoggedOut, repository().logout())
        assertEquals(listOf("signedOut:old-owner"), events)
        assertEquals(SessionLogoutResult.LoggedOut, repository().logout())
        assertEquals(listOf("signedOut:old-owner"), events)
    }

    @Test fun `failed revocation or refresh connection failure keeps the old local reminder owner`() = runTest {
        api.revokeSucceeded = false
        assertEquals(SessionLogoutResult.Failed, repository().logout())
        listOf(SessionFailure.NETWORK, SessionFailure.SERVICE).forEach { reason ->
            api.result = SessionApiResult.Failed(reason)
            assertEquals(AppStartupResult.ConnectionUnavailable, repository().resolve())
        }
        assertEquals("old-owner", store.value?.accountId)
        assertEquals("old-owner", runtime.accountId)
        assertTrue(events.isEmpty())
    }

    @Test fun `account deletion emits deletion rather than ordinary sign out and supports runtime fallback`() = runTest {
        repository().clearAfterAccountDeletion("old-owner")
        assertEquals(listOf("deleted:old-owner"), events)
        runtime.update("runtime-only-access", "runtime-only-owner")
        repository().clearAfterAccountDeletion("runtime-only-owner")
        assertEquals(listOf("deleted:old-owner", "deleted:runtime-only-owner"), events)
    }

    @Test fun `new account acceptance cancels previous owner but same account refresh does not cancel`() = runTest {
        api.result = authenticated("new-owner")
        assertEquals(LoginResult.Authenticated("new-owner", true), repository().login(LoginCommand("fixture@example.invalid", "isolated-password")))
        assertEquals(listOf("replaced:old-owner"), events)
        assertEquals(AppStartupResult.SignedIn(true), repository().resolve())
        assertEquals(listOf("replaced:old-owner"), events)
    }

    @Test fun `account replacement and logout use runtime owner when persisted credentials are absent`() = runTest {
        store.value = null
        api.result = authenticated("new-owner")
        repository().login(LoginCommand("fixture@example.invalid", "isolated-password"))
        assertEquals(listOf("replaced:old-owner"), events)
        store.value = null
        assertEquals(SessionLogoutResult.LoggedOut, repository().logout())
        assertEquals(listOf("replaced:old-owner", "signedOut:new-owner"), events)
    }

    @Test fun `ordinary observer failure cannot turn successful logout into failure`() = runTest {
        observerFailure = IllegalStateException("isolated notification failure")
        assertEquals(SessionLogoutResult.LoggedOut, repository().logout())
        assertEquals(listOf("signedOut:old-owner"), events)
        assertNull(store.value); assertNull(runtime.accountId)
    }

    @Test fun `observer failure cannot misreport successful account replacement or deletion`() = runTest {
        observerFailure = IllegalStateException("isolated notification failure")
        api.result = authenticated("new-owner")
        assertEquals(LoginResult.Authenticated("new-owner", true), repository().login(LoginCommand("fixture@example.invalid", "isolated-password")))
        assertEquals(listOf("replaced:old-owner"), events)
        repository().clearAfterAccountDeletion("new-owner")
        assertEquals(listOf("replaced:old-owner", "deleted:new-owner"), events)
    }

    @Test fun `observer cancellation propagates while already committed sign out stays signed out`() = runTest {
        observerFailure = CancellationException("isolated observer cancellation")
        try { repository().logout(); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        assertNull(store.value); assertNull(runtime.accountId)
        assertEquals(listOf("signedOut:old-owner"), events)
    }

    @Test fun `upstream cancellation or failed credential save never emits a completed transition`() = runTest {
        api.failure = CancellationException("isolated upstream cancellation")
        try { repository().logout(); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        assertEquals("old-owner", store.value?.accountId); assertTrue(events.isEmpty())
        api.failure = null
        api.result = authenticated("new-owner")
        store.failSave = true
        try { repository().login(LoginCommand("fixture@example.invalid", "isolated-password")); fail("Save failure must propagate") }
        catch (_: IllegalStateException) { }
        assertEquals("old-owner", store.value?.accountId); assertEquals("old-owner", runtime.accountId)
        assertTrue(events.isEmpty())
    }

    @Test fun `unreadable credential ownership prevents login replacement and destructive deletion`() = runTest {
        store.failLoad = true
        api.result = authenticated("new-owner")
        // Login now captures authoritative ownership before HTTP; an unreadable store cannot be
        // treated as empty and overwritten. Optional notification fallback is not identity proof.
        try { repository().login(LoginCommand("fixture@example.invalid", "isolated-password")); fail("Unverified credential owner must not be overwritten") }
        catch (_: IllegalStateException) { }
        assertTrue(events.isEmpty())
        try { repository().clearAfterAccountDeletion("old-owner"); fail("Unverified credential owner must not be deleted") }
        catch (_: IllegalStateException) { }
        assertEquals("old-owner", store.value?.accountId)
        assertEquals("old-owner", runtime.accountId)
        assertTrue(events.isEmpty())
    }

    private class Store(var value: StoredSession?) : SessionCredentialStore {
        var failSave = false
        var failLoad = false
        override suspend fun load(): StoredSession? { check(!failLoad); return value }
        override suspend fun save(session: StoredSession) { check(!failSave); value = session }
        override suspend fun clear() { value = null }
    }
    private class Api : SessionApi {
        var result: SessionApiResult = authenticated("old-owner")
        var revokeSucceeded = true
        var failure: Exception? = null
        var refreshCalls = 0
        override suspend fun login(email: String, password: String, deviceId: String): SessionApiResult { failure?.let { throw it }; return result }
        override suspend fun refresh(refreshToken: String, deviceId: String): SessionApiResult { refreshCalls += 1; failure?.let { throw it }; return result }
        override suspend fun revoke(refreshToken: String): Boolean { failure?.let { throw it }; return revokeSucceeded }
    }
    private companion object {
        fun authenticated(account: String) = SessionApiResult.Authenticated(account, "new-isolated-access", "new-isolated-refresh", true)
    }
}
