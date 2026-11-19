package com.reallanguage.app.core.session

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SessionPreflightHttpTransportTest {
    @Test fun `concurrent expired requests share one refresh and each operation is sent once`() = runTest {
        var now = 0L
        val runtime = SessionRuntime { now }.apply { update("old", "owner", expiresInSeconds = 900) }
        now = 900_000
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var refreshes = 0
        val sent = mutableListOf<HttpRequest>()
        val transport = SessionPreflightHttpTransport(HttpTransport { sent += it; HttpResponse(200, "") }, runtime) { owner, _ ->
            refreshes++; started.complete(Unit); finish.await()
            runtime.update("new", owner, expiresInSeconds = 900); true
        }
        val first = async { transport.execute(request()) }
        started.await()
        val secondStarted = CompletableDeferred<Unit>()
        val second = async { secondStarted.complete(Unit); transport.execute(request().copy(body = "second-command")) }
        secondStarted.await()
        finish.complete(Unit)
        assertEquals(200, first.await().statusCode)
        assertEquals(200, second.await().statusCode)
        assertEquals(1, refreshes)
        assertEquals(listOf("unchanged-command", "second-command"), sent.map { it.body })
        assertTrue(sent.all { it.headers["Authorization"] == "Bearer new" })
    }

    @Test fun `expired access refreshes before sending and never replays a rejected operation`() = runTest {
        var now = 0L
        val runtime = SessionRuntime { now }.apply { update("old", "owner", expiresInSeconds = 900) }
        now = 900_000
        val sent = mutableListOf<HttpRequest>()
        var refreshes = 0
        val transport = SessionPreflightHttpTransport(HttpTransport { sent += it; HttpResponse(401, "rejected") }, runtime) { owner, epoch ->
            assertEquals("owner", owner)
            assertEquals(runtime.accountEpoch, epoch)
            assertTrue(sent.isEmpty())
            refreshes++
            runtime.update("new", owner, expiresInSeconds = 900)
            true
        }
        val response = transport.execute(request())
        assertEquals(401, response.statusCode)
        assertEquals(1, refreshes)
        assertEquals(listOf(request().copy(headers = mapOf("Authorization" to "Bearer new"))), sent)
    }

    @Test fun `account replacement during refresh cannot send the old operation as the new account`() = runTest {
        var now = 0L
        val runtime = SessionRuntime { now }.apply { update("old", "owner", expiresInSeconds = 900) }
        now = 900_000
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var sends = 0
        val transport = SessionPreflightHttpTransport(HttpTransport { sends++; HttpResponse(200, "") }, runtime) { _, _ ->
            started.complete(Unit); finish.await(); true
        }
        val pending = async { transport.execute(request()) }
        started.await()
        runtime.update("other", "other-owner", newSession = true, expiresInSeconds = 900)
        finish.complete(Unit)
        assertEquals(401, pending.await().statusCode)
        assertEquals(0, sends)
    }

    @Test fun `refresh rejection does not send the operation or clear existing credentials`() = runTest {
        var now = 0L
        val runtime = SessionRuntime { now }.apply { update("old", "owner", expiresInSeconds = 900) }
        now = 900_000
        var sends = 0
        val transport = SessionPreflightHttpTransport(HttpTransport { sends++; HttpResponse(200, "") }, runtime) { _, _ -> false }
        assertEquals(401, transport.execute(request()).statusCode)
        assertEquals(0, sends)
        assertEquals("owner", runtime.accountId)
        assertEquals("old", runtime.accessToken)
    }

    @Test fun `fresh token uses the original operation without refreshing`() = runTest {
        val runtime = SessionRuntime { 0 }.apply { update("old", "owner", expiresInSeconds = 900) }
        var refreshes = 0
        var sends = 0
        val transport = SessionPreflightHttpTransport(HttpTransport { sends++; assertEquals(request(), it); HttpResponse(200, "") }, runtime) { _, _ -> refreshes++; true }
        assertEquals(200, transport.execute(request()).statusCode)
        assertEquals(0, refreshes)
        assertEquals(1, sends)
    }

    private fun request() = HttpRequest("POST", "/api/v1/learning/confirm", "unchanged-command", mapOf("Authorization" to "Bearer old"))
}
