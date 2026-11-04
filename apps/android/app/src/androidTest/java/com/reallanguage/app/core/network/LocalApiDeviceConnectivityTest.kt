package com.reallanguage.app.core.network

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.BuildConfig
import com.reallanguage.app.core.session.UrlConnectionSessionTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

class LocalApiDeviceConnectivityTest {
    @Test fun configuredLanRoutesReachTheRealApiThroughAndroidNetworking() = runBlocking {
        assumeTrue(BuildConfig.DEBUG && !BuildConfig.API_BASE_URL.contains("api.invalid"))
        // The Android Emulator NAT does not bridge the Mac's mDNS multicast into
        // the guest. Keep this as a physical-device acceptance test instead of
        // pretending the emulator can validate DNS-SD behavior.
        assumeFalse(isAndroidEmulator())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val routes = buildList {
            add(BuildConfig.API_BASE_URL)
            BuildConfig.API_FALLBACK_BASE_URL.takeIf(String::isNotBlank)?.let(::add)
        }
        val transport = AddressFailoverHttpTransport(
            transports = routes.map { route ->
                UrlConnectionSessionTransport(route, allowCleartextForLocalDevelopment = true)
            },
            discoverTransports = {
                AndroidNsdLocalApiDiscovery(
                    context = context,
                    serviceType = BuildConfig.API_NSD_SERVICE_TYPE,
                    timeoutMilliseconds = 8_000,
                ).discoverBaseUrls().map { route ->
                    UrlConnectionSessionTransport(route, allowCleartextForLocalDevelopment = true)
                }
            },
        )

        val response = transport.execute(HttpRequest("GET", "/api/v1/health", ""))

        assertEquals(200, response.statusCode)
        assertTrue(response.body.contains("\"status\":\"ok\""))
        assertTrue(response.body.contains("\"service\":\"api\""))
    }

    @Test fun emulatorHostBridgeReachesTheRealApiThroughAndroidNetworking() = runBlocking {
        assumeTrue(isAndroidEmulator())
        val liveBaseUrl = InstrumentationRegistry.getArguments()
            .getString("localApiBaseUrl")
            .orEmpty()
        assumeTrue("Pass localApiBaseUrl from the live Mac network", liveBaseUrl.isNotBlank())
        val transport = UrlConnectionSessionTransport(
            baseUrl = liveBaseUrl,
            allowCleartextForLocalDevelopment = true,
        )

        val response = transport.execute(HttpRequest("GET", "/api/v1/health", ""))

        assertEquals(200, response.statusCode)
        assertTrue(response.body.contains("\"status\":\"ok\""))
        assertTrue(response.body.contains("\"service\":\"api\""))
    }
}

private fun isAndroidEmulator(): Boolean =
    Build.FINGERPRINT.startsWith("generic") ||
        Build.FINGERPRINT.startsWith("unknown") ||
        Build.MODEL.contains("Emulator") ||
        Build.MODEL.contains("Android SDK built for") ||
        Build.PRODUCT.contains("sdk_gphone") ||
        Build.HARDWARE.contains("goldfish") ||
        Build.HARDWARE.contains("ranchu") ||
        Build.MANUFACTURER.contains("Genymotion") ||
        Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")
