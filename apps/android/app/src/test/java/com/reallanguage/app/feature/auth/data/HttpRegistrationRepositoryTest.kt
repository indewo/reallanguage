package com.reallanguage.app.feature.auth.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.feature.auth.domain.PasswordRejectionReason
import com.reallanguage.app.feature.auth.domain.RegistrationCommand
import com.reallanguage.app.feature.auth.domain.RegistrationFailureReason
import com.reallanguage.app.feature.auth.domain.RegistrationStartResult
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpRegistrationRepositoryTest {
    @Test fun postsTheConfirmedRegistrationContractAndMapsVerification() = runTest {
        val transport = FakeTransport(HttpResponse(200, "verification"))
        val repository = HttpRegistrationRepository(transport, FakeCodec())

        assertEquals(
            RegistrationStartResult.EmailVerificationRequired("registration-1"),
            repository.startRegistration(command),
        )
        assertEquals("/api/v1/auth/registrations", transport.request?.path)
        assertEquals("POST", transport.request?.method)
    }

    @Test fun mapsAgeAndPasswordPolicyOutcomesWithoutInventingAccountState() = runTest {
        listOf(
            "age" to RegistrationStartResult.AgeIneligible,
            "blocked" to RegistrationStartResult.PasswordRejected(PasswordRejectionReason.BLOCKED),
        ).forEach { (body, expected) ->
            val repository = HttpRegistrationRepository(
                FakeTransport(HttpResponse(200, body)),
                FakeCodec(),
            )
            assertEquals(expected, repository.startRegistration(command))
        }
    }

    @Test fun separatesNetworkFailureFromServerFailure() = runTest {
        val offline = HttpRegistrationRepository(
            HttpTransport { throw IOException("offline") },
            FakeCodec(),
        )
        assertEquals(
            RegistrationStartResult.Failed(RegistrationFailureReason.NETWORK, retryable = true),
            offline.startRegistration(command),
        )
        val server = HttpRegistrationRepository(
            FakeTransport(HttpResponse(503, "unavailable")),
            FakeCodec(),
        )
        assertEquals(
            RegistrationStartResult.Failed(RegistrationFailureReason.SERVICE, retryable = true),
            server.startRegistration(command),
        )
    }

    private val command = RegistrationCommand(
        email = "learner@example.com",
        password = "not-a-real-password",
        birthYearMonth = "2000-01",
        acceptedTerms = true,
        acceptedPrivacyPolicy = true,
    )
}

private class FakeTransport(private val response: HttpResponse) : HttpTransport {
    var request: HttpRequest? = null
    override suspend fun execute(request: HttpRequest): HttpResponse {
        this.request = request
        return response
    }
}

private class FakeCodec : RegistrationWireCodec {
    override fun request(command: RegistrationCommand) = "request"
    override fun result(body: String): RegistrationStartResult = when (body) {
        "verification" -> RegistrationStartResult.EmailVerificationRequired("registration-1")
        "age" -> RegistrationStartResult.AgeIneligible
        else -> RegistrationStartResult.PasswordRejected(PasswordRejectionReason.BLOCKED)
    }
}
