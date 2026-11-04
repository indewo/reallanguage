package com.reallanguage.app.core.session

import com.reallanguage.app.core.navigation.AppStartupResult
import com.reallanguage.app.feature.auth.domain.LoginCommand
import com.reallanguage.app.feature.auth.domain.LoginResult
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Two real repositories share one in-memory runtime/credential location, as distinct Graphs can.
 * Both login coroutines deliberately remain active; this is not a claim that a cancelled Auth Entry
 * can bypass its own cancellation. No Android credentials, HTTP, real accounts or passwords. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionSharedLoginOwnershipTest {
    @Test fun newerRepositoryIntentRejectsOlderSuccessWhileItsOwnDifferentAccountLoginIsPending() = runTest {
        pendingNewIntentWins(sameAccount = false)
    }

    @Test fun newerRepositorySameAccountIntentRejectsOlderTokensBeforeItsOwnReply() = runTest {
        pendingNewIntentWins(sameAccount = true)
    }

    @Test fun failedNewerRepositoryIntentCannotFallBackToAnOlderSuccessfulLogin() = runTest {
        // Existing ProductionSessionLoginCommitTest already specifies no fallback after a newer
        // failed intent; this extends that same rule to repositories sharing the session authority.
        for (failure in listOf(SessionApiResult.InvalidOrExpired, SessionApiResult.Failed(SessionFailure.NETWORK))) {
            val fixture = Fixture()
            val before = fixture.snapshot()
            val older = async { fixture.first.login(fixture.command("older")) }
            var newer: Deferred<LoginResult>? = null
            try {
                runCurrent()
                val latest = async { fixture.second.login(fixture.command("newer")) }
                newer = latest
                runCurrent()
                assertEquals(2, fixture.api.logins.size)
                fixture.api.logins[1].reply(failure)
                assertFalse(latest.await() is LoginResult.Authenticated)
                assertEquals(before, fixture.snapshot())
                fixture.api.logins[0].reply(fixture.auth("older", "obsolete"))
                assertFalse("A newer failed intent is not permission to accept the old login", older.resultOrCancellation() is LoginResult.Authenticated)
                assertEquals(before, fixture.snapshot())
                assertTrue(fixture.store.writes.isEmpty())
                assertEquals(0, fixture.store.clears)
            } finally { fixture.release(older, newer) }
        }
    }

    @Test fun newerRepositoryIntentRevokesAnOlderSaveAlreadyHoldingTheCredentialMutex() = runTest {
        val fixture = Fixture()
        val before = fixture.snapshot()
        val oldResponse = fixture.auth("older", "obsolete")
        val newResponse = fixture.auth("newer", "accepted")
        fixture.store.pauseNextSave = true
        val older = async { fixture.first.login(fixture.command("older")) }
        var newer: Deferred<LoginResult>? = null
        try {
            runCurrent()
            fixture.api.logins.single().reply(oldResponse)
            runCurrent()
            assertTrue("The old real repository is suspended inside credential save", fixture.store.saveWaiting)
            var entered = false
            val latest = async { entered = true; fixture.second.login(fixture.command("newer")) }
            newer = latest
            runCurrent()
            assertTrue(entered)
            assertEquals("New intent waits for credential capture, not for its authority to begin", 1, fixture.api.logins.size)
            fixture.store.releaseSave()
            runCurrent()
            assertFalse("Another repository's new intent must revoke an in-progress old commit", older.resultOrCancellation() is LoginResult.Authenticated)
            assertTrue("The test really exercised a late credential write", fixture.stored(oldResponse) in fixture.store.writes)
            assertEquals("Old writes are restored before the latest remote reply", before, fixture.snapshot())
            assertEquals(2, fixture.api.logins.size)
            fixture.api.logins[1].reply(newResponse)
            assertEquals(LoginResult.Authenticated(newResponse.accountId, true), latest.await())
            fixture.assertAccepted(newResponse, before)
        } finally { fixture.release(older, newer) }
    }

    @Test fun alreadyCommittedNewerLoginRejectsAnOlderLateReplyAcrossRepositories() = runTest {
        // Current stored-session/revision checks already protect this order. Preserve that control.
        for (sameAccount in listOf(false, true)) {
            val fixture = Fixture()
            val before = fixture.snapshot()
            val oldResponse = fixture.auth(if (sameAccount) "initial" else "older", "obsolete")
            val newResponse = fixture.auth(if (sameAccount) "initial" else "newer", "accepted")
            val older = async { fixture.first.login(fixture.command("older")) }
            var newer: Deferred<LoginResult>? = null
            try {
                runCurrent()
                val latest = async { fixture.second.login(fixture.command("newer")) }
                newer = latest
                runCurrent()
                assertEquals(2, fixture.api.logins.size)
                fixture.api.logins[1].reply(newResponse)
                assertEquals(LoginResult.Authenticated(newResponse.accountId, true), latest.await())
                fixture.assertAccepted(newResponse, before)
                val committed = fixture.snapshot()
                fixture.api.logins[0].reply(oldResponse)
                assertFalse(older.resultOrCancellation() is LoginResult.Authenticated)
                assertEquals(committed, fixture.snapshot())
                assertEquals(listOf(fixture.stored(newResponse)), fixture.store.writes)
            } finally { fixture.release(older, newer) }
        }
    }

    @Test fun ordinaryRefreshThroughEitherRepositoryKeepsTheSameEpochAndPersistsRotatedCredentials() = runTest {
        val fixture = Fixture()
        val before = fixture.snapshot()
        val firstRefresh = fixture.auth("initial", "refresh-one")
        val secondRefresh = fixture.auth("initial", "refresh-two")
        fixture.api.refreshReply = firstRefresh
        assertEquals(AppStartupResult.SignedIn(true), fixture.first.resolve())
        assertEquals(fixture.stored(firstRefresh), fixture.store.value)
        assertEquals(before.epoch, fixture.runtime.accountEpoch)
        fixture.api.refreshReply = secondRefresh
        assertEquals(AppStartupResult.SignedIn(true), fixture.second.resolve())
        assertEquals(fixture.stored(secondRefresh), fixture.store.value)
        assertEquals(secondRefresh.accountId, fixture.runtime.accountId)
        assertEquals(secondRefresh.accessToken, fixture.runtime.accessToken)
        assertEquals(before.epoch, fixture.runtime.accountEpoch)
        assertEquals(before.revision + 2, fixture.runtime.identityRevision.value)
        assertEquals(listOf(requireNotNull(before.stored).refreshToken, firstRefresh.refreshToken), fixture.api.refreshInputs)
        assertEquals(listOf(fixture.stored(firstRefresh), fixture.stored(secondRefresh)), fixture.store.writes)
        assertTrue(fixture.api.logins.isEmpty())
        assertEquals(0, fixture.store.clears)
    }

    private suspend fun TestScope.pendingNewIntentWins(sameAccount: Boolean) {
        val fixture = Fixture()
        val before = fixture.snapshot()
        val oldResponse = fixture.auth(if (sameAccount) "initial" else "older", "obsolete")
        val newResponse = fixture.auth(if (sameAccount) "initial" else "newer", "accepted")
        val older = async { fixture.first.login(fixture.command("older")) }
        var newer: Deferred<LoginResult>? = null
        try {
            runCurrent()
            val latest = async { fixture.second.login(fixture.command("newer")) }
            newer = latest
            runCurrent()
            assertEquals("Both repositories captured the same original session", 2, fixture.api.logins.size)
            fixture.api.logins[0].reply(oldResponse)
            assertFalse("The older success cannot win merely by returning before the newer request", older.resultOrCancellation() is LoginResult.Authenticated)
            assertEquals(before, fixture.snapshot())
            assertTrue(fixture.store.writes.isEmpty())
            fixture.api.logins[1].reply(newResponse)
            assertEquals(LoginResult.Authenticated(newResponse.accountId, true), latest.await())
            fixture.assertAccepted(newResponse, before)
            assertEquals(listOf(fixture.stored(newResponse)), fixture.store.writes)
        } finally { fixture.release(older, newer) }
    }

    private suspend fun Deferred<LoginResult>.resultOrCancellation(): LoginResult? =
        try { await() } catch (_: CancellationException) { null }

    private class Fixture {
        private val id = UUID.randomUUID().toString()
        val store = Store(StoredSession("account-initial-$id", "ISOLATED_INITIAL_REFRESH", true))
        val runtime = SessionRuntime().apply { update("ISOLATED_INITIAL_ACCESS", "account-initial-$id") }
        val api = Api()
        private val installation = InstallationIdentity { "isolated-shared-installation-$id" }
        val first = ProductionSessionRepository(api, store, runtime, installation)
        val second = ProductionSessionRepository(api, store, runtime, installation)
        fun command(label: String) = LoginCommand("$label-$id@example.invalid", "ISOLATED_PASSWORD_NOT_A_CREDENTIAL")
        fun auth(label: String, tokenLabel: String) = SessionApiResult.Authenticated(
            "account-$label-$id", "ISOLATED_${tokenLabel}_ACCESS", "ISOLATED_${tokenLabel}_REFRESH", true)
        fun stored(result: SessionApiResult.Authenticated) = StoredSession(result.accountId, result.refreshToken, result.settingsCompleted)
        fun snapshot() = Snapshot(store.value, runtime.accountId, runtime.accessToken, runtime.accountEpoch, runtime.identityRevision.value)
        fun assertAccepted(result: SessionApiResult.Authenticated, before: Snapshot) {
            assertEquals(stored(result), store.value)
            assertEquals(result.accountId, runtime.accountId)
            assertEquals(result.accessToken, runtime.accessToken)
            assertEquals(before.epoch + 1, runtime.accountEpoch)
            assertEquals(before.revision + 1, runtime.identityRevision.value)
            assertEquals(0, store.clears)
        }
        suspend fun release(vararg jobs: Job?) {
            jobs.forEach { it?.cancel() }
            api.logins.forEach { it.reply(SessionApiResult.Failed(SessionFailure.NETWORK)) }
            store.releaseSave()
            jobs.forEach { it?.join() }
        }
    }
    private data class Snapshot(val stored: StoredSession?, val owner: String?, val access: String?, val epoch: Long, val revision: Long)
    private class Store(initial: StoredSession) : SessionCredentialStore {
        var value: StoredSession? = initial
        val writes = mutableListOf<StoredSession>()
        var clears = 0
        var pauseNextSave = false
        private var save: Pending<Unit>? = null
        val saveWaiting get() = save != null
        override suspend fun load() = value
        override suspend fun save(session: StoredSession) {
            if (pauseNextSave) { pauseNextSave = false; suspendCoroutine<Unit> { save = Pending(it) } }
            value = session
            writes += session
        }
        override suspend fun clear() { clears++; value = null }
        fun releaseSave() { val pending = save; save = null; pending?.reply(Unit) }
    }
    private class Api : SessionApi {
        val logins = mutableListOf<Pending<SessionApiResult>>()
        val refreshInputs = mutableListOf<String>()
        var refreshReply: SessionApiResult? = null
        override suspend fun login(email: String, password: String, deviceId: String): SessionApiResult =
            suspendCoroutine { logins += Pending(it) }
        override suspend fun refresh(refreshToken: String, deviceId: String): SessionApiResult {
            refreshInputs += refreshToken
            return checkNotNull(refreshReply)
        }
        override suspend fun revoke(refreshToken: String): Boolean = error("No logout in shared-login fixture")
    }
    private class Pending<T>(private var callback: Continuation<T>?) {
        fun reply(value: T) { val current = callback ?: return; callback = null; current.resume(value) }
    }
}
