package com.reallanguage.app.feature.safety.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.SafetyCheckResult
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpSafetyCheckRepositoryTest {
    @Test fun submitsTheConfirmedSourceWithAuthenticationAndStableIdentity() = runTest {
        val runtime = SessionRuntime().apply { update("access-token") }
        val transport = RecordingSafetyTransport(HttpResponse(200, "pass"))
        val repository = HttpSafetyCheckRepository(
            transport,
            FakeSafetyCodec,
            runtime,
            "safety-command",
        )

        assertEquals(SafetyCheckResult.Passed, repository.check(source))
        assertEquals("/api/v1/safety/checks", transport.request?.path)
        assertEquals("Bearer access-token", transport.request?.headers?.get("Authorization"))
        assertEquals("日记正文|DIARY|ARTICLE|safety-command", transport.request?.body)
    }

    @Test fun refusesToSendSensitiveContentWithoutAnAuthenticatedSession() = runTest {
        val transport = RecordingSafetyTransport(HttpResponse(200, "pass"))
        val repository = HttpSafetyCheckRepository(
            transport,
            FakeSafetyCodec,
            SessionRuntime(),
            "safety-command",
        )

        assertEquals(SafetyCheckResult.Failed, repository.check(source))
        assertEquals(null, transport.request)
    }

    private val source = ConfirmedSource(
        text = "日记正文",
        sourceType = SourceType.DIARY,
        contentShape = SourceContentShape.ARTICLE,
    )
}

private class RecordingSafetyTransport(private val response: HttpResponse) : HttpTransport {
    var request: HttpRequest? = null
    override suspend fun execute(request: HttpRequest): HttpResponse {
        this.request = request
        return response
    }
}

private object FakeSafetyCodec : SafetyCheckWireCodec {
    override fun request(source: ConfirmedSource, commandId: String): String =
        "${source.text}|${source.sourceType}|${source.contentShape}|$commandId"

    override fun result(body: String): SafetyCheckResult {
        check(body == "pass")
        return SafetyCheckResult.Passed
    }
}
