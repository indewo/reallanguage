package com.reallanguage.app.feature.billing.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.billing.domain.AiBillingSnapshotResult
import com.reallanguage.app.feature.billing.domain.AiChargeType
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Android's real JSONObject through the production repository and its default codec.
 * All 200 responses and identities are in memory: no socket, payment, DB or credential access.
 * Bounds are the existing billingSummarySchema, not new payment/rounding rules. */
@RunWith(AndroidJUnit4::class)
class JsonAiBillingWireCodecTest {
    @Test fun zeroMaximumAndAnExplicitEmptyLedgerAreValid() = runBlocking {
        for (amount in listOf(0L, MAX_FEN)) {
            val result = ready(load(summary().put("balanceAmountFen", amount)
                .put("charges", JSONArray().put(charge().put("amountFen", amount)))))
            assertEquals(amount, result.snapshot.balanceAmountFen)
            assertEquals(amount, result.snapshot.charges.single().amountFen)
        }
        val empty = ready(load(summary().put("balanceAmountFen", 0).put("charges", JSONArray())))
        assertEquals(0L, empty.snapshot.balanceAmountFen)
        assertTrue(empty.snapshot.charges.isEmpty())
    }

    @Test fun distinctAttemptsWithTheSameAmountTimeAndOperationKeepAllFourCallTypes() = runBlocking {
        val values = JSONArray()
        AiChargeType.entries.forEachIndexed { index, type ->
            values.put(charge("attempt-$index").put("callType", type.name))
        }
        val result = ready(load(summary().put("charges", values)))
        assertEquals(AiChargeType.entries.toList(), result.snapshot.charges.map { it.callType })
        assertEquals(listOf("attempt-0", "attempt-1", "attempt-2", "attempt-3"), result.snapshot.charges.map { it.chargeId })
        assertTrue(result.snapshot.charges.all { it.amountFen == 25L })
        assertEquals(1, result.snapshot.charges.map { it.occurredAtEpochMillis }.distinct().size)
        assertEquals(1, result.snapshot.charges.map { it.operationLabelZh }.distinct().size)
    }

    @Test fun exactStringLengthAndFiveHundredRecordLimitsRemainAccepted() = runBlocking {
        val fullId = "i".repeat(128)
        val fullModel = "m".repeat(128)
        val fullOperation = "义".repeat(200)
        val maximumFields = charge(fullId).put("modelSource", fullModel).put("operationLabelZh", fullOperation)
        val record = ready(load(summary().put("charges", JSONArray().put(maximumFields)))).snapshot.charges.single()
        assertEquals(fullId, record.chargeId)
        assertEquals(fullModel, record.modelSource)
        assertEquals(fullOperation, record.operationLabelZh)
        val values = JSONArray()
        repeat(500) { values.put(charge("attempt-$it")) }
        val result = ready(load(summary().put("charges", values)))
        assertEquals(500, result.snapshot.charges.size)
        assertEquals("attempt-499", result.snapshot.charges.last().chargeId)
    }

    @Test fun amountsMustBeActualBoundedNonnegativeIntegersWithoutCoercionOrTruncation() = runBlocking {
        val invalid = listOf("0.5", "1.25", "\"25\"", "-1", "100000001", "9223372036854775807", "1e20", "1e999", "null", "true", "{}", "[]")
        for (field in listOf("balanceAmountFen", "amountFen")) {
            invalid.forEachIndexed { index, raw ->
                val body = summary().toString()
                val original = "\"$field\":25"
                check(body.contains(original))
                reject(body.replace(original, "\"$field\":$raw"), "$field numeric case $index")
            }
        }
    }

    @Test fun idsModelsAndOperationsMustBeOriginalStringsNotJsonConversions() = runBlocking {
        val wrongTypes = listOf<Any>(7, true, JSONObject.NULL, JSONObject().put("value", "isolated"), JSONArray().put("isolated"))
        for (field in listOf("chargeId", "modelSource", "operationLabelZh")) {
            wrongTypes.forEachIndexed { index, value ->
                reject(summary().put("charges", JSONArray().put(charge().put(field, value))).toString(), "$field type case $index")
            }
        }
    }

