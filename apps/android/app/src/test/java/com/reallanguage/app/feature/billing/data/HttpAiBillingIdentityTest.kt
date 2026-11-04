package com.reallanguage.app.feature.billing.data

import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.billing.domain.AiBillingSnapshot
import com.reallanguage.app.feature.billing.domain.AiBillingSnapshotResult
import com.reallanguage.app.feature.billing.domain.AiChargeRecord
import com.reallanguage.app.feature.billing.domain.AiChargeType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only in-memory identities and transport/codec fixtures; no account, payment or HTTP service. */
class HttpAiBillingIdentityTest {
    @Test fun previousAccountRepositoryCannotBorrowTheReplacementAccountsToken() = runTest {
        val runtime = accountSession()
        var requests = 0
        val repository = HttpAiBillingRepository({
            requests++
            HttpResponse(200, "isolated-summary")
        }, runtime, AiBillingWireCodec { snapshot })
        runtime.update("isolated-b-token", "isolated-b")

        assertEquals(AiBillingSnapshotResult.Failed, repository.loadSnapshot())
        assertEquals(0, requests)
    }

    @Test fun signingOutAndBackIntoTheSameAccountCannotReviveTheOldRepository() = runTest {
        val runtime = accountSession()
        var requests = 0
        val repository = HttpAiBillingRepository({
            requests++
            HttpResponse(200, "isolated-summary")
        }, runtime, AiBillingWireCodec { snapshot })
        val epoch = runtime.accountEpoch
        relogin(runtime)
        assertTrue(runtime.accountEpoch > epoch)

        assertEquals(AiBillingSnapshotResult.Failed, repository.loadSnapshot())
        assertEquals(0, requests)
    }

    @Test fun previousAccountsLateHttpResponseIsRejectedBeforeDecoding() = runTest {
        val runtime = accountSession()
        var decodes = 0
        val repository = HttpAiBillingRepository({ request ->
            assertEquals("Bearer isolated-a-token", request.headers["Authorization"])
            runtime.update("isolated-b-token", "isolated-b")
            HttpResponse(200, "isolated-a-summary")
        }, runtime, AiBillingWireCodec { decodes++; snapshot })

        assertEquals(AiBillingSnapshotResult.Failed, repository.loadSnapshot())
        assertEquals(0, decodes)
    }

    @Test fun sameAccountsLateHttpResponseAfterReloginIsRejectedBeforeDecoding() = runTest {
        val runtime = accountSession()
        var decodes = 0
        val repository = HttpAiBillingRepository({ request ->
            assertEquals("Bearer isolated-a-token", request.headers["Authorization"])
            relogin(runtime)
            HttpResponse(200, "isolated-old-session-summary")
        }, runtime, AiBillingWireCodec { decodes++; snapshot })

        assertEquals(AiBillingSnapshotResult.Failed, repository.loadSnapshot())
        assertEquals(0, decodes)
    }

    @Test fun accountSwitchDuringDecodeCannotPublishThePreviousAccountsSummary() = runTest {
        val runtime = accountSession()
        val repository = HttpAiBillingRepository({ HttpResponse(200, "isolated-summary") }, runtime,
            AiBillingWireCodec {
                runtime.update("isolated-b-token", "isolated-b")
                snapshot
            })

        assertEquals(AiBillingSnapshotResult.Failed, repository.loadSnapshot())
    }

    @Test fun sameAccountReloginDuringDecodeCannotPublishTheOldSessionsSummary() = runTest {
        val runtime = accountSession()
        val repository = HttpAiBillingRepository({ HttpResponse(200, "isolated-summary") }, runtime,
            AiBillingWireCodec { relogin(runtime); snapshot })

        assertEquals(AiBillingSnapshotResult.Failed, repository.loadSnapshot())
    }

    @Test fun transportCancellationRetainsItsTypeMessageAndOriginalCause() = runTest {
        val cancelled = CancellationException("isolated billing load cancelled")
        var decodes = 0
        val repository = HttpAiBillingRepository({ throw cancelled }, accountSession(),
            AiBillingWireCodec { decodes++; snapshot })

        val actual = runCatching { repository.loadSnapshot() }.exceptionOrNull()
        assertTrue("Cancellation must propagate rather than become Failed", actual is CancellationException)
        assertEquals(cancelled.message, actual?.message)
        assertTrue("Coroutine stack recovery must retain the original cancellation in its cause chain",
            generateSequence(actual) { it.cause }.any { it === cancelled })
        assertEquals(0, decodes)
    }

    @Test fun ordinarySameAccountRefreshBeforeHttpAndDuringDecodeKeepsOwnership() = runTest {
        val runtime = accountSession()
        val epoch = runtime.accountEpoch
        val authorizations = mutableListOf<String?>()
        val repository = HttpAiBillingRepository({ request ->
            assertEquals("GET", request.method)
            assertEquals("/api/v1/billing/summary", request.path)
            authorizations += request.headers["Authorization"]
            runtime.update("isolated-http-refresh", "isolated-a")
            HttpResponse(200, "isolated-summary")
        }, runtime, AiBillingWireCodec {
            runtime.update("isolated-codec-refresh", "isolated-a")
            snapshot
        })
        runtime.update("isolated-before-refresh", "isolated-a")

        assertEquals(AiBillingSnapshotResult.Ready(snapshot), repository.loadSnapshot())
        assertEquals(AiBillingSnapshotResult.Ready(snapshot), repository.loadSnapshot())
        assertEquals(epoch, runtime.accountEpoch)
        assertEquals(listOf("Bearer isolated-before-refresh", "Bearer isolated-codec-refresh"), authorizations)
    }

    @Test fun aNewRepositoryAfterSameAccountReloginCanReadTheNewSessionsSummary() = runTest {
        val runtime = accountSession()
        relogin(runtime)
        var requests = 0
        val repository = HttpAiBillingRepository({ request ->
            requests++
            assertEquals("Bearer isolated-new-a-token", request.headers["Authorization"])
            HttpResponse(200, "isolated-new-summary")
        }, runtime, AiBillingWireCodec { snapshot })

        assertEquals(AiBillingSnapshotResult.Ready(snapshot), repository.loadSnapshot())
        assertEquals(1, requests)
    }

    private fun accountSession() = SessionRuntime().apply { update("isolated-a-token", "isolated-a") }
    private fun relogin(runtime: SessionRuntime) {
        runtime.clear()
        runtime.update("isolated-new-a-token", "isolated-a")
    }
    private val snapshot = AiBillingSnapshot(800, listOf(
        AiChargeRecord("isolated-charge", 25, 10, "isolated-provider/model", AiChargeType.INITIAL_CALL, "隔离账单记录"),
    ))
}
