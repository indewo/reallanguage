package com.reallanguage.app.feature.home.data

import com.reallanguage.app.core.session.DeletedAccountLocalAccess
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.StoredSession
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReminderDeletionIdentityTest {
    @Test fun retainedCredentialDuringConfirmedDeletionCleanupCannotAuthorizeANewReceiver() = runTest {
        val credentials = Credentials()
        DeletedAccountLocalAccess.revoke(credentials.value.accountId)
        assertNull(StoredSessionReminderLocalIdentity.load(credentials) { true })
        assertEquals(0, credentials.mutations)
    }

    @Test fun receiverCapturedBeforeConfirmedDeletionLosesAuthorityEvenBeforeCredentialCleanup() = runTest {
        val credentials = Credentials()
        val identity = requireNotNull(StoredSessionReminderLocalIdentity.load(credentials) { true })
        assertTrue(identity.isCurrent())
        DeletedAccountLocalAccess.revoke(credentials.value.accountId)
        assertFalse(identity.isCurrent())
        assertEquals(0, credentials.mutations)
    }

    private class Credentials : SessionCredentialStore {
        val value = StoredSession("reminder-deletion-${UUID.randomUUID()}", "isolated-refresh", true)
        var mutations = 0
        override suspend fun load() = value
        override suspend fun save(session: StoredSession) { mutations++ }
        override suspend fun clear() { mutations++ }
    }
}
