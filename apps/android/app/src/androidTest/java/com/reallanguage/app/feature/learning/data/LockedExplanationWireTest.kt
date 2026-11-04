package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.core.network.*
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import com.reallanguage.app.feature.formation.domain.SourceEvidence
import com.reallanguage.app.feature.learning.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual JSON with private in-memory cache only, no user DB/provider/credentials. */
class LockedExplanationWireTest {
    private val codec = JsonExplanationReadingWireCodec()
    private val chapter = FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I felt calm today.", "我今天很平静。", true)))
    private val evidence = SourceEvidence(1, 7, 11, "calm", "calm", listOf("calm"))
    private val target = LockedExplanationTarget("t1", LearningTargetType.WORD, "calm", " 平静的 ", emptyList(), evidence, emptyList())

    @Test fun lockedRequestIncludesActualTargetEvidenceAndCompleteExpressionOffsetsWithoutChangingCommand() {
        val body = JSONObject(codec.requestWithLockedTargets(chapter, "s1", 1, null,
            SelectableExpression("word-2", "calm", 7, 11), "existing-paid-command", listOf(target)))
        assertEquals("existing-paid-command", body.getString("commandId"))
        val locked = body.getJSONArray("lockedTargets").getJSONObject(0)
        assertEquals(setOf("targetId", "type", "display", "explanationZh", "structureParts", "sourceEvidence", "relatedVocabulary"),
            locked.keys().asSequence().toSet())
        assertEquals(" 平静的 ", locked.getString("explanationZh"))
        assertEquals("calm", locked.getJSONObject("sourceEvidence").getString("surfaceForm"))
        assertEquals(0, locked.getJSONArray("relatedVocabulary").length())
        val expression = body.getJSONObject("requestedExpression")
        assertEquals(7, expression.getInt("startUtf16")); assertEquals(11, expression.getInt("endUtf16"))
        val legacy = JSONObject(codec.request(chapter, "s1", 1, null, SelectableExpression("word-2", "calm", 7, 11), "legacy-command"))
        assertFalse(legacy.has("lockedTargets"))
        assertFalse(legacy.getJSONObject("requestedExpression").has("startUtf16"))
    }

    @Test fun lockedMeaningRetainsItsRawValueWhileLegacyDecoderStillNormalizesAsBefore() {
        val body = expressionJson(" 平静的 ").toString()
        assertEquals(" 平静的 ", requireNotNull(codec.expressionPreservingLockedMeaning(body)).contextualMeaningZh)
        assertEquals("平静的", requireNotNull(codec.expression(body)).contextualMeaningZh)
    }

    @Test fun actualJsonCacheWithDifferentLockedMeaningIsNotReturnedDeletedOrRegenerated() = runBlocking {
        val delivered = expressionJson("平静的").toString()
        var calls = 0
        var writes = 0
        val cache = object : ExplanationCache {
            override fun read(accountId: String, chapterId: String, sentenceId: String, key: String) = delivered
            override fun write(accountId: String, chapterId: String, sentenceId: String, key: String, body: String) { writes++ }
        }
        val provider = HttpExplanationReadingProvider(HttpTransport { calls++; HttpResponse(200, delivered) },
            SessionRuntime().apply { update("isolated-token", "isolated-owner") }, chapter, "s1", "entry", cache = cache,
            lockedContext = { LockedExplanationContext("c1", "s1", chapter.sentences.single().textEn, listOf(target)) })
        assertEquals(ExplanationContentResult.Failed, provider.explainExpression("s1", "word-2"))
        assertEquals(0, calls); assertEquals(0, writes)
        assertEquals("平静的", JSONObject(delivered).getString("contextualMeaningZh"))
    }

    private fun expressionJson(meaning: String) = JSONObject().put("kind", "EXPRESSION").put("expressionId", "word-2")
        .put("explanationZh", "完整解释").put("phonetic", JSONObject.NULL).put("naturalExample", JSONObject.NULL)
        .put("contextualMeaningZh", meaning).put("usageZh", "用于描述感受。").put("expressionHintZh", JSONObject.NULL)
}
