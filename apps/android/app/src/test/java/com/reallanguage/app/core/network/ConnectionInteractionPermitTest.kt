package com.reallanguage.app.core.network

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Interaction authorization only: no Android runtime, credentials, permissions or HTTP. */
class ConnectionInteractionPermitTest {
    @Test fun currentSignedInOwnerWithoutAnIncidentReceivesAUsablePermit() {
        val fixture = Fixture()
        val permit = fixture.permit()

        assertTrue(permit())
        assertTrue("Checking a current permit is not a one-shot consumption", permit())
        assertNull(fixture.gate.incident.value)
        assertEquals(0, fixture.httpCalls)
    }

    @Test fun networkLossRejectsNewPermitsAndInvalidatesAnExistingPermit() {
        val fixture = Fixture()
        val permit = fixture.permit()

        fixture.gate.networkLost()

        assertNotNull(fixture.gate.incident.value)
        assertNull(fixture.gate.interactionPermit())
        assertFalse("A permission callback held before the cover may no longer act", permit())
        assertEquals(0, fixture.httpCalls)
    }

    @Test fun verifiedRecoveryForTheSameOwnerDoesNotReviveAPreviousPermissionCallback() {
        val fixture = Fixture()
        val beforeLoss = fixture.permit()
        val owner = fixture.currentOwner
        fixture.gate.networkLost()
        val incident = requireNotNull(fixture.gate.incident.value)

        assertTrue(fixture.gate.restoreVerified(incident))

        assertEquals(owner, fixture.currentOwner)
        assertNull(fixture.gate.incident.value)
        assertFalse("Matching account and epoch cannot revive a pre-interruption request", beforeLoss())
        assertTrue("Only a new explicit interaction may acquire the restored connection", fixture.permit()())
        assertEquals(0, fixture.httpCalls)
    }

    @Test fun switchingAccountsOrStartingANewEpochInvalidatesTheOldPermit() {
        for (sameAccount in listOf(false, true)) {
            val fixture = Fixture()
            val previous = fixture.currentOwner
            val permit = fixture.permit()
            fixture.currentOwner = if (sameAccount) previous.copy(epoch = previous.epoch + 1)
            else ConnectionOwner("other-${UUID.randomUUID()}", previous.epoch)

            assertFalse("The permit belongs to the exact account and epoch", permit())
            assertTrue(fixture.permit()())
            assertEquals(0, fixture.httpCalls)
        }
    }

    @Test fun ordinaryTokenRefreshWithUnchangedOwnerAndEpochKeepsThePermitUsable() {
        val fixture = Fixture()
        val permit = fixture.permit()
        // Tokens are deliberately outside ConnectionOwner; a refresh preserves this value.
        fixture.currentOwner = fixture.currentOwner.copy()

        assertTrue(permit())
        assertTrue(fixture.permit()())
        assertNull(fixture.gate.incident.value)
        assertEquals(0, fixture.httpCalls)
    }

    @Test fun anAnonymousOwnerCannotAcquireAPermitAndSignOutRevokesTheOldOne() {
        val fixture = Fixture()
        val previous = fixture.permit()
        fixture.currentOwner = ConnectionOwner(null, fixture.currentOwner.epoch + 1)

        assertNull(fixture.gate.interactionPermit())
        assertFalse(previous())
        assertEquals(0, fixture.httpCalls)
    }

    private class Fixture {
        var currentOwner = ConnectionOwner("permit-${UUID.randomUUID()}", 7)
        var httpCalls = 0
        val gate = ConnectionGuardedHttpTransport(HttpTransport {
            httpCalls++
            error("Interaction permits must never execute or replay HTTP")
        }) { currentOwner }

        fun permit(): () -> Boolean {
            val value = gate.interactionPermit()
            assertNotNull("A verified signed-in owner must receive an interaction permit", value)
            return requireNotNull(value)
        }
    }
}
