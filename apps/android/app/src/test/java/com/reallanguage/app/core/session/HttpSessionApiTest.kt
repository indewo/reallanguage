package com.reallanguage.app.core.session

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HttpSessionApiTest {
    @Test fun loginCancellationRemainsCancellationInsteadOfServiceFailure() = runTest {
        val cancellation = CancellationException("isolated login cancelled")
        val api = HttpSessionApi(HttpTransport { throw cancellation }, FakeWireCodec())

        try {
            api.login("isolated@example.invalid", "isolated-test-password", "isolated-device")
            fail("A cancelled login must not become a service failure")
        } catch (actual: CancellationException) {
            assertTrue("Preserve the original cancellation", actual === cancellation)
        }
    }

    @Test fun refreshCancellationRemainsCancellationInsteadOfConnectionFailure() = runTest {
        val cancellation = CancellationException("isolated refresh cancelled")
        val api = HttpSessionApi(HttpTransport { throw cancellation }, FakeWireCodec())

        try {
            api.refresh("isolated-test-refresh", "isolated-device")
            fail("A cancelled refresh must not become a connection failure")
        } catch (actual: CancellationException) {
            assertTrue("Preserve the original cancellation", actual === cancellation)
        }
    }

    @Test fun revocationCancellationRemainsCancellationInsteadOfRejectedLogout() = runTest {
        val cancellation = CancellationException("isolated revoke cancelled")
        val api = HttpSessionApi(HttpTransport { throw cancellation }, FakeWireCodec())

        try {
            api.revoke("isolated-test-refresh")
            fail("A cancelled revocation must not become a rejected logout")
        } catch (actual: CancellationException) {
            assertTrue("Preserve the original cancellation", actual === cancellation)
        }
    }

    @Test fun serverFailureIsNotOfflineAndDoesNotAutomaticallyRetry() = runTest {
        val transport = FakeHttpTransport(HttpResponse(503, "isolated unavailable"))

        assertEquals(
            SessionApiResult.Failed(SessionFailure.SERVICE),
            HttpSessionApi(transport, FakeWireCodec()).refresh("isolated-test-refresh", "isolated-device"),
        )
        assertEquals("No implicit refresh loop or retry", 1, transport.requests.size)
    }

    @Test fun loginAndRefreshUseVersionedEndpointsAndDecodeSuccessfulSessions() = runTest {
        val transport = FakeHttpTransport(HttpResponse(200, "authenticated"))
        val api = HttpSessionApi(transport, FakeWireCodec())

        assertTrue(api.login("learner@example.com", "password", "device") is SessionApiResult.Authenticated)
        assertEquals("/api/v1/auth/sessions", transport.requests[0].path)
        assertTrue(api.refresh("refresh", "device") is SessionApiResult.Authenticated)
        assertEquals("/api/v1/auth/sessions/refresh", transport.requests[1].path)
    }

    @Test fun invalidSessionResponseAndTransportFailureRemainDistinct() = runTest {
        val invalid = HttpSessionApi(
            FakeHttpTransport(HttpResponse(200, "invalid")),
            FakeWireCodec(),
        )
        assertEquals(SessionApiResult.InvalidOrExpired, invalid.refresh("refresh", "device"))

        val unavailable = HttpSessionApi(
            HttpTransport { throw IOException("offline") },
            FakeWireCodec(),
        )
        assertEquals(
            SessionApiResult.Failed(SessionFailure.NETWORK),
            unavailable.login("learner@example.com", "password", "device"),
        )
    }

    @Test fun expiredRefreshHttpStatusReturnsSignedOutInsteadOfConnectionFailure() = runTest {
        val expired = HttpSessionApi(
            FakeHttpTransport(HttpResponse(401, "expired")),
            FakeWireCodec(),
        )

        assertEquals(SessionApiResult.InvalidOrExpired, expired.refresh("refresh", "device"))

        val malformedStoredCredential = HttpSessionApi(
            FakeHttpTransport(HttpResponse(400, "invalid stored credential")),
            FakeWireCodec(),
        )
        assertEquals(
            SessionApiResult.InvalidOrExpired,
            malformedStoredCredential.refresh("refresh", "device"),
        )
    }

    @Test fun revokeSucceedsOnlyForConfirmedServerResponse() = runTest {
        val success = HttpSessionApi(
            FakeHttpTransport(HttpResponse(200, "revoked")),
            FakeWireCodec(),
        )
        assertTrue(success.revoke("refresh"))
        val failed = HttpSessionApi(
            FakeHttpTransport(HttpResponse(503, "unavailable")),
            FakeWireCodec(),
        )
        assertFalse(failed.revoke("refresh"))
    }
}

private class FakeHttpTransport(private val response: HttpResponse) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return response
    }
}

private class FakeWireCodec : SessionWireCodec {
    override fun login(email: String, password: String, deviceId: String) = "login"
    override fun refresh(refreshToken: String, deviceId: String) = "refresh"
    override fun revoke(refreshToken: String) = "revoke"
    override fun session(body: String): SessionApiResult = when (body) {
        "authenticated" -> SessionApiResult.Authenticated("account-1", "access", "refresh", true)
        else -> SessionApiResult.InvalidOrExpired
    }
    override fun revoked(body: String): Boolean = body == "revoked"
}
