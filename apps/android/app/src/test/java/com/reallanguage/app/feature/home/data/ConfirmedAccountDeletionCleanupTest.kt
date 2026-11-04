package com.reallanguage.app.feature.home.data

import com.reallanguage.app.core.session.DeletedAccountLocalAccess
import com.reallanguage.app.core.session.SessionRuntime
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ConfirmedAccountDeletionCleanupTest {
    @Test fun `startup cleanup requires recorded confirmation and removes receipt only after every step succeeds`() = runTest {
        val events = mutableListOf<String>()
        val deletion = ConfirmedAccountDeletion("owner", "command")
        val journal = InMemoryDeletionJournal(events).apply { entries += deletion }
        val cleanup = ConfirmedAccountDeletionCleanup(journal, { events += "local:$it"; true },
            { events += "access:$it"; true }, { events += "session:$it" })
        assertTrue(cleanup.complete(deletion))
        assertEquals(listOf("access:owner", "local:owner", "session:owner", "complete"), events)
        assertTrue(journal.entries.isEmpty())
    }

    @Test fun `local failure preserves the receipt and never clears credentials or reports done`() = runTest {
        val events = mutableListOf<String>()
        val deletion = ConfirmedAccountDeletion("owner", "command")
        val journal = InMemoryDeletionJournal(events).apply { entries += deletion }
        val cleanup = ConfirmedAccountDeletionCleanup(journal, { events += "local"; false },
            { events += "access"; true }, { events += "session" })
        assertFalse(cleanup.complete(deletion))
        assertEquals(listOf("access", "local"), events)
        assertEquals(listOf(deletion), journal.entries)
    }

    @Test fun `arbitrary account or wrong command without a stored confirmation has zero cleanup effects`() = runTest {
        val journal = InMemoryDeletionJournal().apply { entries += ConfirmedAccountDeletion("owner", "command") }
        var effects = 0
        val cleanup = ConfirmedAccountDeletionCleanup(journal, { effects++; true }, { effects++; true }, { effects++ })
        assertFalse(cleanup.complete(ConfirmedAccountDeletion("other", "command")))
        assertFalse(cleanup.complete(ConfirmedAccountDeletion("owner", "wrong-command")))
        assertEquals(0, effects)
    }

    @Test fun `revoking a confirmed old account blocks stale keys but cannot log out a different current account`() = runTest {
        val deleted = "deleted-${UUID.randomUUID()}"
        val current = "current-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("new-token", current) }
        assertTrue(RuntimeDeletedAccountAccess(runtime).revoke(deleted))
        assertEquals(current, runtime.accountId)
        assertFalse(DeletedAccountLocalAccess.allowsAccountHash(hash(deleted)))
        assertTrue(DeletedAccountLocalAccess.allowsAccountHash(hash(current)))
        assertTrue(RuntimeDeletedAccountAccess(runtime).revoke(current))
        assertNull(runtime.accessToken); assertNull(runtime.accountId)
    }
    private fun hash(account: String) = MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString("") { "%02x".format(it) }
}
