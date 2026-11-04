package com.reallanguage.app.feature.auth.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.feature.auth.domain.RegistrationFailureReason
import com.reallanguage.app.feature.auth.domain.RegistrationVerificationStatusResult
import com.reallanguage.app.feature.auth.domain.VerificationEmailResendResult
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpVerificationEmailRepositoryTest {
    @Test fun aServerStatusFailureIsNotMisclassifiedAsAConnectionFailure() = runTest {
        val repository = HttpVerificationEmailRepository(
            HttpTransport { HttpResponse(503, "isolated unavailable response") }, FakeVerificationCodec(),
        )
        assertEquals(RegistrationVerificationStatusResult.Failed(RegistrationFailureReason.SERVICE),
            repository.status("isolated-registration"))
    }

    @Test fun readsRegistrationVerificationStatus() = runTest {
        val transport = RecordingVerificationTransport(HttpResponse(200, "verified"))
        val repository = HttpVerificationEmailRepository(transport, FakeVerificationCodec())

        assertEquals(RegistrationVerificationStatusResult.Verified, repository.status("registration-1"))
        assertEquals("GET", transport.request?.method)
        assertEquals(
            "/api/v1/auth/registrations/registration-1/verification-status",
            transport.request?.path,
        )
    }

    @Test fun postsThePendingRegistrationAndMapsServerCooldowns() = runTest {
        val transport = RecordingVerificationTransport(HttpResponse(200, "resent"))
        val repository = HttpVerificationEmailRepository(transport, FakeVerificationCodec())

        assertEquals(VerificationEmailResendResult.Resent(60), repository.resend("registration-1"))
        assertEquals("POST", transport.request?.method)
        assertEquals(
            "/api/v1/auth/registrations/registration-1/verification-email",
            transport.request?.path,
        )

        val cooldown = HttpVerificationEmailRepository(
            RecordingVerificationTransport(HttpResponse(200, "cooldown")),
            FakeVerificationCodec(),
        )
        assertEquals(VerificationEmailResendResult.Cooldown(12), cooldown.resend("registration-1"))
    }

    @Test fun separatesUnavailableNetworkAndRetryableServerFailure() = runTest {
        val unavailable = HttpVerificationEmailRepository(
            RecordingVerificationTransport(HttpResponse(200, "unavailable")),
            FakeVerificationCodec(),
        )
        assertEquals(VerificationEmailResendResult.NotAvailable, unavailable.resend("registration-1"))

        val offline = HttpVerificationEmailRepository(
            HttpTransport { throw IOException("offline") },
            FakeVerificationCodec(),
        )
        assertEquals(
            VerificationEmailResendResult.Failed(RegistrationFailureReason.NETWORK, true),
            offline.resend("registration-1"),
        )
        val server = HttpVerificationEmailRepository(
            RecordingVerificationTransport(HttpResponse(503, "failed")),
            FakeVerificationCodec(),
        )
        assertEquals(
            VerificationEmailResendResult.Failed(RegistrationFailureReason.SERVICE, true),
            server.resend("registration-1"),
        )
    }
}

private class RecordingVerificationTransport(private val response: HttpResponse) : HttpTransport {
    var request: HttpRequest? = null
    override suspend fun execute(request: HttpRequest): HttpResponse {
        this.request = request
        return response
    }
}

private class FakeVerificationCodec : VerificationEmailWireCodec {
    override fun result(body: String): VerificationEmailResendResult = when (body) {
        "resent" -> VerificationEmailResendResult.Resent(60)
        "cooldown" -> VerificationEmailResendResult.Cooldown(12)
        else -> VerificationEmailResendResult.NotAvailable
    }

    override fun status(body: String): RegistrationVerificationStatusResult = when (body) {
        "verified" -> RegistrationVerificationStatusResult.Verified
        "waiting" -> RegistrationVerificationStatusResult.Waiting
        else -> RegistrationVerificationStatusResult.NotAvailable
    }
}
