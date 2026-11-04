package com.reallanguage.app.feature.knowledge.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.settings.domain.BasicVocabulary
import com.reallanguage.app.feature.settings.domain.PurposeVocabulary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real Android JSONObject, isolated in-memory transport/runtime only. No Context,
 * database, preferences, account credentials, socket, HTTP server or AI is used.
 * Scope rules match InitialSettings enums, contracts and the existing strict
 * settings codec: exactly one valid basic, zero to three unique valid purposes.
 */
class HttpCurrentVocabularyScopeSourceTest {
    @Test
    fun eachBasicAndTheFullPurposeSetLoadWhileUnrelatedSettingsRemainAllowed() = runBlocking {
        val purposes = PurposeVocabulary.entries.map { it.name }
        for (basic in BasicVocabulary.entries) {
            val response = body(basic.name, JSONArray(purposes))
                .put("formalityLevel", 3)
                .put("slangEnabled", false)
                .put("styleReference", "Clear and concise")
                .put("voiceEnabled", true)
                .put("autoPlayEnabled", false)
                .put("speechSpeedLevel", 3)
                .put("futureNonScopePreference", JSONObject().put("enabled", true))
            assertEquals(setOf(basic.name) + purposes, read(response.toString()))
        }
    }

    @Test
    fun basicOnlyWithAnExplicitEmptyPurposeArrayIsAValidNonemptyScope() = runBlocking {
        for (basic in BasicVocabulary.entries) {
            assertEquals(setOf(basic.name), read(body(basic.name, JSONArray()).toString()))
        }
    }

    @Test
    fun unknownBlankOrNonexactBasicNameIsNotASuccessfulScope() = runBlocking {
        for (value in listOf("NOT_A_SCOPE", "", " ", "cet_4", "CET_4 ", "GRE")) {
            assertNull("Reject an invalid basic enum, not a scope that filters all saved words away", read(body(value, JSONArray()).toString()))
        }
    }

    @Test
    fun basicJsonNumbersBooleansNullObjectsAndArraysMustNotBeStringCoerced() = runBlocking {
        val values = listOf<Any>(4, true, JSONObject.NULL, JSONObject().put("name", "CET_4"), JSONArray().put("CET_4"))
        for ((index, value) in values.withIndex()) {
            assertNull("Reject basic JSON type case $index before coercion", read(body(value, JSONArray()).toString()))
        }
    }

    @Test
    fun unknownBlankNonexactOrBasicEnumUsedAsPurposeIsRejected() = runBlocking {
        for (value in listOf("NOT_A_PURPOSE", "", " ", "gre", "GRE ", "CET_4")) {
            assertNull("Purpose entries must belong to their own confirmed enum", read(body("CET_4", JSONArray().put(value)).toString()))
        }
    }

    @Test
    fun purposeArrayMembersMustBeOriginalStringsNotCoercedJsonValues() = runBlocking {
        val values = listOf<Any>(1, false, JSONObject.NULL, JSONObject().put("name", "GRE"), JSONArray().put("GRE"))
        for ((index, value) in values.withIndex()) {
            assertNull("Reject purpose member JSON type case $index before coercion", read(body("CET_4", JSONArray().put(value)).toString()))
        }
    }

    @Test
    fun duplicatePurposesOrMoreThanTheConfirmedThreeAreNotSilentlyDeduplicated() = runBlocking {
        // Both constraints already exist in contracts and JsonSubsequentSettingsWireCodec.
        for (values in listOf(listOf("GRE", "GRE"), listOf("IELTS_TOEFL", "GRE", "BUSINESS_ENGLISH", "GRE"))) {
            assertNull("An invalid purpose selection must not be accepted after Set deduplication", read(body("CET_4", JSONArray(values)).toString()))
        }
    }

    @Test
    fun purposeFieldMustBeAnActualArrayRatherThanNullObjectNumberOrEncodedArrayText() = runBlocking {
        val values = listOf<Any>(JSONObject.NULL, JSONObject().put("0", "GRE"), 0, true, "[\"GRE\"]", "GRE")
        for ((index, value) in values.withIndex()) {
            assertNull("Reject purpose container shape $index", read(body("CET_4", value).toString()))
        }
    }

    @Test
    fun missingScopeFieldsAndMalformedJsonDoNotInventDefaultOrEmptyScopes() = runBlocking {
        val invalid = listOf(
            JSONObject().toString(),
            JSONObject().put("basicVocabulary", "CET_4").toString(),
            JSONObject().put("purposeVocabularies", JSONArray()).toString(),
            JSONObject().put("settings", body("CET_4", JSONArray())).toString(),
            "{broken",
            "[]",
        )
        for ((index, value) in invalid.withIndex()) assertNull("Reject missing/malformed current-scope case $index", read(value))
    }

    @Test
    fun transportCancellationRetainsItsTypeAndOriginalMessageInsteadOfBecomingUnavailable() = runBlocking {
        val runtime = runtime()
        var calls = 0
        val expected = CancellationException("isolated-scope-read-cancelled")
        val source = HttpCurrentVocabularyScopeSource(HttpTransport {
            calls++
            throw expected
        }, runtime)
        val observed = try {
            source.load()
            null
        } catch (cancelled: CancellationException) {
            cancelled
        }
        assertNotNull("Cancellation must propagate, not become null/unavailable", observed)
        assertEquals("isolated-scope-read-cancelled", observed?.message)
        assertEquals(1, calls)
        assertEquals("isolated-scope-owner", runtime.accountId)
    }

    @Test
    fun non200AndOrdinaryTransportFailureRemainUnavailableAndNoSessionMakesNoRequest() = runBlocking {
        val valid = body("CET_4", JSONArray()).toString()
        for (status in listOf(204, 401, 403, 500)) assertNull(read(valid, status))
        var failedCalls = 0
        assertNull(HttpCurrentVocabularyScopeSource(HttpTransport {
            failedCalls++
            error("isolated ordinary transport failure")
        }, runtime()).load())
        assertEquals(1, failedCalls)
        var unauthenticatedCalls = 0
        assertNull(HttpCurrentVocabularyScopeSource(HttpTransport {
            unauthenticatedCalls++
            HttpResponse(200, valid)
        }, SessionRuntime()).load())
        assertEquals(0, unauthenticatedCalls)
    }

    private suspend fun read(body: String, status: Int = 200): Set<String>? {
        val runtime = runtime()
        val ownerEpoch = runtime.accountEpoch
        val requests = mutableListOf<HttpRequest>()
        val result = HttpCurrentVocabularyScopeSource(HttpTransport {
            requests += it
            HttpResponse(status, body)
        }, runtime).load()
        assertEquals(1, requests.size)
        assertEquals("GET", requests.single().method)
        assertEquals("/api/v1/settings/current", requests.single().path)
        assertTrue(requests.single().body.isEmpty())
        assertEquals("Bearer isolated-scope-token", requests.single().headers["Authorization"])
        assertEquals("isolated-scope-owner", runtime.accountId)
        assertEquals(ownerEpoch, runtime.accountEpoch)
        return result
    }

    private fun body(basic: Any, purposes: Any) = JSONObject()
        .put("basicVocabulary", basic).put("purposeVocabularies", purposes)

    private fun runtime() = SessionRuntime().apply { update("isolated-scope-token", "isolated-scope-owner") }
}
