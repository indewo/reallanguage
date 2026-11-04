package com.reallanguage.app.feature.billing.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.billing.domain.RefundPageRepository
import com.reallanguage.app.feature.billing.domain.RefundPageResult
import com.reallanguage.app.feature.billing.domain.RefundPageSnapshot
import com.reallanguage.app.feature.billing.domain.RefundRequestStatus
import com.reallanguage.app.feature.billing.domain.RefundSubmitResult
import com.reallanguage.app.feature.billing.domain.MinorRechargeSupportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class HttpRefundRepository(
    private val transport: HttpTransport,
    private val runtime: SessionRuntime,
    private val codec: RefundWireCodec = JsonRefundWireCodec(),
) : RefundPageRepository {
    override suspend fun loadPage(): RefundPageResult = authenticated { token ->
        val response = transport.execute(HttpRequest("GET", "/api/v1/billing/refunds/current", "", mapOf("Authorization" to "Bearer $token")))
        if (response.statusCode == 200) RefundPageResult.Ready(codec.page(response.body)) else RefundPageResult.Failed
    } ?: RefundPageResult.Failed

    override suspend fun submitRefund(amountFen: Long, commandId: String): RefundSubmitResult = authenticated { token ->
        val response = transport.execute(HttpRequest(
            "POST", "/api/v1/billing/refunds", codec.command(amountFen, commandId),
            mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"),
        ))
        if (response.statusCode == 201) codec.result(response.body) else RefundSubmitResult.Failed
    } ?: RefundSubmitResult.Failed

    override suspend fun submitMinorRechargeSupport(commandId: String): MinorRechargeSupportResult = authenticated { token ->
        val response = transport.execute(HttpRequest(
            "POST", "/api/v1/billing/minor-recharge-support", codec.minorSupportCommand(commandId),
            mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"),
        ))
        if (response.statusCode == 201) codec.minorSupportResult(response.body) else MinorRechargeSupportResult.Failed
    } ?: MinorRechargeSupportResult.Failed

    private suspend fun <T> authenticated(block: suspend (String) -> T): T? = withContext(Dispatchers.IO) {
        val token = runtime.accessToken ?: return@withContext null
        runCatching { block(token) }.getOrNull()
    }
}

interface RefundWireCodec {
    fun page(body: String): RefundPageSnapshot
    fun command(amountFen: Long, commandId: String): String
    fun result(body: String): RefundSubmitResult.Accepted
    fun minorSupportCommand(commandId: String): String
    fun minorSupportResult(body: String): MinorRechargeSupportResult.Accepted
}
class JsonRefundWireCodec : RefundWireCodec {
    override fun page(body: String): RefundPageSnapshot = JSONObject(body).let {
        RefundPageSnapshot(
            refundableAmountFen = it.getLong("refundableAmountFen"),
            refundMethodLabelZh = it.getString("refundMethodLabelZh"),
            requestId = if (it.isNull("requestId")) null else it.getString("requestId"),
            requestStatus = if (it.isNull("requestStatus")) null else RefundRequestStatus.valueOf(it.getString("requestStatus")),
        )
    }
    override fun command(amountFen: Long, commandId: String) =
        JSONObject().put("amountFen", amountFen).put("commandId", commandId).toString()
    override fun result(body: String): RefundSubmitResult.Accepted = JSONObject(body).let {
        RefundSubmitResult.Accepted(it.getString("requestId"), RefundRequestStatus.valueOf(it.getString("status")))
    }
    override fun minorSupportCommand(commandId: String) = JSONObject().put("commandId", commandId).toString()
    override fun minorSupportResult(body: String) = MinorRechargeSupportResult.Accepted(JSONObject(body).getString("supportCaseId"))
}
