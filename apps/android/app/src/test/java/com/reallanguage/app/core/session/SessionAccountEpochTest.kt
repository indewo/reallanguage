package com.reallanguage.app.core.session

import org.junit.Assert.*
import org.junit.Test

class SessionAccountEpochTest {
    @Test fun tokenRefreshKeepsTheAuthenticatedAccountEpochButInvalidatesInFlightTokenWork() {
        val runtime = SessionRuntime().apply { update("first", "owner") }
        val ownerEpoch = runtime.accountEpoch
        val tokenRevision = runtime.identityRevision.value
        runtime.update("refreshed", "owner")
        assertEquals(ownerEpoch, runtime.accountEpoch)
        assertTrue(runtime.identityRevision.value > tokenRevision)
    }
    @Test fun logoutAndSameAccountLoginCannotReviveAnOldOwner() {
        val runtime = SessionRuntime().apply { update("first", "owner") }
        val ownerEpoch = runtime.accountEpoch
        runtime.clear(); runtime.update("new-login", "owner")
        assertNotEquals(ownerEpoch, runtime.accountEpoch)
    }
    @Test fun accountReplacementChangesTheEpochEvenWhenTheTokenIsTheSame() {
        val runtime = SessionRuntime().apply { update("isolated", "owner") }
        val ownerEpoch = runtime.accountEpoch
        runtime.update("isolated", "other-owner")
        assertNotEquals(ownerEpoch, runtime.accountEpoch)
    }
}
