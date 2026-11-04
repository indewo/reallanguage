package com.reallanguage.app.feature.home.data

import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.home.domain.AccountDeletionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpAccountDeletionRepositoryTest {
    @Test fun clearsCurrentLocalDataOnlyAfterConfirmedCloudDeletionAndThenClearsOwnedSession() = runTest {
        val events = mutableListOf<String>(); val runtime = SessionRuntime().apply { update("token", "account") }
        val repository = HttpAccountDeletionRepository(
            transport = { request -> events += "server"; assertEquals("DELETE", request.method); assertEquals("Bearer token", request.headers["Authorization"]); HttpResponse(200, "{\"status\":\"DELETED\"}") },
            runtime = runtime,
            localData = { accountId -> events += "local:$accountId"; true },
            deletedSession = { events += "session" },
            codec = wire,
            journal = InMemoryDeletionJournal(),
            access = { true },
        )
        assertEquals(AccountDeletionResult.Deleted, repository.delete("delete-1"))
        assertEquals(listOf("server", "local:account", "session"), events)
    }
    @Test fun localOrServerFailureRemainsRetryableWithoutClearingSession() = runTest {
        val runtime = SessionRuntime().apply { update("token", "account") }; var cleared = 0
        val localFailure = HttpAccountDeletionRepository({ HttpResponse(200, "{\"status\":\"DELETED\"}") }, runtime, { false }, { cleared++ }, wire, InMemoryDeletionJournal(), { true })
        assertEquals(AccountDeletionResult.Failed, localFailure.delete("delete-1"))
        val serverFailure = HttpAccountDeletionRepository({ HttpResponse(503, "") }, runtime, { true }, { cleared++ }, wire, InMemoryDeletionJournal(), { true })
        assertEquals(AccountDeletionResult.Failed, serverFailure.delete("delete-1")); assertEquals(0, cleared)
    }
    private val wire = object : AccountDeletionWireCodec {
        override fun command(commandId: String) = "{\"commandId\":\"$commandId\"}"
        override fun confirmsDeletion(responseBody: String) = responseBody == "{\"status\":\"DELETED\"}"
    }
}
