package com.reallanguage.app.feature.auth.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.feature.auth.domain.PasswordResetFailureReason
import com.reallanguage.app.feature.auth.domain.PasswordResetRequestResult
import com.reallanguage.app.feature.auth.domain.PasswordResetResult
import com.reallanguage.app.feature.auth.domain.PasswordResetVerificationResult
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpPasswordResetRepositoryTest {
    private val codec = RecordingPasswordResetCodec()

    @Test fun usesVersionedEndpointsAndStableCommandBodies() = runTest {
        val transport = QueueTransport(
            ArrayDeque(
                listOf(
                    HttpResponse(200, "request-result"),
                    HttpResponse(200, "verification-result"),
                    HttpResponse(200, "completion-result"),
                ),
            ),
        )
        val repository = HttpPasswordResetRepository(transport, codec)

        assertEquals(
            PasswordResetRequestResult.Sent("challenge-1"),
            repository.request("learner@example.com", "request-command"),
        )
        assertEquals(
            PasswordResetVerificationResult.Verified("reset-token"),
            repository.verify("challenge-1", "verification-token"),
        )
        assertEquals(
            PasswordResetResult.Completed(false, true),
            repository.reset("reset-token", "a sufficiently long password", "reset-command"),
        )

        assertEquals("/api/v1/auth/password-resets", transport.requests[0].path)
        assertEquals("learner@example.com|request-command", transport.requests[0].body)
        assertEquals(
            "/api/v1/auth/password-resets/challenge-1/verification",
            transport.requests[1].path,
        )
        assertEquals(
            "/api/v1/auth/password-resets/completion",
            transport.requests[2].path,
        )
        assertEquals(
            "reset-token|a sufficiently long password|reset-command",
            transport.requests[2].body,
        )
    }

    @Test fun mapsLinkExpiryAndTransportFailuresWithoutAdvancingTheFlow() = runTest {
        val invalid = HttpPasswordResetRepository(
            QueueTransport(
                ArrayDeque(listOf(HttpResponse(200, "invalid"))),
            ),
            codec,
        )
        assertEquals(
            PasswordResetVerificationResult.InvalidLink,
            invalid.verify("challenge-1", "expired-token"),
        )

        val offline = HttpPasswordResetRepository(HttpTransport { throw IOException("offline") }, codec)
        val result = offline.request("learner@example.com", "same-command")
        assertTrue(result is PasswordResetRequestResult.Failed)
        result as PasswordResetRequestResult.Failed
        assertEquals(PasswordResetFailureReason.NETWORK, result.reason)
        assertTrue(result.retryable)
    }
}

private class RecordingPasswordResetCodec : PasswordResetWireCodec {
    override fun request(email: String, commandId: String) = "$email|$commandId"

    override fun requestResult(body: String): PasswordResetRequestResult {
        check(body == "request-result")
        return PasswordResetRequestResult.Sent("challenge-1")
    }

    override fun verification(token: String) = token

    override fun verificationResult(body: String): PasswordResetVerificationResult = when (body) {
        "verification-result" -> PasswordResetVerificationResult.Verified("reset-token")
        "invalid" -> PasswordResetVerificationResult.InvalidLink
        else -> error("malformed")
    }

    override fun completion(resetToken: String, newPassword: String, commandId: String) =
        "$resetToken|$newPassword|$commandId"

    override fun completionResult(body: String): PasswordResetResult {
        check(body == "completion-result")
        return PasswordResetResult.Completed(false, true)
    }
}

private class QueueTransport(
    private val responses: ArrayDeque<HttpResponse>,
) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return responses.removeFirst()
    }
}
