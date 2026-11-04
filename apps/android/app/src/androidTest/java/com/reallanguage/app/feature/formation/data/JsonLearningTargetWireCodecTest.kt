package com.reallanguage.app.feature.formation.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import com.reallanguage.app.feature.formation.domain.EnglishCandidate
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishSentenceDraft
import org.json.JSONObject
import org.json.JSONArray

@RunWith(AndroidJUnit4::class)
class JsonLearningTargetWireCodecTest {
    @Test fun freshPatternJsonRejectsEmptyBlankAndCoercedStructureParts() {
        val invalid = listOf(JSONArray(), JSONArray().put(""), JSONArray().put(" "),
            JSONArray().put("drink").put("\n\t"), JSONArray().put("\u00a0\u3000"),
            JSONArray().put(42), JSONArray().put(JSONObject.NULL), JSONArray().put(JSONObject().put("text", "drink")))
        for (parts in invalid) {
            assertThrows("Do not accept or coerce an invalid structure $parts", Exception::class.java) {
                JsonLearningTargetWireCodec().payload(patternPayload(parts).toString())
            }
        }
    }

    @Test fun structureJsonKeepsMeaningfulOrderAndWhitespaceWithoutFabricatingPartsForWords() {
        val parts = listOf(" drink ", "名词")
        val payload = JsonLearningTargetWireCodec().payload(patternPayload(JSONArray(parts)).toString())
        assertEquals(parts, payload.targets.single().structureParts)
        val word = patternPayload(JSONArray())
        word.getJSONArray("targets").getJSONObject(0).put("type", "WORD").put("display", "tea")
        assertEquals(emptyList<String>(), JsonLearningTargetWireCodec().payload(word.toString()).targets.single().structureParts)
    }

    @Test fun requestedExpressionIncludesVisibleTextNotOnlyOpaqueId() {
        val chapters = listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft(
            "u1", "我很开心。", true, listOf(EnglishCandidate("e1", "I was happy.")),
        ))))
        val json = JSONObject(JsonLearningTargetWireCodec().request(
            chapters, mapOf("u1" to "e1"), "command", "u1" to "q1", "happy",
        ))
        assertEquals("happy", json.getJSONObject("requestedExpression").getString("display"))
    }
    @Test fun readsAuthoritativeLearningScopesWithTargets() {
        val payload = JsonLearningTargetWireCodec().payload(
            """{"learningScopes":["CET_4","BUSINESS_ENGLISH"],"targets":[{"targetId":"t","unitId":"u","display":"run","explanationZh":"经营","type":"WORD","structureParts":[],"relatedVocabulary":[],"selectedByDefault":true}],"queryableExpressions":[]}""",
        )

        assertEquals(setOf("CET_4", "BUSINESS_ENGLISH"), payload.learningScopes)
        assertEquals("run", payload.targets.single().display)
    }

    private fun patternPayload(parts: JSONArray) = JSONObject()
        .put("learningScopes", JSONArray().put("CET_4"))
        .put("queryableExpressions", JSONArray())
        .put("targets", JSONArray().put(JSONObject().put("targetId", "t1").put("unitId", "u1")
            .put("display", "drink + noun").put("explanationZh", "喝某物").put("type", "PATTERN")
            .put("structureParts", parts).put("relatedVocabulary", JSONArray()).put("selectedByDefault", true)))
}
