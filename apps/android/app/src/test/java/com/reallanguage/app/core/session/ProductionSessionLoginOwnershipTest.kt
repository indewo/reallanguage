package com.reallanguage.app.core.session

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real repository, but credentials and non-cooperative API callbacks exist only in memory. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductionSessionLoginOwnershipTest {
    @Test fun laterDifferentAccountLoginWinsEvenWhenEarlierLoginSucceedsLast() = runTest {
        assertLatestLoginWins(sameAccount = false)
    }

    @Test fun laterSameAccountLoginKeepsItsTokensAndEpochWhenEarlierLoginSucceedsLast() = runTest {
        assertLatestLoginWins(sameAccount = true)
    }

    @Test fun cancelledLoginCannotWriteCredentialsOrRuntimeWhenItsApiReturnsSuccessLate() = runTest {
        val fixture = Fixture()
        val before = fixture.snapshot()
        val pending = launch { fixture.repository.login(fixture.command("cancelled")) }
        try {
            runCurrent()
            assertEquals(1, fixture.api.calls.size)
            pending.cancel()
            runCurrent()
            assertTrue(pending.isCancelled)
            assertFalse("The callback deliberately does not cooperate with cancellation", pending.isCompleted)

            fixture.api.calls.single().reply(fixture.authenticated("cancelled", "late"))
            runCurrent()
            pending.join()

            assertEquals("The old API success really returned after cancellation", 1, fixture.api.returnedResponses)
            assertEquals(before, fixture.snapshot())
            assertTrue("Cancelled work must not even attempt credential persistence", fixture.store.saves.isEmpty())
            assertEquals(0, fixture.store.clears)
        } finally {
            fixture.release(pending)
        }
    }

    @Test fun singleExplicitSameAccountLoginPersistsItsCredentialsAndBeginsANewEpoch() = runTest {
        val fixture = Fixture()
        val before = fixture.snapshot()
        val accepted = fixture.authenticated("initial", "explicit")
        val pending = async { fixture.repository.login(fixture.command("initial")) }
        try {
            runCurrent()
            assertEquals(1, fixture.api.calls.size)
            fixture.api.calls.single().reply(accepted)

            assertEquals(LoginResult.Authenticated(accepted.accountId, true), pending.await())
            assertEquals(fixture.stored(accepted), fixture.store.value)
            assertEquals(listOf(fixture.stored(accepted)), fixture.store.saves)
            assertEquals(accepted.accountId, fixture.runtime.accountId)
            assertEquals(accepted.accessToken, fixture.runtime.accessToken)
            assertEquals(before.epoch + 1, fixture.runtime.accountEpoch)
            assertEquals(before.revision + 1, fixture.runtime.identityRevision.value)
            assertEquals(0, fixture.store.clears)
        } finally {
            fixture.release(pending)
        }
    }

    private suspend fun TestScope.assertLatestLoginWins(sameAccount: Boolean) {
        val fixture = Fixture()
        val earlyResult = fixture.authenticated("first", "early", settingsCompleted = false)
        val lateResult = fixture.authenticated(if (sameAccount) "first" else "second", "latest")
        val early = async { fixture.repository.login(fixture.command("first")) }
        var late: Deferred<LoginResult>? = null
        try {
            runCurrent()
            assertEquals(1, fixture.api.calls.size)
            val latest = async { fixture.repository.login(fixture.command(if (sameAccount) "first" else "second")) }
            late = latest
            runCurrent()
            assertEquals("Both real login calls must reach the same repository's API", 2, fixture.api.calls.size)

            fixture.api.calls[1].reply(lateResult)
            assertEquals(LoginResult.Authenticated(lateResult.accountId, true), latest.await())
            val newest = fixture.snapshot()
            assertEquals(fixture.stored(lateResult), newest.stored)
            assertEquals(lateResult.accountId, newest.owner)
            assertEquals(lateResult.accessToken, newest.access)

            fixture.api.calls[0].reply(earlyResult)
            val obsolete = try { early.await() } catch (_: CancellationException) { null }

            assertEquals("An older success cannot replace any part of the newer session", newest, fixture.snapshot())
            assertEquals("Only the winning login may save credentials", listOf(fixture.stored(lateResult)), fixture.store.saves)
            assertFalse("A superseded response must not authorize navigation", obsolete is LoginResult.Authenticated)
            assertEquals(2, fixture.api.returnedResponses)
            assertEquals(0, fixture.store.clears)
        } finally {
            fixture.release(early, late)
        }
    }

    private class Fixture {
        private val identity = UUID.randomUUID().toString()
        private val initialOwner = "login-initial-$identity"
        val store = Store(StoredSession(initialOwner, "ISOLATED_INITIAL_REFRESH", false))
        val runtime = SessionRuntime().apply { update("ISOLATED_INITIAL_ACCESS", initialOwner) }
        val api = Api()
        val repository = ProductionSessionRepository(api, store, runtime, InstallationIdentity { "login-installation-$identity" })

        fun command(label: String) = LoginCommand("$label-$identity@example.invalid", "ISOLATED_PASSWORD_NOT_A_CREDENTIAL")
        fun authenticated(owner: String, tokens: String, settingsCompleted: Boolean = true) = SessionApiResult.Authenticated(
            "login-$owner-$identity", "ISOLATED_${tokens}_ACCESS", "ISOLATED_${tokens}_REFRESH", settingsCompleted,
        )
        fun stored(result: SessionApiResult.Authenticated) = StoredSession(result.accountId, result.refreshToken, result.settingsCompleted)
        fun snapshot() = Snapshot(store.value, runtime.accountId, runtime.accessToken, runtime.accountEpoch, runtime.identityRevision.value)

        suspend fun release(vararg jobs: Job?) {
            jobs.forEach { it?.cancel() }
            // Release every suspended non-cooperative callback before any suspending cleanup.
            api.calls.forEach { it.reply(SessionApiResult.Failed(SessionFailure.NETWORK)) }
            jobs.forEach { it?.join() }
        }
    }

    private data class Snapshot(val stored: StoredSession?, val owner: String?, val access: String?, val epoch: Long, val revision: Long)

    private class Store(initial: StoredSession) : SessionCredentialStore {
        var value: StoredSession? = initial
        val saves = mutableListOf<StoredSession>()
        var clears = 0
        override suspend fun load() = value
        override suspend fun save(session: StoredSession) { saves += session; value = session }
        override suspend fun clear() { clears++; value = null }
    }

    private class Api : SessionApi {
        val calls = mutableListOf<PendingLogin>()
        var returnedResponses = 0
        override suspend fun login(email: String, password: String, deviceId: String): SessionApiResult {
            val result = suspendCoroutine { callback: Continuation<SessionApiResult> -> calls += PendingLogin(callback) }
            returnedResponses++
            return result
        }
        override suspend fun refresh(refreshToken: String, deviceId: String): SessionApiResult = error("Login fixture must not refresh")
        override suspend fun revoke(refreshToken: String): Boolean = error("Login fixture must not revoke")
    }

    private class PendingLogin(private var callback: Continuation<SessionApiResult>?) {
        fun reply(result: SessionApiResult) {
            val pending = callback ?: return
            callback = null
            pending.resume(result)
        }
    }
}
