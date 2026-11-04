package com.reallanguage.app.feature.error.data

import com.reallanguage.app.core.navigation.AppStartupRepository
import com.reallanguage.app.core.navigation.AppStartupResult
import com.reallanguage.app.core.network.ConnectionGuardedHttpTransport
import com.reallanguage.app.core.network.ConnectionIncident
import com.reallanguage.app.core.network.ConnectionOwner
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.feature.error.domain.ConnectionRecoveryResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logged-in recovery only. The real gate is driven by in-memory owners; no HTTP or credentials. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountConnectionRecoveryRepositoryTest {
    @Test fun anonymousServiceRecoveryKeepsTheCurrentFormAndNeverRequiresOrPerformsLogin() = runTest {
        val fixture = Fixture(ConnectionOwner(null, 3))
        fixture.account = { AppStartupResult.SignedOut }
        assertEquals(ConnectionRecoveryResult.Restored, fixture.repository().verifyNetworkAndAccount())
        assertEquals(listOf("service", "account"), fixture.order)
        assertNull(fixture.gate.incident.value)
        assertEquals(0, fixture.businessCalls)
    }

    @Test fun anonymousRecoveryCannotUnlockAfterLoginStartsANewIdentity() = runTest {
        val fixture = Fixture(ConnectionOwner(null, 3))
        fixture.account = {
            fixture.owner = ConnectionOwner("new-account", 4)
            AppStartupResult.SignedOut
        }
        assertEquals(ConnectionRecoveryResult.Unavailable, fixture.repository().verifyNetworkAndAccount())
        fixture.assertBlockedBy(fixture.original)
    }

    @Test fun anonymousCleanupFailureCannotPretendTheEntryIsReady() = runTest {
        val fixture = Fixture(ConnectionOwner(null, 3))
        fixture.account = { AppStartupResult.ConnectionUnavailable }
        assertEquals(ConnectionRecoveryResult.Unavailable, fixture.repository().verifyNetworkAndAccount())
        fixture.assertBlockedBy(fixture.original)
    }

    @Test fun serviceMustBeReachableBeforeAccountVerificationAndOnlyItsExactIncidentIsUnlocked() = runTest {
        val fixture = Fixture()
        fixture.service = { assertEquals(fixture.original, fixture.gate.incident.value); true }
        fixture.account = {
            assertEquals(fixture.original, fixture.gate.incident.value)
            AppStartupResult.SignedIn(settingsCompleted = true)
        }
        val repository = fixture.repository()
        assertEquals(ConnectionRecoveryResult.Restored, repository.verifyNetworkAndAccount())
        assertEquals(listOf("service", "account"), fixture.order)
        assertNull(fixture.gate.incident.value)
        assertEquals(0, fixture.businessCalls)
        assertEquals("A consumed incident cannot start another account check", ConnectionRecoveryResult.Unavailable, repository.verifyNetworkAndAccount())
        assertEquals(listOf("service", "account"), fixture.order)
    }

    @Test fun unreachableServiceRetainsTheIncidentWithoutCheckingAccountOrReplayingBusiness() = runTest {
        val fixture = Fixture()
        fixture.service = { false }
        assertEquals(ConnectionRecoveryResult.Unavailable, fixture.repository().verifyNetworkAndAccount())
        assertEquals(listOf("service"), fixture.order)
        fixture.assertBlockedBy(fixture.original)
    }

    @Test fun serviceExceptionsBecomeUnavailableWithoutAnAccountCheck() = runTest {
        for (failure in listOf(IOException("isolated reachability failure"), IllegalStateException("isolated probe failure"))) {
            val fixture = Fixture()
            fixture.service = { throw failure }
            assertEquals(ConnectionRecoveryResult.Unavailable, fixture.repository().verifyNetworkAndAccount())
            assertEquals(listOf("service"), fixture.order)
            fixture.assertBlockedBy(fixture.original)
        }
    }

    @Test fun signedOutAndIncompleteSettingsFollowTheirDistinctStartupDestinationsWithoutUnlockingLocalContent() = runTest {
        for ((result, destination) in listOf(AppStartupResult.SignedOut to ConnectionRecoveryResult.LoginRequired,
            AppStartupResult.SignedIn(settingsCompleted = false) to ConnectionRecoveryResult.InitialSettingsRequired)) {
            val fixture = Fixture()
            fixture.account = { result }
            assertEquals(destination, fixture.repository().verifyNetworkAndAccount())
            assertEquals(listOf("service", "account"), fixture.order)
            fixture.assertBlockedBy(fixture.original)
        }
    }

    @Test fun unavailableOrExceptionalAccountCheckCannotUnlockTheIncident() = runTest {
        val checks: List<suspend () -> AppStartupResult> = listOf(
            { AppStartupResult.ConnectionUnavailable },
            { throw IOException("isolated account connection failure") },
            { throw IllegalStateException("isolated account response failure") },
        )
        for (check in checks) {
            val fixture = Fixture()
            fixture.account = check
            assertEquals(ConnectionRecoveryResult.Unavailable, fixture.repository().verifyNetworkAndAccount())
            assertEquals(listOf("service", "account"), fixture.order)
            fixture.assertBlockedBy(fixture.original)
        }
    }

    @Test fun changedAccountOrSameAccountReloginRejectsTheOldRepositoryBeforeAnyChecks() = runTest {
        for (newOwner in changedOwners) {
            val fixture = Fixture()
            val repository = fixture.repository()
            fixture.owner = newOwner
            // Keep the old incident visible: comparing only incident equality is insufficient.
            assertEquals(fixture.original, fixture.gate.incident.value)
            assertEquals(ConnectionRecoveryResult.Unavailable, repository.verifyNetworkAndAccount())
            assertTrue(fixture.order.isEmpty())
            fixture.assertBlockedBy(fixture.original)
        }
    }

    @Test fun supersededIncidentForTheSameOwnerCannotCheckOrUnlockANewerFailure() = runTest {
        val fixture = Fixture()
        val repository = fixture.repository()
        val replacement = fixture.replaceIncident()
        assertEquals(ConnectionRecoveryResult.Unavailable, repository.verifyNetworkAndAccount())
        assertTrue(fixture.order.isEmpty())
        fixture.assertBlockedBy(replacement)
    }

    @Test fun ownerChangingDuringTheProbePreventsEvenStartingAccountVerification() = runTest {
        for (newOwner in changedOwners) {
            val fixture = Fixture()
            val response = CompletableDeferred<Boolean>()
            fixture.service = { response.await() }
            val result = async { fixture.repository().verifyNetworkAndAccount() }
            try {
                runCurrent()
                assertEquals(listOf("service"), fixture.order)
                fixture.owner = newOwner
                fixture.gate.networkLost()
                val replacement = checkNotNull(fixture.gate.incident.value)
                response.complete(true)
                assertEquals(ConnectionRecoveryResult.Unavailable, result.await())
                assertEquals("A stale probe must not borrow the new session for startup", listOf("service"), fixture.order)
                fixture.assertBlockedBy(replacement)
            } finally {
                response.complete(false)
                result.cancel()
                runCurrent()
            }
        }
    }

    @Test fun newerIncidentDuringTheProbePreventsStartingAccountVerification() = runTest {
        val fixture = Fixture()
        val response = CompletableDeferred<Boolean>()
        fixture.service = { response.await() }
        val result = async { fixture.repository().verifyNetworkAndAccount() }
        try {
            runCurrent()
            assertEquals(listOf("service"), fixture.order)
            val replacement = fixture.replaceIncident()
            response.complete(true)
            assertEquals(ConnectionRecoveryResult.Unavailable, result.await())
            assertEquals("The same owner does not authorize an obsolete incident", listOf("service"), fixture.order)
            fixture.assertBlockedBy(replacement)
        } finally {
            response.complete(false)
            result.cancel()
            runCurrent()
        }
    }

    @Test fun ownerChangingDuringAccountVerificationCannotRestoreOrLogOutTheNewSession() = runTest {
        for (newOwner in changedOwners) {
            for (late in listOf(AppStartupResult.SignedIn(settingsCompleted = true), AppStartupResult.SignedOut)) {
                val fixture = Fixture()
                val response = CompletableDeferred<AppStartupResult>()
                fixture.account = { response.await() }
                val result = async { fixture.repository().verifyNetworkAndAccount() }
                try {
                    runCurrent()
                    assertEquals(listOf("service", "account"), fixture.order)
                    fixture.owner = newOwner
                    fixture.gate.networkLost()
                    val replacement = checkNotNull(fixture.gate.incident.value)
                    response.complete(late)
                    assertEquals(ConnectionRecoveryResult.Unavailable, result.await())
                    fixture.assertBlockedBy(replacement)
                } finally {
                    response.complete(AppStartupResult.ConnectionUnavailable)
                    result.cancel()
                    runCurrent()
                }
            }
        }
    }

    @Test fun newerIncidentDuringAccountVerificationCannotBeUnlockedByTheOldSuccess() = runTest {
        val fixture = Fixture()
        val response = CompletableDeferred<AppStartupResult>()
        fixture.account = { response.await() }
        val result = async { fixture.repository().verifyNetworkAndAccount() }
        try {
            runCurrent()
            assertEquals(listOf("service", "account"), fixture.order)
            val replacement = fixture.replaceIncident()
            response.complete(AppStartupResult.SignedIn(settingsCompleted = true))
            assertEquals(ConnectionRecoveryResult.Unavailable, result.await())
            fixture.assertBlockedBy(replacement)
        } finally {
            response.complete(AppStartupResult.ConnectionUnavailable)
            result.cancel()
            runCurrent()
        }
    }

    @Test fun cancellationFromEitherCheckKeepsItsOriginalCauseAndNeverUnlocks() = runTest {
        for (cancelProbe in listOf(true, false)) {
            val fixture = Fixture()
            val cancellation = CancellationException("isolated ${if (cancelProbe) "probe" else "account"} cancellation")
            if (cancelProbe) fixture.service = { throw cancellation } else fixture.account = { throw cancellation }
            val failure = runCatching { fixture.repository().verifyNetworkAndAccount() }.exceptionOrNull()
            assertTrue("Cancellation is not an Unavailable response", failure is CancellationException)
            assertTrue(generateSequence(failure) { it.cause }.take(16).any {
                it is CancellationException && it.message == cancellation.message
            })
            assertEquals(if (cancelProbe) listOf("service") else listOf("service", "account"), fixture.order)
            fixture.assertBlockedBy(fixture.original)
        }
    }

    @Test fun lateAccountExceptionCannotClearANewerIncidentOrCloseAnAlreadyRecoveredConnection() = runTest {
        for (newFailure in listOf(false, true)) {
            val fixture = Fixture()
            val response = CompletableDeferred<AppStartupResult>()
            fixture.account = { response.await() }
            val result = async { fixture.repository().verifyNetworkAndAccount() }
            try {
                runCurrent()
                assertEquals(listOf("service", "account"), fixture.order)
                assertTrue(fixture.gate.restoreVerified(fixture.original))
                if (newFailure) fixture.gate.networkLost()
                val newerState = fixture.gate.incident.value
                response.completeExceptionally(IOException("isolated late account failure"))
                assertEquals(ConnectionRecoveryResult.Unavailable, result.await())
                assertEquals("Late failure must not mutate newer connection state", newerState, fixture.gate.incident.value)
                assertEquals(0, fixture.businessCalls)
            } finally {
                response.complete(AppStartupResult.ConnectionUnavailable)
                result.cancel()
                runCurrent()
            }
        }
    }

    private class Fixture(var owner: ConnectionOwner = ConnectionOwner("isolated-account-a", 7)) {
        var businessCalls = 0
        val gate = ConnectionGuardedHttpTransport(HttpTransport {
            businessCalls++
            HttpResponse(200, "isolated unused business response")
        }) { owner }
        val original: ConnectionIncident = run {
            gate.networkLost()
            checkNotNull(gate.incident.value)
        }
        val order = mutableListOf<String>()
        var service: suspend () -> Boolean = { true }
        var account: suspend () -> AppStartupResult = { AppStartupResult.SignedIn(settingsCompleted = true) }

        fun repository() = AccountConnectionRecoveryRepository(
            incident = original,
            gate = gate,
            startup = AppStartupRepository { order += "account"; account() },
            serviceReachable = { order += "service"; service() },
        )

        fun replaceIncident(): ConnectionIncident {
            assertTrue(gate.restoreVerified(original))
            gate.networkLost()
            return checkNotNull(gate.incident.value).also {
                assertEquals(original.owner, it.owner)
                assertTrue(it.generation > original.generation)
            }
        }

        fun assertBlockedBy(expected: ConnectionIncident) {
            assertEquals(expected, gate.incident.value)
            assertEquals("Recovery cannot replay a business operation", 0, businessCalls)
        }
    }

    private val changedOwners = listOf(
        ConnectionOwner("isolated-account-b", 8),
        ConnectionOwner("isolated-account-a", 9),
    )
}
