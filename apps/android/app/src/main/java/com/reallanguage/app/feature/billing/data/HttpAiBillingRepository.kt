package com.reallanguage.app.feature.billing.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.billing.domain.AiBillingRepository
import com.reallanguage.app.feature.billing.domain.AiBillingSnapshot
import com.reallanguage.app.feature.billing.domain.AiBillingSnapshotResult
import com.reallanguage.app.feature.billing.domain.AiChargeRecord
import com.reallanguage.app.feature.billing.domain.AiChargeType
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class HttpAiBillingRepository(
    private val transport: HttpTransport,
    private val runtime: SessionRuntime,
    private val codec: AiBillingWireCodec = JsonAiBillingWireCodec(),
    private val groupDaily: Boolean = false,
) : AiBillingRepository {
    private val ownerAccountId = runtime.accountId
    private val ownerAccountEpoch = runtime.accountEpoch
    private fun ownerIsActive() = ownerAccountId != null && runtime.accountId == ownerAccountId &&
        runtime.accountEpoch == ownerAccountEpoch && runtime.accessToken != null

    override suspend fun loadSnapshot(): AiBillingSnapshotResult = withContext(Dispatchers.IO) {
        if (!ownerIsActive()) return@withContext AiBillingSnapshotResult.Failed
        val token = runtime.accessToken ?: return@withContext AiBillingSnapshotResult.Failed
        try {
            val zone = java.net.URLEncoder.encode(java.time.ZoneId.systemDefault().id, "UTF-8")
            val path = if (groupDaily) "/api/v1/billing/daily-summary?timeZone=$zone" else "/api/v1/billing/summary"
            val response = transport.execute(HttpRequest("GET", path, "", mapOf("Authorization" to "Bearer $token")))
            if (response.statusCode != 200 || !ownerIsActive()) return@withContext AiBillingSnapshotResult.Failed
            val snapshot = codec.snapshot(response.body)
            if (ownerIsActive()) AiBillingSnapshotResult.Ready(snapshot) else AiBillingSnapshotResult.Failed
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { AiBillingSnapshotResult.Failed }
    }
}

fun interface AiBillingWireCodec { fun snapshot(body: String): AiBillingSnapshot }
class JsonAiBillingWireCodec : AiBillingWireCodec {
    override fun snapshot(body: String): AiBillingSnapshot {
        val root = JSONObject(body)
        if (root.has("dailyCharges")) {
            require(root.keys().asSequence().toSet() == setOf("balanceAmountFen", "dailyCharges"))
            val rows = root.getJSONArray("dailyCharges")
            require(rows.length() <= 500)
            val identities = mutableSetOf<Pair<String, String>>()
            return AiBillingSnapshot(root.amountFen("balanceAmountFen"), emptyList(), List(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                require(row.keys().asSequence().toSet() == setOf("date", "operationLabelZh", "count", "amountFen"))
                val date = row.originalString("date", 10)
                require(java.time.LocalDate.parse(date).toString() == date)
                val label = row.originalString("operationLabelZh", 200)
                require(identities.add(date to label))
                val count = row.get("count")
                val amount = row.get("amountFen")
                require(count is Number && count.toDouble() in 1.0..Int.MAX_VALUE.toDouble() && count.toDouble() % 1 == 0.0)
                require(amount is Number && amount.toDouble() in 0.0..9_007_199_254_740_991.0 && amount.toDouble() % 1 == 0.0)
                com.reallanguage.app.feature.billing.domain.DailyAiCharge(date, label, count.toInt(), amount.toLong())
            })
        }
        require(root.keys().asSequence().toSet() == setOf("balanceAmountFen", "charges"))
        val charges = root.getJSONArray("charges")
        require(charges.length() <= 500)
        val identities = mutableSetOf<String>()
        return AiBillingSnapshot(
            balanceAmountFen = root.amountFen("balanceAmountFen"),
            charges = List(charges.length()) { index ->
                val charge = charges.getJSONObject(index)
                require(charge.keys().asSequence().toSet() == setOf("chargeId", "amountFen", "occurredAt", "modelSource", "callType", "operationLabelZh"))
                val identity = charge.originalString("chargeId", 128)
                require(identities.add(identity))
                AiChargeRecord(
                    chargeId = identity,
                    amountFen = charge.amountFen("amountFen"),
                    occurredAtEpochMillis = Instant.parse(charge.getString("occurredAt")).toEpochMilli(),
                    modelSource = charge.originalString("modelSource", 128),
                    callType = AiChargeType.valueOf(charge.getString("callType")),
                    operationLabelZh = charge.originalString("operationLabelZh", 200),
                )
            },
        )
    }

    private fun JSONObject.amountFen(name: String): Long {
        val value = get(name)
        require(value is Number)
        val amount = value.toDouble()
        // Existing public contract: integer fen in 0..100_000_000. Never coerce
        // strings, round fractions, silently overflow, or invent a replacement balance.
        require(amount.isFinite() && amount in 0.0..100_000_000.0 && amount % 1.0 == 0.0)
        return amount.toLong()
    }

    private fun JSONObject.originalString(name: String, maximum: Int): String {
        val value = get(name)
        require(value is String && value.length in 1..maximum)
        return value
    }
}
