package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.testing.withExplicitSource
import com.reallanguage.app.testing.explicitSourceFixRepository
import com.reallanguage.app.feature.formation.domain.FixedSourceUnit

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.*
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.UrlConnectionSessionTransport
import com.reallanguage.app.feature.formation.data.AndroidFixedChapterRepository
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.learning.domain.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** One paid model call, opt-in only; isolated account and neutral fixture input. */
class LiveExplanationRecoveryTest {
    @Test fun realDeliveredExplanationSurvivesRepositoryRecreationWithoutAnotherHttpCall() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveFormation") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "live-formation-session.json")
        val session = try { JSONObject(file.readText()) } finally { file.delete() }
        val runtime = SessionRuntime().apply { update(session.getString("accessToken"), session.getString("accountId")) }
        val sentence = EnglishSentenceDraft("u1", "我今天向同事解释了我的想法。", true, listOf(EnglishCandidate("e1", "I explained my idea to a colleague today.")))
        val request = ContentFixRequest(UUID.randomUUID().toString(), UUID.randomUUID().toString(), listOf(EnglishChapterDraft("c1", listOf(sentence))), mapOf("u1" to "e1"), emptyList(), emptySet()).withExplicitSource("我今天向同事解释了我的想法。", listOf(FixedSourceUnit("u1", 0, "我今天向同事解释了我的想法。")))
        val fixed = explicitSourceFixRepository(context, runtime).fix(request) as ContentFixResult.Fixed
        val chapter = (AndroidFixedChapterRepository(context, runtime).load(fixed.firstChapterId) as FixedChapterLoadResult.Loaded).chapter
        var calls = 0
        val delegate = UrlConnectionSessionTransport("http://10.0.2.2:3001", true)
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                calls++
                val response = delegate.execute(request)
                assertEquals("Real explanation endpoint", 200, response.statusCode)
                return response
            }
        }
        fun provider() = HttpExplanationReadingProvider(transport, runtime, chapter, chapter.sentences.single().sentenceId,
            UUID.randomUUID().toString(), cache = AndroidExplanationCache(context))
        try {
            val details = InstrumentationRegistry.getArguments().getString("expressionDetails") == "true"
            if (InstrumentationRegistry.getArguments().getString("expressionExample") == "true" || details) {
                val sentenceId = chapter.sentences.single().sentenceId
                val first = provider().explainExpression(sentenceId, "word-1")
                assertTrue("Real expression explanation must be delivered", first is ExplanationContentResult.ExpressionReady)
                val example = (first as ExplanationContentResult.ExpressionReady).explanation.naturalExample
                assertEquals("Only the requested fixed-sentence expression may be explained", "word-1", first.explanation.expressionId)
                assertNotNull("The real provider must deliver a bilingual example", example)
                assertTrue(requireNotNull(example).textEn.isNotBlank() && example.textZh.isNotBlank())
                if (details) {
                    assertTrue("Real current-context meaning must be supplied", !first.explanation.contextualMeaningZh.isNullOrBlank())
                    assertTrue("Real usage must be supplied separately", !first.explanation.usageZh.isNullOrBlank())
                    assertTrue("A supplied expression hint cannot be blank", first.explanation.expressionHintZh?.isNotBlank() != false)
                }
                assertEquals(first, provider().explainExpression(sentenceId, "word-1"))
                assertEquals("Cached expression reconstruction must not make another HTTP request", 1, calls)
                println("REAL_EXPRESSION_EXAMPLE " + JSONObject().put("expression", "explained")
                    .put("meaningZh", first.explanation.explanationZh)
                    .put("contextualMeaningZh", first.explanation.contextualMeaningZh ?: JSONObject.NULL)
                    .put("usageZh", first.explanation.usageZh ?: JSONObject.NULL)
                    .put("expressionHintZh", first.explanation.expressionHintZh ?: JSONObject.NULL)
                    .put("exampleEn", example.textEn).put("exampleZh", example.textZh)
                    .put("cacheReconstructionEqual", true).put("httpCalls", calls).toString())
            } else {
                val first = provider().prepare()
                assertTrue("Real explanation must be delivered", first is ExplanationPreparationResult.Ready)
                assertTrue((first as ExplanationPreparationResult.Ready).baseExplanationZh.isNotBlank())
                assertEquals(first, provider().prepare())
            }
            assertEquals("Recovery made another provider request", 1, calls)
        } finally {
            FormationDatabase(context).use { db ->
                listOf("learning_explanation_cache", "fix_commands", "fixed_sentences", "chapters", "formation_flows").forEach {
                    db.writableDatabase.delete(it, "account_id=?", arrayOf(runtime.accountId))
                }
            }
        }
    }
}
