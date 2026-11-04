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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Non-cooperative in-memory persistence only: no Android credentials, real passwords, or HTTP. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductionSessionLoginCommitTest {
    @Test fun cancellationDuringSaveRestoresOriginalCredentialsWithoutPublishingNewRuntime() = runTest {
        val fixture = Fixture()
        val before = fixture.snapshot()
        val response = fixture.authenticated("first")
        fixture.store.pauseNextSave = true
        val pending = launch { fixture.repository.login(fixture.command("first")) }
        try {
            runCurrent()
            fixture.api.calls.single().reply(response)
            runCurrent()
            assertTrue("The actual credential save must be suspended", fixture.store.saveWaiting)
            assertEquals(before, fixture.snapshot())

            pending.cancel()
            runCurrent()
            assertFalse("The save deliberately ignores cancellation until its callback", pending.isCompleted)
            fixture.store.releaseSave()
            runCurrent()
            pending.join()

            assertTrue("The late save must really write before returning", fixture.stored(response) in fixture.store.writes)
            assertEquals("Cancellation must restore the full prior persisted and runtime session", before, fixture.snapshot())
        } finally {
            fixture.release(pending)
        }
    }

    @Test fun laterLoginIntentRevokesAnEarlierSuspendedSaveBeforeItCanAuthenticate() = runTest {
        val fixture = Fixture()
        val before = fixture.snapshot()
        val firstResponse = fixture.authenticated("first")
        val secondResponse = fixture.authenticated("second")
        fixture.store.pauseNextSave = true
        val first = async { fixture.repository.login(fixture.command("first")) }
        var second: Deferred<LoginResult>? = null
        try {
            runCurrent()
            fixture.api.calls.single().reply(firstResponse)
            runCurrent()
            assertTrue(fixture.store.saveWaiting)
            var secondEntered = false
            val latest = async {
                secondEntered = true
                fixture.repository.login(fixture.command("second"))
            }
            second = latest
            runCurrent()
            assertTrue("The newer login intent starts while the old save owns the lock", secondEntered)
            // A new request may be waiting for credential capture; it need not reach HTTP yet.
            fixture.store.releaseSave()
            runCurrent()
            val obsolete = first.resultOrCancellation()

            assertTrue("The old save actually completed its write", fixture.stored(firstResponse) in fixture.store.writes)
            assertFalse("An intent superseded during save cannot authenticate", obsolete is LoginResult.Authenticated)
            assertEquals("Before B responds, A must leave no credential or runtime pollution", before, fixture.snapshot())
            assertEquals(2, fixture.api.calls.size)
            fixture.api.calls[1].reply(secondResponse)
            assertEquals(LoginResult.Authenticated(secondResponse.accountId, true), latest.await())
            assertEquals(fixture.stored(secondResponse), fixture.store.value)
            assertEquals(secondResponse.accountId, fixture.runtime.accountId)
            assertEquals(secondResponse.accessToken, fixture.runtime.accessToken)
            assertEquals(before.epoch + 1, fixture.runtime.accountEpoch)
            assertEquals(before.revision + 1, fixture.runtime.identityRevision.value)
        } finally {
            fixture.release(first, second)
        }
    }

    @Test fun failedLaterLoginStillPreventsEarlierSuccessFromAutomaticallyLoggingIn() = runTest {
        val fixture = Fixture()
        val before = fixture.snapshot()
        val first = async { fixture.repository.login(fixture.command("first")) }
        var second: Deferred<LoginResult>? = null
        try {
            runCurrent()
            val latest = async { fixture.repository.login(fixture.command("second")) }
            second = latest
            runCurrent()
            assertEquals(2, fixture.api.calls.size)
            fixture.api.calls[1].reply(SessionApiResult.InvalidOrExpired)
            assertEquals(LoginResult.InvalidCredentials, latest.await())
            assertEquals("B's failure does not change credentials or runtime identity", before, fixture.snapshot())

            fixture.api.calls[0].reply(fixture.authenticated("first"))
            val obsolete = first.resultOrCancellation()

            assertFalse("A failed latest intent must not fall back to an old success", obsolete is LoginResult.Authenticated)
            assertEquals(before, fixture.snapshot())
            assertTrue("The stale success must not attempt persistence", fixture.store.writes.isEmpty())
        } finally {
            fixture.release(first, second)
        }
    }

    private suspend fun Deferred<LoginResult>.resultOrCancellation(): LoginResult? =
        try { await() } catch (_: CancellationException) { null }

    private class Fixture {
        private val identity = UUID.randomUUID().toString()
        private val initialOwner = "commit-initial-$identity"
        val store = Store(StoredSession(initialOwner, "ISOLATED_OLD_REFRESH", false))
        val runtime = SessionRuntime().apply { update("ISOLATED_OLD_ACCESS", initialOwner) }
        val api = Api()
        val repository = ProductionSessionRepository(api, store, runtime, InstallationIdentity { "commit-installation-$identity" })
        fun command(label: String) = LoginCommand("$label-$identity@example.invalid", "ISOLATED_PASSWORD_NOT_A_CREDENTIAL")
        fun authenticated(label: String) = SessionApiResult.Authenticated(
            "commit-$label-$identity", "ISOLATED_${label}_ACCESS", "ISOLATED_${label}_REFRESH", true,
        )
        fun stored(result: SessionApiResult.Authenticated) = StoredSession(result.accountId, result.refreshToken, result.settingsCompleted)
        fun snapshot() = Snapshot(store.value, runtime.accountId, runtime.accessToken, runtime.accountEpoch, runtime.identityRevision.value)
        suspend fun release(vararg jobs: Job?) {
            jobs.forEach { it?.cancel() }
            // Release all non-cooperative work, including a save holding the shared mutex, first.
            api.calls.forEach { it.reply(SessionApiResult.Failed(SessionFailure.NETWORK)) }
            store.releaseSave()
            jobs.forEach { it?.join() }
        }
    }

    private data class Snapshot(val stored: StoredSession?, val owner: String?, val access: String?, val epoch: Long, val revision: Long)

    private class Store(initial: StoredSession) : SessionCredentialStore {
        var value: StoredSession? = initial
        var pauseNextSave = false
        private var saveCallback: Pending<Unit>? = null
        val saveWaiting get() = saveCallback != null
        val writes = mutableListOf<StoredSession>()
        override suspend fun load() = value
        override suspend fun save(session: StoredSession) {
            if (pauseNextSave) {
                pauseNextSave = false
                suspendCoroutine<Unit> { saveCallback = Pending(it) }
            }
            // This is an actual write after the callback, even when the caller was cancelled.
            value = session
            writes += session
        }
        override suspend fun clear() { value = null }
        fun releaseSave() {
            val pending = saveCallback
            saveCallback = null
            pending?.reply(Unit)
        }
    }

    private class Api : SessionApi {
        val calls = mutableListOf<Pending<SessionApiResult>>()
        override suspend fun login(email: String, password: String, deviceId: String): SessionApiResult =
            suspendCoroutine { calls += Pending(it) }
        override suspend fun refresh(refreshToken: String, deviceId: String): SessionApiResult = error("Login fixture must not refresh")
        override suspend fun revoke(refreshToken: String): Boolean = error("Login fixture must not revoke")
    }

    private class Pending<T>(private var callback: Continuation<T>?) {
        fun reply(value: T) {
            val pending = callback ?: return
            callback = null
            pending.resume(value)
        }
    }
}
