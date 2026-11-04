package com.reallanguage.app.core.session

import com.reallanguage.app.core.navigation.AppStartupResult
import com.reallanguage.app.feature.auth.domain.LoginCommand
import com.reallanguage.app.feature.auth.domain.LoginResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real session repository/runtime with isolated in-memory credentials and API responses only. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRecoveryOwnershipTest {
    @Test fun confirmedExpiryProducesAnExactCurrentReceiptAfterCommittedClearAndOrdinaryObserver() = runTest {
        val fixture = Fixture()
        val sourceEpoch = fixture.runtime.accountEpoch
        val sourceRevision = fixture.runtime.identityRevision.value
        fixture.onSignedOut = { account ->
            assertEquals(OWNER, account)
            assertNull(fixture.store.value)
            assertNull(fixture.runtime.accountId)
            assertNull(fixture.runtime.accessToken)
        }
        val resolution = fixture.repository.resolveForConnection(OWNER, sourceEpoch)
        assertEquals(AppStartupResult.SignedOut, resolution.result)
        val receipt = checkNotNull(resolution.signedOutReceipt)
        assertEquals(OWNER, receipt.accountId)
        assertEquals(sourceEpoch, receipt.sourceEpoch)
        assertEquals(sourceEpoch + 1, receipt.clearedEpoch)
        assertEquals(sourceRevision + 1, receipt.clearedIdentityRevision)
        assertEquals(fixture.runtime.accountEpoch, receipt.clearedEpoch)
        assertEquals(fixture.runtime.identityRevision.value, receipt.clearedIdentityRevision)
        assertTrue(fixture.runtime.isCurrent(receipt))
        assertEquals(listOf(OWNER), fixture.signedOutOwners)
        assertEquals(1, fixture.store.clears)
        assertEquals(1, fixture.api.refreshCalls)
        assertEquals(0, fixture.api.revokeCalls)
    }

    @Test fun loginWhileTheCommittedClearObserverIsPendingRejectsTheOldSignedOutTransition() = runTest {
        for (newOwner in listOf(OTHER_OWNER, OWNER)) {
            val fixture = Fixture()
            val observerEntered = CompletableDeferred<Unit>()
            val releaseObserver = CompletableDeferred<Unit>()
            fixture.onSignedOut = {
                observerEntered.complete(Unit)
                releaseObserver.await()
            }
            val epoch = fixture.runtime.accountEpoch
            val pending = async { fixture.repository.resolveForConnection(OWNER, epoch) }
            try {
                runCurrent()
                assertTrue("The old session must really reach its committed-clear observer", observerEntered.isCompleted)
                assertNull(fixture.store.value)
                assertNull(fixture.runtime.accountId)
                fixture.loginAs(newOwner)
                val newer = fixture.snapshot()
                releaseObserver.complete(Unit)
                val resolution = pending.await()
                assertEquals(AppStartupResult.ConnectionUnavailable, resolution.result)
                assertNull("A stale SignedOut receipt cannot escape after a newer login", resolution.signedOutReceipt)
                assertEquals(newer, fixture.snapshot())
                assertEquals(1, fixture.store.clears)
                assertEquals(1, fixture.api.refreshCalls)
                assertEquals(0, fixture.api.revokeCalls)
            } finally {
                releaseObserver.complete(Unit)
                pending.cancel()
                runCurrent()
            }
        }
    }

    @Test fun sameAccountRefreshKeepsTheOwnerEpochAndOnlyAdvancesTokenRevision() = runTest {
        val fixture = Fixture()
        val epoch = fixture.runtime.accountEpoch
        val revision = fixture.runtime.identityRevision.value
        fixture.api.refresh = { authenticated(OWNER) }
        val resolution = fixture.repository.resolveForConnection(OWNER, epoch)
        assertEquals(AppStartupResult.SignedIn(settingsCompleted = true), resolution.result)
        assertNull(resolution.signedOutReceipt)
        assertEquals(epoch, fixture.runtime.accountEpoch)
        assertTrue(fixture.runtime.identityRevision.value > revision)
        assertEquals(stored(OWNER), fixture.store.value)
        assertEquals(OWNER, fixture.runtime.accountId)
        assertEquals(TEST_ACCESS, fixture.runtime.accessToken)
        assertTrue(fixture.signedOutOwners.isEmpty())
        assertEquals(0, fixture.store.clears)
        assertEquals(1, fixture.api.refreshCalls)
        assertEquals(0, fixture.api.revokeCalls)
    }

    @Test fun realExplicitLoginIncludingTheSameAccountInvalidatesTheOldConnectionOwnerBeforeVerification() = runTest {
        for (newOwner in listOf(OTHER_OWNER, OWNER)) {
            val fixture = Fixture()
            val oldEpoch = fixture.runtime.accountEpoch
            // Deliberately no logout/clear or manually fabricated epoch before this real login.
            fixture.loginAs(newOwner)
            assertNotEquals("Explicit login begins a new session even for the same account", oldEpoch, fixture.runtime.accountEpoch)
            val newer = fixture.snapshot()
            val resolution = fixture.repository.resolveForConnection(OWNER, oldEpoch)
            assertEquals(AppStartupResult.ConnectionUnavailable, resolution.result)
            assertNull(resolution.signedOutReceipt)
            assertEquals(newer, fixture.snapshot())
            assertEquals("An obsolete connection must not refresh the newer credentials", 0, fixture.api.refreshCalls)
            assertEquals(0, fixture.store.clears)
            assertEquals(0, fixture.api.revokeCalls)
        }
    }

    @Test fun revocationWhileCredentialCaptureIsSuspendedIsRecheckedBeforeRemoteRefresh() = runTest {
        val fixture = Fixture()
        val captured = CompletableDeferred<Unit>()
        val releaseCapture = CompletableDeferred<Unit>()
        var interceptFirstRead = true
        fixture.store.beforeLoadReturns = {
            if (interceptFirstRead) {
                interceptFirstRead = false
                captured.complete(Unit)
                releaseCapture.await()
            }
        }
        val epoch = fixture.runtime.accountEpoch
        val pending = async { fixture.repository.resolveForConnection(OWNER, epoch) }
        try {
            runCurrent()
            assertTrue("The fixture must suspend the actual credential capture", captured.isCompleted)
            // This is runtime revocation, not a simulated login or evidence of login epoch behavior.
            fixture.runtime.clear()
            val revoked = fixture.snapshot()
            releaseCapture.complete(Unit)
            val resolution = pending.await()
            assertEquals(AppStartupResult.ConnectionUnavailable, resolution.result)
            assertNull(resolution.signedOutReceipt)
            assertEquals(revoked, fixture.snapshot())
            assertEquals(0, fixture.api.refreshCalls)
            assertEquals(0, fixture.store.clears)
        } finally {
            releaseCapture.complete(Unit)
            pending.cancel()
            runCurrent()
        }
    }

    @Test fun anExpiredInFlightCheckCannotClearOrIssueASignedOutReceiptForANewerRealLogin() = runTest {
        for (newOwner in listOf(OTHER_OWNER, OWNER)) {
            val fixture = Fixture()
            val remote = CompletableDeferred<SessionApiResult>()
            fixture.api.refresh = { remote.await() }
            val epoch = fixture.runtime.accountEpoch
            val pending = async { fixture.repository.resolveForConnection(OWNER, epoch) }
            try {
                runCurrent()
                assertEquals(1, fixture.api.refreshCalls)
                fixture.loginAs(newOwner)
                val newer = fixture.snapshot()
                remote.complete(SessionApiResult.InvalidOrExpired)
                val resolution = pending.await()
                assertEquals(AppStartupResult.ConnectionUnavailable, resolution.result)
                assertNull(resolution.signedOutReceipt)
                assertEquals(newer, fixture.snapshot())
                assertEquals(0, fixture.store.clears)
                assertTrue(fixture.signedOutOwners.isEmpty())
                assertEquals(0, fixture.api.revokeCalls)
            } finally {
                remote.complete(SessionApiResult.Failed(SessionFailure.NETWORK))
                pending.cancel()
                runCurrent()
            }
        }
    }

    @Test fun anAlreadyReturnedClearReceiptCannotAuthorizeNavigationAfterAnotherLogin() = runTest {
        val fixture = Fixture()
        val resolution = fixture.repository.resolveForConnection(OWNER, fixture.runtime.accountEpoch)
        assertEquals(AppStartupResult.SignedOut, resolution.result)
        val receipt = checkNotNull(resolution.signedOutReceipt)
        assertTrue(fixture.runtime.isCurrent(receipt))
        fixture.loginAs(OWNER)
        assertFalse("Effect consumers must recheck a receipt against the current runtime", fixture.runtime.isCurrent(receipt))
        assertEquals(stored(OWNER), fixture.store.value)
        assertEquals(OWNER, fixture.runtime.accountId)
        assertEquals(TEST_ACCESS, fixture.runtime.accessToken)
        assertEquals(1, fixture.store.clears)
    }

    private class Fixture {
        val store = Store()
        val runtime = SessionRuntime().apply { update("ISOLATED_OLD_ACCESS_NOT_A_CREDENTIAL", OWNER) }
        val api = Api()
        val signedOutOwners = mutableListOf<String>()
        var onSignedOut: suspend (String) -> Unit = {}
        val repository = ProductionSessionRepository(api, store, runtime, InstallationIdentity { "isolated-recovery-installation" },
            object : SessionLocalLifecycle {
                override suspend fun signedOut(accountId: String) {
                    signedOutOwners += accountId
                    onSignedOut(accountId)
                }
            })

        suspend fun loginAs(accountId: String) {
            api.loginReply = authenticated(accountId)
            assertEquals(LoginResult.Authenticated(accountId, true), repository.login(
                LoginCommand("recovery-fixture@example.invalid", "ISOLATED_PASSWORD_NOT_A_CREDENTIAL"),
            ))
        }

        fun snapshot() = Snapshot(store.value, runtime.accountId, runtime.accessToken, runtime.accountEpoch, runtime.identityRevision.value)
    }

    private data class Snapshot(val stored: StoredSession?, val owner: String?, val access: String?, val epoch: Long, val revision: Long)

    private class Store : SessionCredentialStore {
        var value: StoredSession? = StoredSession(OWNER, "ISOLATED_OLD_REFRESH_NOT_A_CREDENTIAL", true)
        var beforeLoadReturns: suspend () -> Unit = {}
        var clears = 0
        override suspend fun load(): StoredSession? {
            val captured = value
            beforeLoadReturns()
            return captured
        }
        override suspend fun save(session: StoredSession) { value = session }
        override suspend fun clear() { clears++; value = null }
    }

    private class Api : SessionApi {
        var refresh: suspend () -> SessionApiResult = { SessionApiResult.InvalidOrExpired }
        var loginReply = authenticated(OWNER)
        var refreshCalls = 0
        var revokeCalls = 0
        override suspend fun login(email: String, password: String, deviceId: String) = loginReply
        override suspend fun refresh(refreshToken: String, deviceId: String): SessionApiResult { refreshCalls++; return refresh() }
        override suspend fun revoke(refreshToken: String): Boolean { revokeCalls++; return true }
    }

    private companion object {
        const val OWNER = "isolated-recovery-account-a"
        const val OTHER_OWNER = "isolated-recovery-account-b"
        const val TEST_ACCESS = "ISOLATED_NEW_ACCESS_NOT_A_CREDENTIAL"
        const val TEST_REFRESH = "ISOLATED_NEW_REFRESH_NOT_A_CREDENTIAL"
        fun authenticated(account: String) = SessionApiResult.Authenticated(account, TEST_ACCESS, TEST_REFRESH, true)
        fun stored(account: String) = StoredSession(account, TEST_REFRESH, true)
    }
}
