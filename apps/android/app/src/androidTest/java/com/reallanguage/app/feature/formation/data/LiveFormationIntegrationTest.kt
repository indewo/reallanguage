package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.testing.withExplicitSource
import com.reallanguage.app.testing.explicitSourceFixRepository
import com.reallanguage.app.feature.formation.domain.FixedSourceUnit

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.UrlConnectionSessionTransport
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.learning.domain.FixedChapterLoadResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in live provider test. Never uses the user's account or production database. */
class LiveFormationIntegrationTest {
    @Test fun realTargetSourceEvidenceSurvivesClientRecreationWithoutAnotherProviderAttempt() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTargetSourceEvidence") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessionFile = File(context.filesDir, "live-formation-session.json")
        val session = JSONObject(sessionFile.readText())
        sessionFile.delete()
        val runtime = SessionRuntime().apply { update(session.getString("accessToken"), session.getString("accountId")) }
        val sentence = EnglishSentenceDraft("u1", "昨天我鼓起勇气请求反馈。", true,
            listOf(EnglishCandidate("e1", "Yesterday I worked up the courage to ask for feedback.")))
        val chapters = listOf(EnglishChapterDraft("c1", listOf(sentence)))
        val selections = mapOf("u1" to "e1")
        val delegate = UrlConnectionSessionTransport("http://10.0.2.2:3001", true)
        val transport = HttpTransport { request ->
            val response = delegate.execute(request)
            File(context.filesDir, "live-target-source-http.json").writeText(JSONObject()
                .put("statusCode", response.statusCode).put("body", response.body).toString())
            response
        }
        val commandId = UUID.randomUUID().toString()
        fun create() = HttpLearningTargetRepository(transport, runtime, chapters, selections, commandId)
        val generated = create().extract()
        assertTrue("Real provider failed to supply valid source evidence", generated is LearningTargetExtractionResult.Extracted)
        val targets = (generated as LearningTargetExtractionResult.Extracted).targets
        assertTrue("Representative conventional expression was omitted", targets.any { target ->
            target.type != LearningTargetType.WORD && target.sourceEvidence?.surfaceForm?.contains("worked up the courage") == true
        })
        targets.forEach { target ->
            val evidence = checkNotNull(target.sourceEvidence)
            assertTrue(evidence.isValidFor(sentence.candidates.single().textEn))
            assertEquals(listOf(evidence.surfaceForm), evidence.allowedForms)
        }
        assertEquals("Recreated client changed an already delivered target", generated, create().extract())
        File(context.filesDir, "live-target-source-evidence-result.json").writeText(JSONObject()
            .put("realProvider", true).put("clientRecreatedEqual", true)
            .put("sourceTextEn", sentence.candidates.single().textEn)
            .put("targets", org.json.JSONArray(targets.map { target ->
                val evidence = checkNotNull(target.sourceEvidence)
                JSONObject().put("type", target.type.name).put("display", target.display)
                    .put("explanationZh", target.explanationZh).put("surfaceForm", evidence.surfaceForm)
                    .put("normalizedForm", evidence.normalizedForm).put("startUtf16", evidence.startUtf16).put("endUtf16", evidence.endUtf16)
            })).toString())
    }
    @Test fun exactPointedWordIsDeliveredAndRetryReusesTheSameTarget() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveWordIdentity") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessionFile = File(context.filesDir, "live-formation-session.json")
        val session = JSONObject(sessionFile.readText())
        sessionFile.delete()
        val runtime = SessionRuntime().apply { update(session.getString("accessToken"), session.getString("accountId")) }
        val transport = UrlConnectionSessionTransport("http://10.0.2.2:3001", true)
        val sentence = EnglishSentenceDraft("u1", "我向同事解释了这个计划。", true,
            listOf(EnglishCandidate("e1", "I explained the plan to my colleague.")))
        val chapters = listOf(EnglishChapterDraft("c1", listOf(sentence)))
        val selections = mapOf("u1" to "e1")
        val commandId = UUID.randomUUID().toString()
        val repository = HttpLearningTargetRepository(transport, runtime, chapters, selections, commandId)
        val word = sentence.wordExpressions("e1").single { it.display == "explained" }
        assertEquals("word:2", word.expressionId)
        val generated = repository.generate("u1", word.expressionId)
        assertTrue("Real pointed word did not produce a valid target", generated is LearningTargetGenerationResult.Added)
        val target = (generated as LearningTargetGenerationResult.Added).target
        assertEquals("explained", target.display.lowercase())
        assertEquals("u1", target.unitId)
        assertTrue("Word explanation is empty", target.explanationZh.isNotBlank())
        val reentered = HttpLearningTargetRepository(transport, runtime, chapters, selections, commandId)
        assertEquals("Same command must reuse delivered target", generated, reentered.generate("u1", word.expressionId))
    }

    @Test fun realTargetAndAddedWordReachAtomicLocalFix() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveFormation") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessionFile = File(context.filesDir, "live-formation-session.json")
        val session = JSONObject(sessionFile.readText())
        sessionFile.delete()
        val runtime = SessionRuntime().apply { update(session.getString("accessToken"), session.getString("accountId")) }
        val delegate = UrlConnectionSessionTransport("http://10.0.2.2:3001", true)
        var lastFailure = "no request sent"
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                try {
                    val response = delegate.execute(request)
                    lastFailure = "HTTP ${response.statusCode} from ${request.path}"
                    assertEquals("Live endpoint ${request.path} returned ${response.statusCode}", 200, response.statusCode)
                    return response
                } catch (error: Exception) {
                    lastFailure = error.javaClass.simpleName
                    throw AssertionError("Live transport failed: ${error.javaClass.simpleName}")
                }
            }
        }
        val sentence = EnglishSentenceDraft("u1", "今天我鼓起勇气向同事解释我的想法。", true,
            listOf(EnglishCandidate("e1", "Today I worked up the courage to explain my idea to a colleague.")))
        val chapters = listOf(EnglishChapterDraft("c1", listOf(sentence)))
        val selections = mapOf("u1" to "e1")
        val targets = HttpLearningTargetRepository(transport, runtime, chapters, selections, UUID.randomUUID().toString())
        val extracted = targets.extract()
        assertTrue("Real target extraction did not return content: $lastFailure", extracted is LearningTargetExtractionResult.Extracted)
        val word = sentence.wordExpressions("e1").single { it.display == "colleague" }
        val added = targets.generate(word.unitId, word.expressionId)
        assertTrue("Real word addition failed", added is LearningTargetGenerationResult.Added)
        val target = (added as LearningTargetGenerationResult.Added).target
        assertEquals("colleague", target.display.lowercase())
        assertEquals("u1", target.unitId)
        val request = ContentFixRequest(UUID.randomUUID().toString(), UUID.randomUUID().toString(), chapters, selections, listOf(target), setOf(target.targetId)).withExplicitSource("今天我鼓起勇气向同事解释我的想法。", listOf(FixedSourceUnit("u1", 0, "今天我鼓起勇气向同事解释我的想法。")))
        val repository = explicitSourceFixRepository(context, runtime, knowledgeExamples = HttpKnowledgeExampleRepository(transport, runtime))
        val fixed = repository.fix(request)
        assertTrue("Real examples could not be fixed to SQLite", fixed is ContentFixResult.Fixed)
        assertTrue("Fixed chapter cannot be loaded for learning", AndroidFixedChapterRepository(context, runtime).load((fixed as ContentFixResult.Fixed).firstChapterId) is FixedChapterLoadResult.Loaded)
        assertEquals("Successful retry changed the fixed chapter", fixed, repository.fix(request))
        val database = FormationDatabase(context)
        try {
            database.readableDatabase.rawQuery("select examples_json from target_vocabulary_details where account_id=?", arrayOf(runtime.accountId)).use {
                assertTrue("Added word details were lost", it.moveToFirst())
                assertEquals(3, org.json.JSONArray(it.getString(0)).length())
            }
        } finally { database.close() }
    }
}