    @Test fun emptyAndOverlongIdsModelsAndOperationsAreRejected() = runBlocking {
        for ((field, maximum) in mapOf("chargeId" to 128, "modelSource" to 128, "operationLabelZh" to 200)) {
            for (value in listOf("", "x".repeat(maximum + 1))) {
                reject(summary().put("charges", JSONArray().put(charge().put(field, value))).toString(), "$field length ${value.length}")
            }
        }
    }

    @Test fun unknownRootAndChargeFieldsCannotBypassTheStrictPublicContract() = runBlocking {
        reject(summary().put("tokenBalance", 100).toString(), "unknown root field")
        reject(summary().put("charges", JSONArray().put(charge().put("unexpectedMetadata", "isolated"))).toString(), "unknown charge field")
    }

    @Test fun fiveHundredAndOneRecordsAreRejectedRatherThanSilentlyTruncated() = runBlocking {
        val values = JSONArray()
        repeat(501) { values.put(charge("attempt-$it")) }
        reject(summary().put("charges", values).toString(), "501 charge records")
    }

    @Test fun duplicateAttemptIdsAreRejectedEvenWhenTheirAmountsAgree() = runBlocking {
        // provider_attempt_id is one real charge identity. Different IDs with the same
        // amount/time are intentionally accepted by the separate four-call-type control.
        for (secondAmount in listOf(25, 50)) {
            val values = JSONArray().put(charge("same-attempt"))
                .put(charge("same-attempt").put("amountFen", secondAmount))
            reject(summary().put("charges", values).toString(), "duplicate charge ID")
        }
    }

    @Test fun missingFieldsAndWrongRootOrLedgerShapesRemainRejected() = runBlocking {
        for (field in listOf("balanceAmountFen", "charges")) {
            val body = summary().apply { remove(field) }
            reject(body.toString(), "missing $field")
        }
        for (field in listOf("chargeId", "amountFen", "occurredAt", "modelSource", "callType", "operationLabelZh")) {
            reject(summary().put("charges", JSONArray().put(charge().apply { remove(field) })).toString(), "missing charge $field")
        }
        for ((index, body) in listOf("", "{broken", "[]", "null", summary().put("charges", JSONObject()).toString()).withIndex()) {
            reject(body, "malformed container $index")
        }
    }

    @Test fun invalidDatesAndUnknownCallTypesRemainRejected() = runBlocking {
        for ((field, value) in listOf("occurredAt" to "not-a-date", "callType" to "UNKNOWN_CALL")) {
            reject(summary().put("charges", JSONArray().put(charge().put(field, value))).toString(), "invalid $field")
        }
    }

    private suspend fun reject(body: String, case: String) {
        assertEquals(case, AiBillingSnapshotResult.Failed, load(body))
    }

    private fun ready(result: AiBillingSnapshotResult): AiBillingSnapshotResult.Ready {
        assertTrue("A valid public summary must remain readable", result is AiBillingSnapshotResult.Ready)
        return result as AiBillingSnapshotResult.Ready
    }

    private suspend fun load(body: JSONObject) = load(body.toString())

    private suspend fun load(body: String): AiBillingSnapshotResult {
        val runtime = SessionRuntime().apply { update("isolated-codec-token", "isolated-codec-owner") }
        val epoch = runtime.accountEpoch
        val requests = mutableListOf<HttpRequest>()
        val result = HttpAiBillingRepository(HttpTransport {
            requests += it
            HttpResponse(200, body)
        }, runtime).loadSnapshot()
        assertEquals(1, requests.size)
        assertEquals("GET", requests.single().method)
        assertEquals("/api/v1/billing/summary", requests.single().path)
        assertEquals("", requests.single().body)
        assertEquals("Bearer isolated-codec-token", requests.single().headers["Authorization"])
        assertEquals("isolated-codec-owner", runtime.accountId)
        assertEquals(epoch, runtime.accountEpoch)
        return result
    }

    private fun summary() = JSONObject().put("balanceAmountFen", 25)
        .put("charges", JSONArray().put(charge()))

    private fun charge(id: String = "isolated-attempt") = JSONObject()
        .put("chargeId", id).put("amountFen", 25)
        .put("occurredAt", "2026-09-02T00:00:00.000Z")
        .put("modelSource", "isolated-provider/model").put("callType", "INITIAL_CALL")
        .put("operationLabelZh", "隔离调用记录")

    companion object { private const val MAX_FEN = 100_000_000L }
}
