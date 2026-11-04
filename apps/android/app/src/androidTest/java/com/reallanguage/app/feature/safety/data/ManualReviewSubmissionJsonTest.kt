package com.reallanguage.app.feature.safety.data

import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.safety.domain.ManualReviewSubmissionResult
import com.reallanguage.app.feature.safety.domain.ManualReviewSubmissionStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ManualReviewSubmissionJsonTest {
    @Test fun realJsonPreservesAllExplicitServerStatusesAndRejectsMissingOrInvalidStatus() = runBlocking {
        for (status in ManualReviewSubmissionStatus.entries) {
            assertEquals(ManualReviewSubmissionResult.Submitted("review-1", status), read(body().put("reviewStatus", status.name)))
        }
        for (value in listOf<Any>(JSONObject.NULL, "UNKNOWN", 3, true)) {
            assertEquals(ManualReviewSubmissionResult.Failed, read(body().put("reviewStatus", value)))
        }
        assertEquals(ManualReviewSubmissionResult.Failed, read(body()))
    }
    @Test fun realJsonCannotTreatArbitrarySuccessfulHttpPayloadsAsSubmittedReviews() = runBlocking {
        for (value in listOf<Any>(JSONObject.NULL, 4, "", " ")) {
            assertEquals(ManualReviewSubmissionResult.Failed, read(body().put("reviewStatus", "PENDING").put("reviewRequestId", value)))
        }
        assertEquals(ManualReviewSubmissionResult.Failed, read(body().put("reviewStatus", "PENDING").put("status", "COMPLETED")))
    }
    private fun body() = JSONObject().put("status", "SUBMITTED").put("reviewRequestId", "review-1")
    private suspend fun read(body: JSONObject) = HttpSafetyHandlingRepository({ HttpResponse(200, body.toString()) },
        SessionRuntime().apply { update("fixture-token", "fixture-owner") }, SafetyHandlingCommandIds("accept", "edit", "review")).submitManualReview("case-1")
}
