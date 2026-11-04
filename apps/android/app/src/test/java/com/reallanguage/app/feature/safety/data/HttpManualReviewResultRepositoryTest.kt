package com.reallanguage.app.feature.safety.data

import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.safety.domain.ManualReviewOutcome
import com.reallanguage.app.feature.safety.domain.ManualReviewResult
import com.reallanguage.app.feature.safety.domain.ReviewedSafetySource
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpManualReviewResultRepositoryTest {
    @Test fun loadsOnlyAnAuthenticatedOwnedResult() = runTest {
        val runtime = SessionRuntime().apply { update("token", "account") }
        val repository = HttpManualReviewResultRepository({ request ->
            assertEquals("/api/v1/safety/cases/manual-reviews/review-1", request.path)
            assertEquals("Bearer token", request.headers["Authorization"])
            HttpResponse(200, "approved")
        }, runtime, ManualReviewResultWireCodec { ManualReviewResult.Ready(ManualReviewOutcome.APPROVED, source) })
        assertTrue(repository.load("review-1") is ManualReviewResult.Ready)
    }
    @Test fun missingSessionOrUnknownResultFailsClosed() = runTest {
        val runtime = SessionRuntime()
        val repository = HttpManualReviewResultRepository({ HttpResponse(404, "") }, runtime, ManualReviewResultWireCodec { error("not called") })
        assertEquals(ManualReviewResult.Failed, repository.load("review-1"))
        runtime.update("token", "account")
        assertEquals(ManualReviewResult.Unavailable, repository.load("review-1"))
    }
    @Test fun expiredServerSessionPreservesTheLoginContinuationSignal() = runTest {
        val runtime = SessionRuntime().apply { update("expired", "account") }
        val repository = HttpManualReviewResultRepository({ HttpResponse(401, "") }, runtime)
        assertEquals(ManualReviewResult.RequiresLogin, repository.load("review-1"))
    }
    companion object { private val source = ReviewedSafetySource("case", "内容", SourceType.DIARY, SourceContentShape.ARTICLE, "已通过") }
}
