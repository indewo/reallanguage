package com.reallanguage.app.feature.home.data

import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.home.domain.AccountDeletionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AccountDeletionIntegrityTest {
    private val runtime = SessionRuntime().apply { update("isolated-token", "owner") }
    private val wire = object : AccountDeletionWireCodec {
        override fun command(commandId: String) = commandId
        override fun confirmsDeletion(responseBody: String) = responseBody == "confirmed-deleted"
    }

    @Test fun `server failure or timeout never deletes local content or credentials`() = runTest {
        for (timeout in listOf(false, true)) {
            var local = 0; var session = 0
            val journal = InMemoryDeletionJournal()
            val repository = HttpAccountDeletionRepository({ if (timeout) error("isolated timeout") else HttpResponse(503, "") }, runtime,
                { local++; true }, { session++ }, wire, journal, { true })
            assertEquals(AccountDeletionResult.Failed, repository.delete("command"))
            assertEquals(0, local); assertEquals(0, session); assertTrue(journal.entries.isEmpty())
            assertEquals("owner", runtime.accountId)
        }
    }

    @Test fun `HTTP 200 without confirmed deletion cannot authorize data removal`() = runTest {
        var local = 0; var session = 0
        val journal = InMemoryDeletionJournal()
        val repository = HttpAccountDeletionRepository({ HttpResponse(200, "not-deleted") }, runtime,
            { local++; true }, { session++ }, wire, journal, { true })
        assertEquals(AccountDeletionResult.Failed, repository.delete("command"))
        assertEquals(0, local); assertEquals(0, session); assertTrue(journal.entries.isEmpty())
    }

    @Test fun `confirmed receipt precedes revocation and local cleanup and is removed last`() = runTest {
        val events = mutableListOf<String>()
        val journal = InMemoryDeletionJournal(events)
        val repository = HttpAccountDeletionRepository({ events += "server"; HttpResponse(200, "confirmed-deleted") }, runtime,
            { events += "local:$it"; assertNull(runtime.accessToken); true }, { events += "session:$it" }, wire, journal,
            { events += "access:$it"; runtime.clear(); true })
        assertEquals(AccountDeletionResult.Deleted, repository.delete("command"))
        assertEquals(listOf("server", "record", "access:owner", "local:owner", "session:owner", "complete"), events)
        assertTrue(journal.entries.isEmpty())
    }

    @Test fun `failed local cleanup retries only the recorded cleanup without a second DELETE`() = runTest {
        var server = 0; var local = 0
        val journal = InMemoryDeletionJournal()
        val repository = HttpAccountDeletionRepository({ server++; HttpResponse(200, "confirmed-deleted") }, runtime,
            { ++local > 1 }, {}, wire, journal, { runtime.clear(); true })
        assertEquals(AccountDeletionResult.Failed, repository.delete("command"))
        assertEquals(listOf(ConfirmedAccountDeletion("owner", "command")), journal.entries)
        assertNull(runtime.accessToken)
        assertEquals(AccountDeletionResult.Deleted, repository.delete("command"))
        assertEquals(1, server); assertEquals(2, local); assertTrue(journal.entries.isEmpty())
    }

    @Test fun `persisted confirmation resumes after repository recreation without a live session`() = runTest {
        val journal = InMemoryDeletionJournal().apply { entries += ConfirmedAccountDeletion("owner", "command") }
        runtime.clear()
        val cleaned = mutableListOf<String>()
        val repository = HttpAccountDeletionRepository({ error("must not send DELETE again") }, runtime,
            { cleaned += it; true }, {}, wire, journal, { true })
        assertEquals(AccountDeletionResult.Deleted, repository.delete("command"))
        assertEquals(listOf("owner"), cleaned); assertTrue(journal.entries.isEmpty())
    }

    @Test fun `receipt storage failure cannot remove local content or masquerade as deletion complete`() = runTest {
        var local = 0
        val journal = InMemoryDeletionJournal().apply { rejectRecord = true }
        val repository = HttpAccountDeletionRepository({ HttpResponse(200, "confirmed-deleted") }, runtime,
            { local++; true }, {}, wire, journal, { true })
        assertEquals(AccountDeletionResult.Failed, repository.delete("command"))
        assertEquals(0, local)
    }
}

internal class InMemoryDeletionJournal(private val events: MutableList<String>? = null) : AccountDeletionCleanupJournal {
    val entries = mutableListOf<ConfirmedAccountDeletion>()
    var rejectRecord = false
    override suspend fun read() = AccountDeletionCleanupLoad.Ready(entries.toList())
    override suspend fun record(deletion: ConfirmedAccountDeletion): Boolean {
        events?.add("record")
        if (rejectRecord || entries.any { it.accountId == deletion.accountId && it != deletion }) return false
        if (deletion !in entries) entries += deletion
        return true
    }
    override suspend fun complete(deletion: ConfirmedAccountDeletion): Boolean { events?.add("complete"); return entries.remove(deletion) }
}
