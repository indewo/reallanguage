package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.core.network.*
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import com.reallanguage.app.feature.formation.domain.SourceEvidence
import com.reallanguage.app.feature.learning.domain.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual Android JSON; all transport, account, and cache fixtures are private and in memory. */
class ExistingExplanationDeliveryWireTest {
    private val codec = JsonExplanationReadingWireCodec()
    private val chapter = FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I felt calm today.", "我今天很平静。", true)))
    private val target = LockedExplanationTarget("t1", LearningTargetType.WORD, "calm", " 平静的 ", emptyList(),
        SourceEvidence(1, 7, 11, "calm", "calm", listOf("calm")), emptyList())
    private val context = LockedExplanationContext("c1", "s1", chapter.sentences.single().textEn, listOf(target))
    private val key = UUID.nameUUIDFromBytes("v1:1:word-2:".toByteArray()).toString()
    private val command = UUID.nameUUIDFromBytes("wire-owner:c1:s1:$key".toByteArray()).toString()

    @Test fun completedEnvelopePreservesOnlyTheOriginalResultIncludingRawLockedMeaning() {
        val expected = expression()
        val result = requireNotNull(codec.existingDeliveryResult(envelope(expected).toString(), command, "EXPRESSION"))
        assertEquals(expected.toString(), JSONObject(result).toString())
        assertEquals(" 平静的 ", requireNotNull(codec.expressionPreservingLockedMeaning(result)).contextualMeaningZh)
        assertFalse(JSONObject(result).has("provenance"))
        val sentence = JSONObject().put("kind", "SENTENCE").put("explanationZh", "已有句子讲解")
        assertEquals("已有句子讲解", codec.sentence(requireNotNull(codec.existingDeliveryResult(envelope(sentence).toString(), command, "SENTENCE"))))
    }

    @Test fun wrongEnvelopeIdentityKindProvenanceAndCoercedResultFieldsCannotRecover() {
        val invalid = listOf(
            envelope(expression()).put("commandId", "other-command"),
            envelope(expression()).put("commandId", 42),
            envelope(expression()).put("provenance", "NEW_GENERATION"),
            envelope(expression()).put("extra", true),
            envelope(expression()).put("status", "NO_DELIVERY"),
            envelope(expression().put("kind", "SENTENCE")),
            envelope(expression().put("expressionId", 42)),
            envelope(expression().put("explanationZh", 42)),
            envelope(expression().put("phonetic", 42)),
            envelope(expression().put("contextualMeaningZh", 42)),
            envelope(expression().put("usageZh", " ")),
            envelope(expression().put("naturalExample", JSONObject().put("textEn", 42).put("textZh", "说明"))),
            envelope(expression()).put("result", "not-an-object"),
        )
        invalid.forEach { body ->
            assertNull("Malformed delivery must fail closed: ${body.keys().asSequence().toList()}",
                runCatching { codec.existingDeliveryResult(body.toString(), command, "EXPRESSION") }.getOrNull())
        }
    }

    @Test fun nonCompletedAndLegacyResponsesAreNotPromotedToNewLockedOutput() {
        for (status in listOf("NO_DELIVERY", "NO_DELIVERY_WITH_ATTEMPT", "LEGACY_UNAVAILABLE", "PROCESSING", "FAILED", "EXPIRED",
            "INVALID_DELIVERY", "BILLING_UNVERIFIED", "PENDING_SETTLEMENT", "RELEASED", "RECONCILIATION_REQUIRED", "INVALID_RESULT")) {
            assertNull(codec.existingDeliveryResult(JSONObject().put("status", status).toString(), command, "EXPRESSION"))
        }
        val old = expression().apply { remove("contextualMeaningZh"); remove("usageZh"); remove("expressionHintZh") }
        val recovered = requireNotNull(codec.existingDeliveryResult(envelope(old).toString(), command, "EXPRESSION"))
        assertNull(requireNotNull(codec.expressionPreservingLockedMeaning(recovered)).contextualMeaningZh)
        assertFalse(JSONObject(recovered).has("contextualMeaningZh"))
    }

    @Test fun actualJsonConflictRecoveryUsesExactOriginalCommandAndCacheReadbackAcrossNewProvider() = runBlocking {
        val requests = mutableListOf<HttpRequest>()
        var stored: String? = null
        var reads = 0
        var writes = 0
        val cache = object : ExplanationCache {
            override fun read(accountId: String, chapterId: String, sentenceId: String, key: String): String? {
                assertEquals("wire-owner", accountId); assertEquals("c1", chapterId); assertEquals("s1", sentenceId)
                assertEquals(this@ExistingExplanationDeliveryWireTest.key, key)
                reads++; return stored
            }
            override fun write(accountId: String, chapterId: String, sentenceId: String, key: String, body: String) { writes++; stored = body }
        }
        fun provider() = HttpExplanationReadingProvider(HttpTransport { request ->
            requests += request
            if (request.method == "POST") {
                assertEquals(command, JSONObject(request.body).getString("commandId"))
                HttpResponse(409, "conflict")
            } else {
                assertEquals("/api/v1/learning/explanations/deliveries/$command?kind=EXPRESSION", request.path)
                HttpResponse(200, envelope(expression()).toString())
            }
        }, SessionRuntime().apply { update("isolated-wire-token", "wire-owner") }, chapter, "s1", "entry", cache = cache, lockedContext = { context })
        val first = provider().explainExpression("s1", "word-2") as ExplanationContentResult.ExpressionReady
        assertEquals(" 平静的 ", first.explanation.contextualMeaningZh)
        assertEquals(2, reads); assertEquals(1, writes)
        assertEquals(first, provider().explainExpression("s1", "word-2"))
        assertEquals(listOf("POST", "GET"), requests.map { it.method })
        assertEquals(1, writes)
    }

    private fun envelope(result: JSONObject) = JSONObject().put("status", "COMPLETED").put("commandId", command)
        .put("provenance", "EXISTING_DELIVERY").put("result", result)
    private fun expression() = JSONObject().put("kind", "EXPRESSION").put("expressionId", "word-2")
        .put("explanationZh", "完整解释").put("phonetic", JSONObject.NULL).put("naturalExample", JSONObject.NULL)
        .put("contextualMeaningZh", " 平静的 ").put("usageZh", "用于描述感受。").put("expressionHintZh", JSONObject.NULL)
}
