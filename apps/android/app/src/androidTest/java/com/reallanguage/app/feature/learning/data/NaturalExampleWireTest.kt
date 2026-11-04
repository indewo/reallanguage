package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.feature.learning.domain.NaturalExpressionExample
import org.junit.Test
import org.junit.Assert.*

class NaturalExampleWireTest {
    private val codec = JsonExplanationReadingWireCodec()
    @Test fun readsDistinctBilingualExampleAndOldMissingValueStaysMissing() {
        val legacy = """{"kind":"EXPRESSION","expressionId":"x","explanationZh":"平静的","phonetic":null}"""
        assertNull(codec.expression(legacy)?.naturalExample)
        val body = org.json.JSONObject(legacy).put("naturalExample", org.json.JSONObject()
            .put("textEn", "She felt calm.").put("textZh", "她感到平静。"))
        assertEquals(NaturalExpressionExample("She felt calm.", "她感到平静。"), codec.expression(body.toString())?.naturalExample)
    }
    @Test fun rejectsPartialAndBlankExampleInsteadOfTreatingItAsSuccessful() {
        listOf("""{"textEn":"She felt calm."}""", """{"textEn":" ","textZh":"她感到平静。"}""").forEach { example ->
            val body = """{"kind":"EXPRESSION","expressionId":"x","explanationZh":"平静的","phonetic":null,"naturalExample":$example}"""
            assertTrue(runCatching { codec.expression(body) }.isFailure)
        }
    }
}
