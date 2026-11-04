package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.core.network.*
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual JSON input/output only, isolated transport and no external provider or database. */
class AcceptedVocabularyWireTest {
    private val evidence = SourceEvidence(1, 8, 11, "tea", "tea", listOf("tea"))
    private val item = RelatedVocabularyItem("local-tea", "u1", "tea", "茶", LearningTargetType.WORD, evidence)
    private val pattern = LearningTarget("p1", "u1", "drink + noun", "喝某物", LearningTargetType.PATTERN,
        structureParts = listOf("drink", "noun"), relatedVocabulary = listOf("tea"),
        relatedVocabularyItems = listOf(item), relatedVocabularyItemRefs = listOf(item.itemRef))
    private val chapters = listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", "我喝茶。", true,
        listOf(EnglishCandidate("e1", "I drink tea."))))))
    private val requests = mutableListOf<JSONObject>()

    @Test fun realRequestSendsOnlyTheClickedAcceptedMeaningAndReentryHasTheSameCommand() = runBlocking {
        suspend fun generate() = repository { response() }.generate("u1", "word:8")
        assertTrue(generate() is LearningTargetGenerationResult.Added)
        assertEquals(1, requests.size)
        val request = requests.single()
        val expression = request.getJSONObject("requestedExpression")
        val accepted = expression.getJSONObject("acceptedVocabulary")
        assertEquals(setOf("display", "senseZh", "unitType", "sourceEvidence"), accepted.keys().asSequence().toSet())
        assertEquals("tea", accepted.getString("display"))
        assertEquals("茶", accepted.getString("senseZh"))
        assertEquals("WORD", accepted.getString("unitType"))
        assertEquals(evidence, decodeSourceEvidence(accepted.get("sourceEvidence")))
        assertEquals("word:8", expression.getString("expressionId"))
        assertFalse(request.has("targets"))
        assertFalse(request.toString().contains("local-tea"))
        assertTrue(generate() is LearningTargetGenerationResult.Added)
        assertEquals(request.getString("commandId"), requests.last().getString("commandId"))
        assertEquals(request.toString(), requests.last().toString())
    }

    @Test fun rejectedOutputDoesNotReplaceAcceptedFactsOrCreateANewRetryCommand() = runBlocking {
        var wrong = true
        val repo = repository { response().apply {
            if (wrong) getJSONArray("targets").getJSONObject(0).put("sourceEvidence",
                evidence.copy(normalizedForm = "beverage").toJson())
        } }
        assertEquals(LearningTargetGenerationResult.Failed, repo.generate("u1", "word:8"))
        val before = requests.single()
        wrong = false
        assertTrue(repo.generate("u1", "word:8") is LearningTargetGenerationResult.Added)
        assertEquals(before.toString(), requests.last().toString())
        assertEquals("tea", requests.last().getJSONObject("requestedExpression").getJSONObject("acceptedVocabulary")
            .getJSONObject("sourceEvidence").getString("normalizedForm"))
    }

    private fun repository(response: () -> JSONObject) = HttpLearningTargetRepository(HttpTransport { request ->
        requests += JSONObject(requireNotNull(request.body))
        HttpResponse(200, response().toString())
    }, SessionRuntime().apply { update("isolated-token", "isolated-owner") }, chapters, mapOf("u1" to "e1"), "accepted-base",
        initialTargets = listOf(pattern))
    private fun response() = JSONObject().put("learningScopes", JSONArray()).put("queryableExpressions", JSONArray())
        .put("targets", JSONArray().put(JSONObject().put("targetId", "w1").put("unitId", "u1").put("display", "tea")
            .put("explanationZh", "茶").put("type", "WORD").put("structureParts", JSONArray()).put("relatedVocabulary", JSONArray())
            .put("relatedVocabularyItems", JSONArray()).put("relatedVocabularyItemRefs", JSONArray())
            .put("sourceEvidence", evidence.toJson())))
}
