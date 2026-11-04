package com.reallanguage.app.core.session

import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertEquals
import com.reallanguage.app.core.network.HttpRequest
import java.net.URL

class ApiBaseUrlPolicyTest {
    @Test fun `local HTTP API bypasses a stale VPN or Wi-Fi proxy`() {
        listOf(
            "http://127.0.0.1:3000",
            "http://EricdeMacBook-Pro.local:3000",
            "http://10.34.113.36:3000",
            "http://172.16.0.2:3000",
            "http://172.31.255.254:3000",
            "http://192.168.1.5:3000",
        ).forEach { url -> assertTrue(url, shouldBypassSystemProxyForLocalApi(URL(url))) }
    }

    @Test fun `public or encrypted API keeps platform routing`() {
        listOf(
            "https://192.168.1.5:3000",
            "https://api.reallanguage.me",
            "http://172.32.0.2:3000",
            "http://example.com:3000",
        ).forEach { url -> assertFalse(url, shouldBypassSystemProxyForLocalApi(URL(url))) }
    }

    @Test fun `local API requests nearby devices permission on supported Android versions`() {
        assertFalse(requiresNearbyDevicesPermissionForLocalApi("http://192.168.1.5:3000", 32))
        assertTrue(requiresNearbyDevicesPermissionForLocalApi("http://192.168.1.5:3000", 33))
        assertTrue(requiresNearbyDevicesPermissionForLocalApi("http://10.0.2.2:3000", 36))
        assertTrue(requiresNearbyDevicesPermissionForLocalApi("http://EricdeMacBook-Pro.local:3000", 36))
        assertFalse(requiresNearbyDevicesPermissionForLocalApi("https://api.reallanguage.me", 36))
        assertFalse(requiresNearbyDevicesPermissionForLocalApi("not a url", 36))
    }

    @Test fun `total request deadlines preserve the existing connect and read budgets`() {
        assertEquals(11_000L, SessionNetworkTimeouts.totalMilliseconds(HttpRequest("POST", "/api/v1/auth/sessions/refresh", "{}")))
        assertEquals(183_000L, SessionNetworkTimeouts.totalMilliseconds(HttpRequest("POST", "/api/v1/content-formation/learning-targets", "{}")))
        assertEquals(183_000L, SessionNetworkTimeouts.totalMilliseconds(HttpRequest("POST", "/api/v1/safety/cases/isolated-case/handling", "{}")))
        assertEquals(11_000L, SessionNetworkTimeouts.totalMilliseconds(HttpRequest("POST", "/api/v1/safety/cases/isolated-case/manual-reviews", "{}")))
    }

    @Test fun `safety handling preparation keeps the same AI wait budget as generation`() {
        listOf(
            "/api/v1/safety/cases/isolated-case/handling",
            "/api/v1/safety/cases/isolated-case/handling?request=isolated",
        ).forEach { path ->
            assertEquals(path, 180_000, SessionNetworkTimeouts.readMilliseconds(HttpRequest("POST", path, "{}")))
        }
    }

    @Test fun `safety handling AI budget does not extend adjacent non AI commands or invalid paths`() {
        listOf(
            HttpRequest("GET", "/api/v1/safety/cases/isolated-case/handling", ""),
            HttpRequest("POST", "/api/v1/safety/cases/isolated-case/manual-reviews", "{}"),
            HttpRequest("POST", "/api/v1/safety/cases/isolated-case/accept-deidentified", "{}"),
            HttpRequest("POST", "/api/v1/safety/cases/isolated-case/return-to-edit", "{}"),
            HttpRequest("POST", "/api/v1/safety/cases/isolated-case/handling/extra", "{}"),
            HttpRequest("POST", "/api/v1/safety/cases//handling", "{}"),
        ).forEach { request ->
            assertEquals(request.toString(), 8_000, SessionNetworkTimeouts.readMilliseconds(request))
        }
    }

    @Test fun `AI wait budget does not slow authentication or unknown endpoints`() {
        assertEquals(180_000, SessionNetworkTimeouts.readMilliseconds(HttpRequest("POST", "/api/v1/content-formation/learning-targets", "")))
        assertEquals(8_000, SessionNetworkTimeouts.readMilliseconds(HttpRequest("POST", "/api/v1/auth/sessions", "")))
        assertEquals(8_000, SessionNetworkTimeouts.readMilliseconds(HttpRequest("GET", "/api/v1/content-formation/learning-targets", "")))
        assertEquals(8_000, SessionNetworkTimeouts.readMilliseconds(HttpRequest("POST", "/api/v1/unknown", "")))
    }
    @Test
    fun `session network timeouts fail fast enough for an interactive retry`() {
        assertTrue(SessionNetworkTimeouts.CONNECT_MILLISECONDS <= 3_000)
        assertTrue(SessionNetworkTimeouts.READ_MILLISECONDS <= 8_000)
    }

    @Test
    fun `https is accepted outside local development`() {
        requireValidApiBaseUrl("https://api.reallanguage.me", false)
    }

    @Test
    fun `http is accepted only for a local development build`() {
        requireValidApiBaseUrl("http://192.168.1.10:3000", true)
    }

    @Test
    fun `http is rejected outside local development`() {
        assertThrows(IllegalArgumentException::class.java) {
            requireValidApiBaseUrl("http://192.168.1.10:3000", false)
        }
    }

    @Test
    fun `unsupported URL is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            requireValidApiBaseUrl("api.reallanguage.me", true)
        }
    }
}
