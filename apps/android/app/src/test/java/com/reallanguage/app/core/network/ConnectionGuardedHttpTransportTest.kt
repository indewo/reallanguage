package com.reallanguage.app.core.network

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ConnectionGuardedHttpTransportTest {
    private val request = HttpRequest("POST", "/existing-operation", "isolated content")
    private val owner = ConnectionOwner("isolated-account", 1)

    @Test fun anonymousOfflineStartupEpochChangeKeepsTheCoverButMakesExplicitRecoveryPossible() {
        var current = ConnectionOwner(null, 0)
        val gate = ConnectionGuardedHttpTransport(HttpTransport { error("No replay") }) { current }
        gate.networkLost()
        val old = checkNotNull(gate.incident.value)
        current = ConnectionOwner(null, 1) // Empty credential startup clears the anonymous runtime.
        assertTrue(gate.alignVerifiedAnonymousStartup())
        val rebound = checkNotNull(gate.incident.value)
        assertEquals(current, rebound.owner)
        assertFalse(gate.isCurrent(old))
        assertTrue(gate.isCurrent(rebound))
        assertTrue(gate.restoreVerified(rebound)) // Only after the separate network verifier succeeds.
    }

    @Test fun anonymousStartupAlignmentNeverAcknowledgesAnAccountIncidentOrANewLogin() {
        var current = owner
        val gate = ConnectionGuardedHttpTransport(HttpTransport { error("No replay") }) { current }
        gate.networkLost()
        val accountIncident = gate.incident.value
        current = ConnectionOwner(null, 2)
        assertFalse(gate.alignVerifiedAnonymousStartup())
        assertEquals(accountIncident, gate.incident.value)
        gate.networkLost()
        val anonymousIncident = gate.incident.value
        current = ConnectionOwner("new-account", 3)
        assertFalse(gate.alignVerifiedAnonymousStartup())
        assertEquals(anonymousIncident, gate.incident.value)
    }

    @Test fun verifiedAccountClearReleasesOnlyTheOldCoverForTheLoginDestination() {
        var current = owner
        val gate = ConnectionGuardedHttpTransport(HttpTransport { error("No business replay") }) { current }
        gate.networkLost()
        val previous = checkNotNull(gate.incident.value)
        current = ConnectionOwner(null, 2)
        assertTrue(gate.acceptVerifiedSignedOut(previous) { true })
        assertNull(gate.incident.value)
        assertFalse(gate.acceptVerifiedSignedOut(previous) { true })
    }

    @Test fun missingSignOutProofNewLoginAndNewIncidentCannotReleaseTheCover() {
        var current = owner
        val gate = ConnectionGuardedHttpTransport(HttpTransport { error("No business replay") }) { current }
        gate.networkLost()
        val previous = checkNotNull(gate.incident.value)
        current = ConnectionOwner(null, 2)
        assertFalse(gate.acceptVerifiedSignedOut(previous) { false })
        current = ConnectionOwner(owner.accountId, 3)
        assertFalse(gate.acceptVerifiedSignedOut(previous) { true })
        current = ConnectionOwner(null, 4)
        gate.networkLost()
        val latest = gate.incident.value
        assertFalse(gate.acceptVerifiedSignedOut(previous) { true })
        assertEquals(latest, gate.incident.value)
    }

    @Test fun aLaterAnonymousEpochDuringReceiptValidationCannotBeAcknowledgedByAnOlderProof() {
        var current = owner
        val gate = ConnectionGuardedHttpTransport(HttpTransport { error("No business replay") }) { current }
        gate.networkLost()
        val previous = checkNotNull(gate.incident.value)
        current = ConnectionOwner(null, 2)
        assertFalse(gate.acceptVerifiedSignedOut(previous) { current = ConnectionOwner(null, 4); true })
        assertEquals(previous, gate.incident.value)
    }

    @Test fun verifiedNewSessionCanRetireOnlyItsCapturedPriorIncidentWithoutAnyBusinessReplay() {
        for (newOwner in listOf(ConnectionOwner("other-account", 2), ConnectionOwner(owner.accountId, 2))) {
            var current = owner
            var calls = 0
            val gate = ConnectionGuardedHttpTransport(HttpTransport { calls++; HttpResponse(200, "isolated") }) { current }
            gate.networkLost()
            val captured = gate.incident.value
            current = newOwner
            assertTrue(gate.acceptVerifiedNewSession(captured))
            assertNull(gate.incident.value)
            assertEquals(0, calls)
            assertFalse(gate.acceptVerifiedNewSession(captured))
        }
    }

    @Test fun loginCompletionCannotOpenANewerIncidentAnUnchangedOldSessionOrASignedOutOwner() {
        var current = owner
        val gate = ConnectionGuardedHttpTransport(HttpTransport { error("No business replay") }) { current }
        gate.networkLost()
        val old = gate.incident.value
        assertFalse(gate.acceptVerifiedNewSession(old))
        current = ConnectionOwner(null, 2)
        assertFalse(gate.acceptVerifiedNewSession(old))
        current = ConnectionOwner("new-account", 3)
        gate.networkLost()
        val latest = gate.incident.value
        assertFalse(gate.acceptVerifiedNewSession(old))
        assertFalse(gate.acceptVerifiedNewSession(null))
        assertEquals(latest, gate.incident.value)
    }

    @Test fun anIoFailureBlocksNewRequestsUntilTheExactIncidentIsVerifiedWithoutReplay() = runTest {
        var calls = 0
        val gate = ConnectionGuardedHttpTransport(HttpTransport { calls++; throw IOException("isolated network failure") }) { owner }
        assertTrue(runCatching { gate.execute(request) }.exceptionOrNull() is IOException)
        val incident = gate.incident.value
        assertNotNull(incident)
        assertEquals(owner, incident!!.owner)
        repeat(3) { assertTrue(runCatching { gate.execute(request) }.exceptionOrNull() is IOException) }
        assertEquals(1, calls)
        assertTrue(gate.restoreVerified(incident))
        assertNull(gate.incident.value)
        assertEquals("Verification never replays the original paid operation", 1, calls)
        assertFalse(gate.restoreVerified(incident))
    }

    @Test fun serviceResponsesAndCallerCancellationAreNotNetworkFailures() = runTest {
        for (status in listOf(200, 400, 402, 403, 409, 429, 500, 503)) {
            val response = HttpResponse(status, "isolated")
            val gate = ConnectionGuardedHttpTransport(HttpTransport { response }) { owner }
            assertSame(response, gate.execute(request))
            assertNull(gate.incident.value)
        }
        val cancelled = CancellationException("caller cancelled")
        val gate = ConnectionGuardedHttpTransport(HttpTransport { throw cancelled }) { owner }
        assertSame(cancelled, runCatching { gate.execute(request) }.exceptionOrNull())
        assertNull(gate.incident.value)
    }

    @Test fun anExpiredProtectedSessionRequiresCheckingButBadLoginCredentialsDoNot() = runTest {
        val gate = ConnectionGuardedHttpTransport(HttpTransport { HttpResponse(401, "isolated") }) { owner }
        gate.execute(request)
        assertNull(gate.incident.value)
        gate.execute(request.copy(headers = mapOf("Authorization" to "Bearer ISOLATED_NOT_A_CREDENTIAL")))
        assertNotNull(gate.incident.value)
    }

    @Test fun anOldAccountFailureCannotBlockOrUnlockANewerLogin() = runTest {
        var current = owner
        val response = CompletableDeferred<Unit>()
        val gate = ConnectionGuardedHttpTransport(HttpTransport { response.await(); throw IOException("old") }) { current }
        val old = async { runCatching { gate.execute(request) } }
        kotlinx.coroutines.yield()
        current = ConnectionOwner("other-account", 2)
        response.complete(Unit)
        old.await()
        assertNull(gate.incident.value)
        gate.networkLost()
        val incident = checkNotNull(gate.incident.value)
        current = ConnectionOwner("other-account", 4) // Logout and login, even the same ID.
        assertFalse(gate.restoreVerified(incident))
    }

    @Test fun losingNetworkWithoutAnyHttpRequestStillClosesLocalContentAccess() {
        val gate = ConnectionGuardedHttpTransport(HttpTransport { error("No network request") }) { owner }
        gate.networkLost()
        val incident = checkNotNull(gate.incident.value)
        gate.networkLost()
        assertEquals("Repeated loss notifications do not create repeated recovery entries", incident, gate.incident.value)
    }

    @Test fun aLateFailureFromBeforeSuccessfulRecoveryCannotCloseTheNewConnectionAgain() = runTest {
        val pending = CompletableDeferred<Unit>()
        val gate = ConnectionGuardedHttpTransport(HttpTransport { pending.await(); throw IOException("old connection") }) { owner }
        val old = async { runCatching { gate.execute(request) } }
        kotlinx.coroutines.yield()
        gate.networkLost()
        assertTrue(gate.restoreVerified(checkNotNull(gate.incident.value)))
        pending.complete(Unit)
        old.await()
        assertNull(gate.incident.value)
    }
}
