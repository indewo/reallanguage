package com.reallanguage.app.feature.billing.data

import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.billing.domain.AiBillingSnapshot
import com.reallanguage.app.feature.billing.domain.AiBillingSnapshotResult
import com.reallanguage.app.feature.billing.domain.AiChargeRecord
import com.reallanguage.app.feature.billing.domain.AiChargeType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpAiBillingRepositoryTest {
    @Test fun loadsAuthenticatedRmbBalanceAndActualCharges() = runTest {
        val runtime = SessionRuntime().apply { update("token", "account") }
        val repository = HttpAiBillingRepository(
            transport = { request ->
                assertEquals("GET", request.method)
                assertEquals("/api/v1/billing/summary", request.path)
                assertEquals("Bearer token", request.headers["Authorization"])
                HttpResponse(200, "ignored")
            },
            runtime = runtime,
            codec = { AiBillingSnapshot(800, listOf(AiChargeRecord("attempt", 25, 10, "provider/model", AiChargeType.USER_RETRY, "生成英语表达"))) },
        )
        val result = repository.loadSnapshot() as AiBillingSnapshotResult.Ready
        assertEquals(800, result.snapshot.balanceAmountFen)
        assertEquals(AiChargeType.USER_RETRY, result.snapshot.charges.single().callType)
    }

    @Test fun failsClosedWithoutSessionOrSuccessfulResponse() = runTest {
        val runtime = SessionRuntime()
        val repository = HttpAiBillingRepository({ HttpResponse(500, "") }, runtime)
        assertTrue(repository.loadSnapshot() is AiBillingSnapshotResult.Failed)
        runtime.update("token", "account")
        assertTrue(repository.loadSnapshot() is AiBillingSnapshotResult.Failed)
    }
}
