package com.reallanguage.app.feature.learning.data

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.*
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.UrlConnectionSessionTransport
import com.reallanguage.app.feature.formation.data.*
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.learning.domain.*
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** One real AI call with a declared lost-response injection; neutral fixed input is not an end-to-end diary claim. */
class LiveLockedMeaningRecoveryTest {
    @Test fun realExplanationRetainsCancelledWordMeaningAndReopensWithoutAnotherRequest() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveLockedMeaning") == "true")
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sessionFile = File(base.filesDir, "live-formation-session.json")
        val session = try { JSONObject(sessionFile.readText()) } finally { check(sessionFile.delete()) }
        val runtime = SessionRuntime().apply { update(session.getString("accessToken"), session.getString("accountId")) }
        val account = requireNotNull(runtime.accountId)
        val context = IsolatedLearningDatabaseContext(base, "live-locked-${UUID.randomUUID()}")
        val textEn = "I used to drink tea."
        val textZh = "我以前常喝茶。"
        val meaning = "茶（饮品）"
        val wordEvidence = SourceEvidence(1, 16, 19, "tea", "tea", listOf("tea"))
        val target = LearningTarget("pattern", "focus", "used to + verb", "表示过去的习惯", LearningTargetType.PATTERN,
            structureParts = listOf("used to", "verb"), relatedVocabulary = listOf("tea"),
            sourceEvidence = SourceEvidence(1, 2, 19, "used to drink tea", "used to + verb", listOf("used to drink tea")),
            relatedVocabularyItems = listOf(RelatedVocabularyItem("tea-gloss", "focus", "tea", meaning, LearningTargetType.WORD, wordEvidence)),
            relatedVocabularyItemRefs = listOf("tea-gloss"))
        var httpCalls = 0
        var generationRequests = 0
        var recoveryRequests = 0
        var originalCommand: String? = null
        val delegate = UrlConnectionSessionTransport("http://10.0.2.2:3001", true)
        val transport = HttpTransport { request ->
            check(httpCalls < 2) { "One generation plus one read-only recovery request budget" }
            httpCalls++
            if (request.method == "POST") {
                check(request.path == "/api/v1/learning/explanations" && generationRequests == 0)
                generationRequests++
                val input = JSONObject(requireNotNull(request.body))
                originalCommand = input.getString("commandId")
                assertEquals(16, input.getJSONObject("requestedExpression").getInt("startUtf16"))
                assertEquals(1, input.getJSONArray("lockedTargets").length())
                assertEquals(meaning, input.getJSONArray("lockedTargets").getJSONObject(0)
                    .getJSONArray("relatedVocabulary").getJSONObject(0).getString("senseZh"))
                val delivered = delegate.execute(request)
                check(delivered.statusCode == 200) { "Real initial generation must succeed before injecting response loss" }
                // Only the transport failure is injected. Both server responses and the AI are real.
                throw IOException("Isolated response-loss injection after actual server delivery")
            }
            check(request.method == "GET" && recoveryRequests == 0 && generationRequests == 1)
            recoveryRequests++
            check(request.path == "/api/v1/learning/explanations/deliveries/${requireNotNull(originalCommand)}?kind=EXPRESSION")
            val recovered = delegate.execute(request)
            check(recovered.statusCode == 200 && JSONObject(recovered.body).getString("status") == "COMPLETED")
            recovered
        }
        try {
            FormationDatabase(context).use { helper ->
                val db = helper.writableDatabase
                db.execSQL("insert into chapters values(?,'neutral-fixture','chapter',0,'NOT_STARTED')", arrayOf(account))
                db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,'chapter','focus',0,?,?,'FOCUS')",
                    arrayOf(account, textZh, textEn))
                db.insertOrThrow("target_selections", null, ContentValues().apply {
                    put("account_id", account); put("chapter_id", "chapter"); put("target_id", target.targetId); put("unit_id", "focus")
                    put("type", "PATTERN"); put("display", target.display); put("explanation_zh", target.explanationZh)
                    put("structure_parts", target.structureParts.joinToString("\u001f")); put("related_vocabulary", "tea")
                    put("source_evidence", target.sourceEvidence!!.toJson().toString())
                    put("related_vocabulary_snapshot", encodeRelatedVocabularySnapshot(target, textEn))
                })
            }
            val chapter = (AndroidFixedChapterRepository(context, runtime).load("chapter") as FixedChapterLoadResult.Loaded).chapter
            fun provider() = HttpExplanationReadingProvider(transport, runtime, chapter, "focus", UUID.randomUUID().toString(),
                cache = AndroidExplanationCache(context), lockedContext = { AndroidLockedExplanationContextReader(context, runtime).read("chapter", "focus") })
            val first = provider().explainExpression("focus", "word-4")
            assertTrue("Real locked explanation must be delivered", first is ExplanationContentResult.ExpressionReady)
            val value = (first as ExplanationContentResult.ExpressionReady).explanation
            assertEquals(meaning, value.contextualMeaningZh)
            assertFalse(value.usageZh.isNullOrBlank())
            assertNotNull(value.naturalExample)
            assertEquals(first, provider().explainExpression("focus", "word-4"))
            assertEquals(2, httpCalls)
            assertEquals(1, generationRequests)
            assertEquals(1, recoveryRequests)
            FormationDatabase(context).use { helper ->
                for (table in listOf("vocabulary_items", "vocabulary_occurrences", "target_vocabulary_details", "learning_obligations")) {
                    helper.readableDatabase.rawQuery("select count(*) from $table where account_id=?", arrayOf(account)).use {
                        check(it.moveToFirst()); assertEquals(0, it.getInt(0))
                    }
                }
            }
            fun safe(value: String?): Any {
                if (value == null) return JSONObject.NULL
                require(value.length <= 1600 && !Regex("sk-|bearer\\s|access.?token|password|api.?key|https?://|@", RegexOption.IGNORE_CASE).containsMatchIn(value))
                return value
            }
            val result = JSONObject().put("sampleKind", "ISOLATED_LOCKED_MEANING").put("sourceTextEn", textEn)
                .put("contextualMeaningZh", safe(value.contextualMeaningZh)).put("usageZh", safe(value.usageZh))
                .put("expressionHintZh", safe(value.expressionHintZh)).put("exampleEn", safe(value.naturalExample?.textEn))
                .put("exampleZh", safe(value.naturalExample?.textZh)).put("httpRequests", httpCalls)
                .put("generationRequests", generationRequests).put("readOnlyRecoveryRequests", recoveryRequests)
                .put("responseLossInjectedAfterRealDelivery", true)
                .put("cacheRecreationEqual", true).put("newVocabularyTasks", 0).put("wholeJourneyAccepted", false)
            val output = File(base.filesDir, "live-locked-meaning-result.json")
            check(output.createNewFile()) { "Previous live evidence must be retained" }
            output.writeText(result.toString())
        } finally {
            check(context.deleteDatabase("real-language-learning.db"))
            runtime.clear()
        }
    }
}
