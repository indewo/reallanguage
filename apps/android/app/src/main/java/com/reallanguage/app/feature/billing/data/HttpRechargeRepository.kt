package com.reallanguage.app.feature.billing.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.billing.domain.PaymentMethod
import com.reallanguage.app.feature.billing.domain.RechargeOption
import com.reallanguage.app.feature.billing.domain.RechargeOrderStatus
import com.reallanguage.app.feature.billing.domain.RechargePageRepository
import com.reallanguage.app.feature.billing.domain.RechargePageResult
import com.reallanguage.app.feature.billing.domain.RechargePageSnapshot
import com.reallanguage.app.feature.billing.domain.RechargeSubmitResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class HttpRechargeRepository(
    private val transport: HttpTransport,
    private val runtime: SessionRuntime,
    private val codec: RechargeWireCodec = JsonRechargeWireCodec(),
) : RechargePageRepository {
    override suspend fun loadPage(): RechargePageResult = withContext(Dispatchers.IO) {
        runCatching {
            val response = transport.execute(HttpRequest("GET", "/api/v1/billing/recharge-config", ""))
            if (response.statusCode == 200) RechargePageResult.Ready(codec.page(response.body)) else RechargePageResult.Failed
        }.getOrDefault(RechargePageResult.Failed)
    }

    override suspend fun createOrder(amountFen: Long, method: PaymentMethod, commandId: String): RechargeSubmitResult = withContext(Dispatchers.IO) {
        val token = runtime.accessToken ?: return@withContext RechargeSubmitResult.Failed
        runCatching {
            val response = transport.execute(HttpRequest(
                "POST", "/api/v1/billing/recharge-orders", codec.command(amountFen, method, commandId),
                mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"),
            ))
            if (response.statusCode == 201) codec.order(response.body) else RechargeSubmitResult.Failed
        }.getOrDefault(RechargeSubmitResult.Failed)
    }

    override suspend fun loadOrder(orderId: String): RechargeSubmitResult = withContext(Dispatchers.IO) {
        val token = runtime.accessToken ?: return@withContext RechargeSubmitResult.Failed
        runCatching {
            val response = transport.execute(HttpRequest(
                "GET", "/api/v1/billing/recharge-orders/$orderId", "",
                mapOf("Authorization" to "Bearer $token"),
            ))
            if (response.statusCode == 200) codec.order(response.body) else RechargeSubmitResult.Failed
        }.getOrDefault(RechargeSubmitResult.Failed)
    }
}

interface RechargeWireCodec {
    fun page(body: String): RechargePageSnapshot
    fun command(amountFen: Long, method: PaymentMethod, commandId: String): String
    fun order(body: String): RechargeSubmitResult.Accepted
}
class JsonRechargeWireCodec : RechargeWireCodec {
    override fun page(body: String): RechargePageSnapshot {
        val values = JSONObject(body).getJSONArray("recommendedOptions")
        return RechargePageSnapshot(List(values.length()) { index ->
            values.getJSONObject(index).let { RechargeOption(it.getLong("amountFen"), it.getString("visionZh")) }
        })
    }
    override fun command(amountFen: Long, method: PaymentMethod, commandId: String) =
        JSONObject().put("amountFen", amountFen).put("paymentMethod", method.name).put("commandId", commandId).toString()
    override fun order(body: String): RechargeSubmitResult.Accepted = JSONObject(body).let {
        RechargeSubmitResult.Accepted(
            it.getString("orderId"), RechargeOrderStatus.valueOf(it.getString("status")),
            if (it.isNull("paymentLaunch")) null else it.getString("paymentLaunch"),
        )
    }
}
