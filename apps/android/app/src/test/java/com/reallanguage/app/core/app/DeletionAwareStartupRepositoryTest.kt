package com.reallanguage.app.core.app

import com.reallanguage.app.core.navigation.*
import com.reallanguage.app.feature.home.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DeletionAwareStartupRepositoryTest {
    @Test fun pendingCleanupCompletesBeforeAnySessionRefresh() = runTest {
        val events = mutableListOf<String>()
        val journal = Journal(mutableListOf(ConfirmedAccountDeletion("old", "command")))
        val gate = DeletionAwareStartupRepository(journal, { events += "cleanup"; journal.entries.remove(it) },
            { events += "refresh"; AppStartupResult.SignedOut })
        assertEquals(AppStartupResult.SignedOut, gate.resolve())
        assertEquals(listOf("cleanup", "refresh"), events)
    }

    @Test fun failedOrUnreadableReceiptCannotRefreshOrPretendToBeSignedOut() = runTest {
        var refresh = 0
        val journal = Journal(mutableListOf(ConfirmedAccountDeletion("old", "command")))
        val gate = DeletionAwareStartupRepository(journal, { false }, { refresh++; AppStartupResult.SignedOut })
        assertEquals(AppStartupResult.ConnectionUnavailable, gate.resolve())
        journal.unavailable = true
        assertEquals(AppStartupResult.ConnectionUnavailable, gate.resolve())
        assertEquals(0, refresh)
    }

    @Test fun cleanupClaimWithoutReceiptReadbackCannotExposeTheApp() = runTest {
        var refresh = 0
        val gate = DeletionAwareStartupRepository(Journal(mutableListOf(ConfirmedAccountDeletion("old", "command"))),
            { true }, { refresh++; AppStartupResult.SignedIn(true) })
        assertEquals(AppStartupResult.ConnectionUnavailable, gate.resolve())
        assertEquals(0, refresh)
    }

    @Test fun cancellationDoesNotFallThroughToRefresh() = runTest {
        var refresh = 0
        val gate = DeletionAwareStartupRepository(Journal(mutableListOf(ConfirmedAccountDeletion("old", "command"))),
            { throw CancellationException("cancelled fixture") }, { refresh++; AppStartupResult.SignedOut })
        try { gate.resolve(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(0, refresh)
    }

    @Test fun noReceiptPreservesExistingStartupBehavior() = runTest {
        val gate = DeletionAwareStartupRepository(Journal(mutableListOf()), { error("no cleanup") }, { AppStartupResult.SignedIn(true) })
        assertEquals(AppStartupResult.SignedIn(true), gate.resolve())
    }
    private class Journal(val entries: MutableList<ConfirmedAccountDeletion>) : AccountDeletionCleanupJournal {
        var unavailable = false
        override suspend fun read(): AccountDeletionCleanupLoad = if (unavailable) AccountDeletionCleanupLoad.Unavailable else AccountDeletionCleanupLoad.Ready(entries.toList())
        override suspend fun record(deletion: ConfirmedAccountDeletion) = false
        override suspend fun complete(deletion: ConfirmedAccountDeletion) = false
    }
}
