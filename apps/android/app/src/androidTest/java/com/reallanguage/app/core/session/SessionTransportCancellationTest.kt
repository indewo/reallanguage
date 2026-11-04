package com.reallanguage.app.core.session

import com.reallanguage.app.core.network.HttpRequest
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTransportCancellationTest {
    @Test fun leavingRequestCancelsBlockedAiReadWithoutWaitingForItsLongReadTimeout() = runBlocking {
        ServerSocket(0).use { server ->
            val accepted = AtomicReference<Socket?>()
            val requestReceived = CompletableDeferred<Unit>()
            val clientDisconnected = CompletableDeferred<Unit>()
            val worker = thread(isDaemon = true, name = "isolated-session-cancellation") {
                try {
                    server.accept().use { socket ->
                        accepted.set(socket)
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { /* Consume only the local test request headers. */ }
                        requestReceived.complete(Unit)
                        check(reader.read() == -1) { "The empty local request must close without additional request bytes" }
                        clientDisconnected.complete(Unit)
                    }
                } catch (failure: Exception) {
                    requestReceived.completeExceptionally(failure)
                    clientDisconnected.completeExceptionally(failure)
                }
            }
            val request = async {
                UrlConnectionSessionTransport("http://127.0.0.1:${server.localPort}", true)
                    .execute(HttpRequest("POST", "/api/v1/content-formation/learning-targets", ""))
            }
            try {
                withTimeout(2_000) { requestReceived.await() }
                request.cancel(CancellationException("User left the isolated request"))

                withTimeout(1_000) {
                    request.join()
                    clientDisconnected.await()
                }

                assertTrue(request.isCancelled)
            } finally {
                accepted.get()?.close()
                server.close()
                worker.join(2_000)
                request.cancelAndJoin()
            }
        }
    }
}
