package com.reallanguage.app.feature.safety.data

import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.safety.domain.ManualReviewResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ManualReviewUnavailableRepositoryTest {
    @Test fun unavailableTargetIsNotAnUnexpectedNetworkFailureOrDecodedSource() = runTest {
        var decoded = 0
        val repository = HttpManualReviewResultRepository({ HttpResponse(404, "not-a-source") }, session(),
            ManualReviewResultWireCodec { decoded++; error("must not decode") })
        assertEquals(ManualReviewResult.Unavailable, repository.load("review-1"))
        assertEquals(0, decoded)
    }

    @Test fun authAndTemporaryServerFailuresStayDistinctFromUnavailable() = runTest {
        for ((status, expected) in listOf(401 to ManualReviewResult.RequiresLogin, 500 to ManualReviewResult.Failed, 503 to ManualReviewResult.Failed)) {
            val repository = HttpManualReviewResultRepository({ HttpResponse(status, "") }, session())
            assertEquals(expected, repository.load("review-1"))
        }
    }

    @Test fun leavingTheRequestPropagatesCancellationInsteadOfInventingAResultFailure() = runTest {
        val repository = HttpManualReviewResultRepository({ throw CancellationException("isolated-cancel") }, session())
        try { repository.load("review-1"); fail("Cancellation must propagate") }
        catch (_: CancellationException) { /* expected */ }
    }

    @Test fun aLate404FromThePreviousIdentityDoesNotBecomeTheNewAccountsTerminalState() = runTest {
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<HttpResponse>()
        val runtime = session()
        val repository = HttpManualReviewResultRepository({ entered.complete(Unit); response.await() }, runtime)
        val result = async { repository.load("review-1") }
        entered.await()
        runtime.update("other-token", "other-account")
        response.complete(HttpResponse(404, ""))
        assertEquals(ManualReviewResult.Failed, result.await())
    }

    private fun session() = SessionRuntime().apply { update("fixture-token", "fixture-account") }
}
