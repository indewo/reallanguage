package com.reallanguage.app.core.network

import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AddressFailoverHttpTransportTest {
    private val request = HttpRequest("GET", "/api/v1/account/current", "")
    private val health = HttpResponse(200, "{\"status\":\"ok\",\"service\":\"api\"}")

    @Test fun `unknown hostname falls back and remembers the working LAN address`() = runTest {
        var primaryCalls = 0
        var fallbackCalls = 0
        val transport = AddressFailoverHttpTransport(listOf(
            HttpTransport { primaryCalls++; throw UnknownHostException("mdns unavailable") },
            HttpTransport { request -> fallbackCalls++; if (request.path == "/api/v1/health") health else HttpResponse(200, "ok") },
        ))

        assertEquals(200, transport.execute(request).statusCode)
        assertEquals(200, transport.execute(request).statusCode)
        assertEquals(1, primaryCalls)
        assertEquals(3, fallbackCalls)
    }

    @Test fun `failed remembered address can recover back to hostname`() = runTest {
        var hostnameWorks = false
        var fallbackWorks = true
        val hostname = HttpTransport { request ->
            if (!hostnameWorks) throw UnknownHostException("not yet")
            if (request.path == "/api/v1/health") health else HttpResponse(200, "hostname")
        }
        val fallback = HttpTransport { request ->
            if (!fallbackWorks) throw ConnectException("address changed")
            if (request.path == "/api/v1/health") health else HttpResponse(200, "fallback")
        }
        val transport = AddressFailoverHttpTransport(listOf(hostname, fallback))

        assertEquals("fallback", transport.execute(request).body)
        hostnameWorks = true
        fallbackWorks = false
        assertThrows(ConnectException::class.java) { kotlinx.coroutines.runBlocking { transport.execute(request) } }
        assertEquals("hostname", transport.execute(request).body)
    }

    @Test fun `business errors timeouts and cancellation are never replayed`() = runTest {
        listOf<Exception>(IOException("read failed"), CancellationException("left page")).forEach { failure ->
            var fallbackCalls = 0
            val transport = AddressFailoverHttpTransport(listOf(
                HttpTransport { request -> if (request.path == "/api/v1/health") health else throw failure },
                HttpTransport { fallbackCalls++; health },
            ))
            assertThrows(failure::class.java) { kotlinx.coroutines.runBlocking { transport.execute(request) } }
            assertEquals(0, fallbackCalls)
        }
    }

    @Test fun `wrong service on the same port is rejected before credentials are sent`() = runTest {
        var wrongBusinessCalls = 0
        var correctBusinessCalls = 0
        val transport = AddressFailoverHttpTransport(listOf(
            HttpTransport { request ->
                if (request.path == "/api/v1/health") HttpResponse(200, "other service")
                else { wrongBusinessCalls++; HttpResponse(200, "wrong") }
            },
            HttpTransport { request ->
                if (request.path == "/api/v1/health") health
                else { correctBusinessCalls++; HttpResponse(200, "correct") }
            },
        ))

        assertEquals("correct", transport.execute(request).body)
        assertEquals(0, wrongBusinessCalls)
        assertEquals(1, correctBusinessCalls)
    }

    @Test fun `runtime discovery replaces an obsolete embedded WiFi address`() = runTest {
        var discoveryCalls = 0
        val stale = HttpTransport { throw ConnectException("old DHCP address") }
        val discovered = HttpTransport { request ->
            if (request.path == "/api/v1/health") health else HttpResponse(200, "discovered")
        }
        val transport = AddressFailoverHttpTransport(
            transports = listOf(stale),
            discoverTransports = {
                discoveryCalls++
                listOf(discovered)
            },
        )

        assertEquals("discovered", transport.execute(request).body)
        assertEquals("discovered", transport.execute(request).body)
        assertEquals(1, discoveryCalls)
    }
}
