package com.reallanguage.app.feature.safety.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.safety.domain.ManualReviewSubmissionResult
import com.reallanguage.app.feature.safety.domain.ManualReviewSubmissionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ManualReviewSubmissionReplayTest {
    @Test fun acknowledgedSubmissionCarriesTheActualPendingOrAdjudicatedStatus() = runTest {
        for (status in ManualReviewSubmissionStatus.entries) {
            val repository = HttpSafetyHandlingRepository({ HttpResponse(200, status.name) }, runtime(), ids, codec)
            assertEquals(ManualReviewSubmissionResult.Submitted("review-1", status), repository.submitManualReview("case-1"))
        }
    }
    @Test fun anOldResponseWithoutAStatusCannotPretendTheRequestIsStillPending() = runTest {
        val repository = HttpSafetyHandlingRepository({ HttpResponse(200, "legacy") }, runtime(), ids, codec)
        assertEquals(ManualReviewSubmissionResult.Failed, repository.submitManualReview("case-1"))
    }
    @Test fun lostResponseRetriesTheSameCommandAndReadsTheExistingApprovedReview() = runTest {
        val requests = mutableListOf<HttpRequest>()
        val repository = HttpSafetyHandlingRepository({ request ->
            requests += request
            if (requests.size == 1) throw IOException("isolated lost response")
            HttpResponse(200, "APPROVED")
        }, runtime(), ids, codec)
        assertEquals(ManualReviewSubmissionResult.Failed, repository.submitManualReview("case-1"))
        assertEquals(ManualReviewSubmissionResult.Submitted("review-1", ManualReviewSubmissionStatus.APPROVED), repository.submitManualReview("case-1"))
        assertEquals(listOf("command:review-stable", "command:review-stable"), requests.map { it.body })
    }
    @Test fun aLateAcknowledgementCannotMoveTheReplacementAccountIntoThePreviousReview() = runTest {
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<HttpResponse>()
        val session = runtime()
        val repository = HttpSafetyHandlingRepository({ entered.complete(Unit); response.await() }, session, ids, codec)
        val result = async { repository.submitManualReview("case-1") }
        entered.await()
        session.update("other-token", "other-account")
        response.complete(HttpResponse(200, "PENDING"))
        assertEquals(ManualReviewSubmissionResult.Failed, result.await())
    }
    @Test fun requestCancellationDoesNotBecomeARetryableSubmissionFailure() = runTest {
        val repository = HttpSafetyHandlingRepository({ throw CancellationException("isolated cancel") }, runtime(), ids, codec)
        try { repository.submitManualReview("case-1"); fail("Cancellation must propagate") }
        catch (_: CancellationException) { /* expected */ }
    }
    @Test fun unsuccessfulResponsesDoNotClaimASubmission() = runTest {
        for (code in listOf(400, 401, 404, 500)) {
            assertEquals(ManualReviewSubmissionResult.Failed, HttpSafetyHandlingRepository({ HttpResponse(code, "PENDING") }, runtime(), ids, codec).submitManualReview("case-1"))
        }
    }
    private fun runtime() = SessionRuntime().apply { update("fixture-token", "fixture-owner") }
    private val ids = SafetyHandlingCommandIds("accept-stable", "edit-stable", "review-stable")
    private val codec = object : SafetyHandlingWireCodec {
        override fun command(commandId: String) = "command:$commandId"
        override fun details(body: String) = error("not used")
        override fun completed(body: String) = false
        override fun reviewRequestId(body: String) = "review-1"
        override fun reviewStatus(body: String) = ManualReviewSubmissionStatus.entries.firstOrNull { it.name == body }
    }
}
