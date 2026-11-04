package com.reallanguage.app.feature.billing.data

import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.billing.domain.RefundPageResult
import com.reallanguage.app.feature.billing.domain.RefundPageSnapshot
import com.reallanguage.app.feature.billing.domain.RefundRequestStatus
import com.reallanguage.app.feature.billing.domain.RefundSubmitResult
import com.reallanguage.app.feature.billing.domain.MinorRechargeSupportResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpRefundRepositoryTest {
    @Test fun loadsAndRequestsServerCalculatedRefundWithAuthentication() = runTest {
        val runtime = SessionRuntime().apply { update("token", "account") }
        val repository = HttpRefundRepository(
            transport = { request ->
                assertEquals("Bearer token", request.headers["Authorization"])
                if (request.method == "GET") HttpResponse(200, "page") else HttpResponse(201, "result")
            },
            runtime = runtime,
            codec = object : RefundWireCodec {
                override fun page(body: String) = RefundPageSnapshot(3_000, "原路退回")
                override fun command(amountFen: Long, commandId: String) = "$amountFen:$commandId"
                override fun result(body: String) = RefundSubmitResult.Accepted("refund-1", RefundRequestStatus.REQUESTED)
                override fun minorSupportCommand(commandId: String) = commandId
                override fun minorSupportResult(body: String) = MinorRechargeSupportResult.Accepted("support-1")
            },
        )
        assertTrue(repository.loadPage() is RefundPageResult.Ready)
        val result = repository.submitRefund(3_000, "command") as RefundSubmitResult.Accepted
        assertEquals(RefundRequestStatus.REQUESTED, result.status)
        assertTrue(repository.submitMinorRechargeSupport("minor-command") is MinorRechargeSupportResult.Accepted)
    }

    @Test fun failsClosedWithoutSession() = runTest {
        val repository = HttpRefundRepository({ error("not called") }, SessionRuntime())
        assertTrue(repository.loadPage() is RefundPageResult.Failed)
        assertTrue(repository.submitRefund(100, "command") is RefundSubmitResult.Failed)
        assertTrue(repository.submitMinorRechargeSupport("minor-command") is MinorRechargeSupportResult.Failed)
    }
}
