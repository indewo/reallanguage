package com.reallanguage.app.core.session

import com.reallanguage.app.core.network.HttpRequest
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SessionTransportDeadlineTest {
    @Test fun continuousResponseBytesCannotKeepAnOrdinaryRequestAlivePastItsExistingTotalBudget() = runBlocking {
        val budget = SessionNetworkTimeouts.CONNECT_MILLISECONDS.toLong() + SessionNetworkTimeouts.READ_MILLISECONDS
        ServerSocket(0).use { server ->
            val accepted = AtomicReference<Socket?>()
            val stopWriting = CountDownLatch(1)
            val clientDisconnected = CompletableDeferred<Boolean>()
            val worker = thread(isDaemon = true, name = "isolated-session-deadline") {
                try {
                    server.accept().use { socket ->
                        accepted.set(socket)
                        socket.soTimeout = (budget + 5_000).toInt()
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { /* Local request has no body. */ }
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: 1024\r\nConnection: close\r\n\r\n ".toByteArray())
                        output.flush()
                        val writer = thread(isDaemon = true, name = "isolated-session-response-drip") writerTask@ {
                            try {
                                repeat(200) {
                                    if (stopWriting.await(100, TimeUnit.MILLISECONDS)) return@writerTask
                                    output.write(' '.code)
                                    output.flush()
                                }
                            } catch (_: IOException) {
                                // The client must close this response on its deadline.
                            }
                        }
                        try {
                            clientDisconnected.complete(reader.read() == -1)
                        } finally {
                            stopWriting.countDown()
                            writer.join(1_000)
                        }
                    }
                } catch (failure: Exception) {
                    clientDisconnected.completeExceptionally(failure)
                }
            }
            val started = System.nanoTime()
            try {
                try {
                    withTimeout(budget + 2_000) {
                        UrlConnectionSessionTransport("http://127.0.0.1:${server.localPort}", true)
                            .execute(HttpRequest("GET", "/api/v1/health", ""))
                    }
                    fail("An incomplete continuously streaming response must not return a successful response")
                } catch (_: SocketTimeoutException) {
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    assertTrue("Do not silently lower the existing connection plus read budget", elapsed >= budget - 500)
                    assertTrue("The request must finish near its complete-request deadline", elapsed < budget + 1_500)
                }
                withTimeout(1_000) {
                    assertTrue("The deadline must close the actual client socket", clientDisconnected.await())
                }
            } finally {
                stopWriting.countDown()
                accepted.get()?.close()
                server.close()
                worker.join(2_000)
            }
        }
    }
}
