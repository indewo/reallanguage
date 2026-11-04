package com.reallanguage.app.core.network

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.BuildConfig
import com.reallanguage.app.core.session.UrlConnectionSessionTransport
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in unauthenticated health check; no user data, account creation or AI request. */
class PublishedApiHealthTest {
    @Test fun installedBuildConnectsToItsPublishedBackend() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyPublishedApi") == "true")
        val transport = UrlConnectionSessionTransport(BuildConfig.API_BASE_URL, true)
        val response = transport.execute(HttpRequest("GET", "/api/v1/health", ""))
        assertEquals(200, response.statusCode)
        assertEquals("ok", JSONObject(response.body).getString("status"))
        assertEquals("api", JSONObject(response.body).getString("service"))
    }
}
