package com.reallanguage.app.feature.safety.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.safety.domain.ManualReviewSubmissionResult
import com.reallanguage.app.feature.safety.domain.SafetyHandlingCommandResult
import com.reallanguage.app.feature.safety.domain.SafetyHandlingDetailsResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpSafetyHandlingRepositoryTest {
    @Test fun loadsOwnedCaseAndMapsAllVisibleEvidence() = runTest {
        val transport = QueueTransport(mutableListOf(HttpResponse(200, "details")))
        val result = repository(transport).load(caseId)
        val details = (result as SafetyHandlingDetailsResult.Ready).details
        assertEquals("联系张三", details.originalSource.text)
        assertEquals("联系某人", details.deidentifiedSource.text)
        assertEquals("隐藏姓名", details.edits.single().explanationZh)
        assertEquals("Bearer token", transport.requests.single().headers["Authorization"])
    }

    @Test fun usesSeparateStableCommandsForEveryExplicitAction() = runTest {
        val transport = QueueTransport(mutableListOf(HttpResponse(200, "completed"), HttpResponse(200, "completed"), HttpResponse(200, "review")))
        val repository = repository(transport)
        assertEquals(SafetyHandlingCommandResult.Completed, repository.acceptDeidentified(caseId))
        assertEquals(SafetyHandlingCommandResult.Completed, repository.returnToEdit(caseId))
        assertEquals(ManualReviewSubmissionResult.Submitted("review-1"), repository.submitManualReview(caseId))
        assertEquals(listOf("command:accept-1", "command:return-1", "command:review-1"), transport.requests.map { it.body })
    }

    @Test fun neverSendsBlockedContentOperationsWithoutAuthentication() = runTest {
        val transport = QueueTransport(mutableListOf())
        val repository = HttpSafetyHandlingRepository(transport, SessionRuntime(), ids, FakeSafetyHandlingCodec)
        assertEquals(SafetyHandlingDetailsResult.Failed, repository.load(caseId))
        assertEquals(emptyList<HttpRequest>(), transport.requests)
    }

    private fun repository(transport: QueueTransport) = HttpSafetyHandlingRepository(transport, SessionRuntime().apply { update("token", "fixture-owner") }, ids, FakeSafetyHandlingCodec)
    private val ids = SafetyHandlingCommandIds("accept-1", "return-1", "review-1")
    private val caseId = "20000000-0000-4000-8000-000000000001"
}

private class QueueTransport(private val responses: MutableList<HttpResponse>) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    override suspend fun execute(request: HttpRequest): HttpResponse { requests += request; return responses.removeAt(0) }
}

private object FakeSafetyHandlingCodec : SafetyHandlingWireCodec {
    override fun reviewStatus(body: String) = com.reallanguage.app.feature.safety.domain.ManualReviewSubmissionStatus.PENDING
    override fun command(commandId: String) = "command:$commandId"
    override fun details(body: String) = com.reallanguage.app.feature.safety.domain.SafetyCaseDetails(
        safetyCaseId = "20000000-0000-4000-8000-000000000001",
        originalSource = com.reallanguage.app.feature.safety.domain.ConfirmedSource("联系张三", com.reallanguage.app.feature.safety.domain.SourceType.DIARY, com.reallanguage.app.feature.safety.domain.SourceContentShape.ARTICLE),
        userReasonZh = "具体原因",
        deidentifiedSource = com.reallanguage.app.feature.safety.domain.ConfirmedSource("联系某人", com.reallanguage.app.feature.safety.domain.SourceType.DIARY, com.reallanguage.app.feature.safety.domain.SourceContentShape.ARTICLE),
        edits = listOf(com.reallanguage.app.feature.safety.domain.SafetyEdit("张三", "某人", "隐藏姓名")),
    )
    override fun completed(body: String) = body == "completed"
    override fun reviewRequestId(body: String) = if (body == "review") "review-1" else error("invalid")
}
