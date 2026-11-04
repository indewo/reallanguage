package com.reallanguage.app.feature.billing.data

import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.billing.domain.PaymentMethod
import com.reallanguage.app.feature.billing.domain.RechargeOption
import com.reallanguage.app.feature.billing.domain.RechargeOrderStatus
import com.reallanguage.app.feature.billing.domain.RechargePageResult
import com.reallanguage.app.feature.billing.domain.RechargePageSnapshot
import com.reallanguage.app.feature.billing.domain.RechargeSubmitResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpRechargeRepositoryTest {
    @Test fun loadsServerOptionsAndCreatesAuthenticatedIdempotentOrder() = runTest {
        val runtime = SessionRuntime().apply { update("token", "account") }
        val requests = mutableListOf<String>()
        val repository = HttpRechargeRepository(
            transport = { request ->
                requests += "${request.method}:${request.path}:${request.headers["Authorization"]}"
                if (request.method == "GET") HttpResponse(200, "page") else HttpResponse(201, "order")
            },
            runtime = runtime,
            codec = object : RechargeWireCodec {
                override fun page(body: String) = RechargePageSnapshot(listOf(RechargeOption(5_000, "愿景")))
                override fun command(amountFen: Long, method: PaymentMethod, commandId: String) = "$amountFen:${method.name}:$commandId"
                override fun order(body: String) = RechargeSubmitResult.Accepted("order-1", RechargeOrderStatus.PENDING_PAYMENT, "launch")
            },
        )
        assertTrue(repository.loadPage() is RechargePageResult.Ready)
        val order = repository.createOrder(5_000, PaymentMethod.ALIPAY, "command") as RechargeSubmitResult.Accepted
        assertEquals("launch", order.paymentLaunch)
        assertTrue(repository.loadOrder("order-1") is RechargeSubmitResult.Accepted)
        assertEquals(listOf(
            "GET:/api/v1/billing/recharge-config:null",
            "POST:/api/v1/billing/recharge-orders:Bearer token",
            "GET:/api/v1/billing/recharge-orders/order-1:Bearer token",
        ), requests)
    }
}
