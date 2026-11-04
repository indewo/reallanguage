package com.reallanguage.app.core.session

import com.reallanguage.app.core.network.HttpRequest
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AiRequestTimeoutTest {
    @Test fun aiResponseAfterEightSecondsIsNotDiscardedAsConnectionFailure() = runBlocking {
        ServerSocket(0).use { server ->
            val worker = thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { /* consume HTTP headers */ }
                    Thread.sleep(8_500)
                    val body = "{}"
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n$body".toByteArray())
                }
            }
            try {
                val transport = UrlConnectionSessionTransport("http://127.0.0.1:${server.localPort}", true)
                val response = transport.execute(HttpRequest("POST", "/api/v1/content-formation/learning-targets", ""))
                assertEquals(200, response.statusCode)
            } finally { worker.join(12_000) }
        }
    }
}
