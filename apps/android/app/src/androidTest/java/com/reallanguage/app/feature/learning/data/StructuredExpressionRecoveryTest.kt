package com.reallanguage.app.feature.learning.data

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.reallanguage.app.core.network.*
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.*
import com.reallanguage.app.feature.learning.presentation.*
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real JSON codec/cache/SQLite/VM recovery, isolated transport and account; never calls AI. */
class StructuredExpressionRecoveryTest {
    private val chapter = FixedLearningChapter("structured-c1", 1,
        listOf(FixedLearningSentence("s1", "I felt calm.", "我很平静。", true)))
    private val expression = SelectableExpression("word-1", "felt", 2, 6)
    private val legacy = """{"kind":"EXPRESSION","expressionId":"word-1","explanationZh":"感到；feel 的过去式","phonetic":"/felt/","naturalExample":{"textEn":"She felt happy.","textZh":"她感到开心。"}}"""
    private fun structured(hint: Any = " 注意过去式。 ") = JSONObject(legacy)
        .put("contextualMeaningZh", " 此处指感到平静。 ").put("usageZh", " felt 后接形容词。 ")
        .put("expressionHintZh", hint)

    @Test fun realJsonCachesAndRestoresTheSameFieldsThroughSqliteVmAndCompletedStep() = runBlocking {
        withIsolatedChapter { context, runtime ->
            for (hint in listOf<Any>(" 注意过去式。 ", JSONObject.NULL)) {
                val cache = AndroidExplanationCache(context)
                var requests = 0
                val body = structured(hint).toString()
                val transport = object : HttpTransport {
                    override suspend fun execute(request: HttpRequest): HttpResponse { requests++; return HttpResponse(200, body) }
                }
                fun provider() = HttpExplanationReadingProvider(transport, runtime, chapter, "s1", UUID.randomUUID().toString(), cache = cache)
                // Each variant starts with an empty cache belonging only to this fixture account.
                val delivered = provider().explainExpression("s1", expression.expressionId) as ExplanationContentResult.ExpressionReady
                val expected = ExpressionExplanation("word-1", "感到；feel 的过去式", "/felt/",
                    NaturalExpressionExample("She felt happy.", "她感到开心。"), "此处指感到平静。", "felt 后接形容词。",
                    if (hint === JSONObject.NULL) null else "注意过去式。")
                assertEquals(expected, delivered.explanation)
                assertEquals(delivered, provider().explainExpression("s1", expression.expressionId))
                assertEquals(1, requests)
                fun progress() = AndroidLearningProgressStore(context, runtime, chapter.chapterId, "s1", "LR-02")
                val models = ViewModelStore()
                try {
                    val model = withContext(Dispatchers.Main) {
                        ExplanationReadingViewModel(chapter, "s1", "基础解释", listOf(expression), provider(), progress()).also { models.put("first", it) }
                    }
                    awaitRestored(model)
                    withContext(Dispatchers.Main) { model.onAction(ExplanationReadingAction.ExpressionRequested(expression.expressionId)) }
                    awaitRestored(model)
                    assertEquals(expected, model.uiState.value.selectedExpression)
                    val saved = requireNotNull(progress().read())
                    assertEquals(expected.contextualMeaningZh, saved["expressionContextualMeaningZh"])
                    assertEquals(expected.usageZh, saved["expressionUsageZh"])
                    assertEquals(expected.expressionHintZh.orEmpty(), saved["expressionHintZh"])
                    val forbidden = object : ExplanationReadingProvider {
                        override suspend fun incrementalHint(sentenceId: String, round: Int, baseExplanationZh: String): ExplanationContentResult = error("No restore AI")
                        override suspend fun explainExpression(sentenceId: String, expressionId: String): ExplanationContentResult = error("No restore AI")
                    }
                    val reopened = withContext(Dispatchers.Main) {
                        ExplanationReadingViewModel(chapter, "s1", "基础解释", listOf(expression), forbidden, progress()).also { models.put("reopened", it) }
                    }
                    awaitRestored(reopened)
                    val snapshot = CompletedStepSnapshot("LR-02", "基础讲解", chapter.sentences.single().textEn, "我很平静。", "基础解释", expressions = listOf(expression))
                    assertEquals(expected, reopened.uiState.value.selectedExpression)
                    assertEquals(expected, snapshot.withSavedExpression(progress().read()).reviewedExpression?.explanation)
                    assertEquals(saved, progress().read())
                    assertEquals(1, requests)
                } finally { withContext(Dispatchers.Main) { models.clear() } }
                // Clean only this fixture account between its two explicit delivered variants.
                FormationDatabase(context).use { helper ->
                    listOf("learning_explanation_cache", "learning_module_progress", "learning_checkpoints").forEach {
                        helper.writableDatabase.delete(it, "account_id=?", arrayOf(runtime.accountId))
                    }
                }
            }
        }
    }

    @Test fun legacyMissingAndExplicitNullGroupsStayReadableWithoutInventingNewFields() {
        val codec = JsonExplanationReadingWireCodec()
        val variants = listOf(JSONObject(legacy), JSONObject(legacy).put("contextualMeaningZh", JSONObject.NULL),
            JSONObject(legacy).put("contextualMeaningZh", JSONObject.NULL).put("usageZh", JSONObject.NULL).put("expressionHintZh", JSONObject.NULL))
        variants.forEach {
            val result = requireNotNull(codec.expression(it.toString()))
            assertEquals("感到；feel 的过去式", result.explanationZh)
            assertNull(result.contextualMeaningZh); assertNull(result.usageZh); assertNull(result.expressionHintZh)
        }
    }

    @Test fun legacyCachedDeliveryIsNotRegeneratedToFillMissingFields() = runBlocking {
        withIsolatedChapter { context, runtime ->
            var requests = 0
            val transport = object : HttpTransport {
                override suspend fun execute(request: HttpRequest): HttpResponse { requests++; return HttpResponse(200, legacy) }
            }
            fun provider() = HttpExplanationReadingProvider(transport, runtime, chapter, "s1", UUID.randomUUID().toString(), cache = AndroidExplanationCache(context))
            val original = provider().explainExpression("s1", "word-1") as ExplanationContentResult.ExpressionReady
            val restored = provider().explainExpression("s1", "word-1")
            assertEquals(original, restored)
            assertNull(original.explanation.contextualMeaningZh)
            assertNull(original.explanation.usageZh)
            assertNull(original.explanation.expressionHintZh)
            assertEquals("Do not charge again to enrich an old cache", 1, requests)
        }
    }

    @Test fun partialWrongTypedAndBlankStructuredGroupsAreRejected() {
        val codec = JsonExplanationReadingWireCodec()
        val invalid = buildList {
            listOf("contextualMeaningZh", "usageZh", "expressionHintZh").forEach { missing -> add(structured().apply { remove(missing) }) }
            listOf("contextualMeaningZh", "usageZh", "expressionHintZh").forEach { key ->
                listOf<Any>(" ", 42, true, JSONObject()).forEach { value -> add(structured().put(key, value)) }
            }
            add(structured().put("contextualMeaningZh", JSONObject.NULL))
            add(structured().put("usageZh", JSONObject.NULL))
            add(JSONObject(legacy).put("expressionHintZh", "只有提示"))
        }
        invalid.forEach { assertTrue("Malformed group must fail: $it", runCatching { codec.expression(it.toString()) }.isFailure) }
    }

    private suspend fun awaitRestored(model: ExplanationReadingViewModel) {
        withTimeout(5_000) { while (model.uiState.value.loading) delay(10) }
        assertFalse(model.uiState.value.progressFailed)
        assertFalse(model.uiState.value.loadingFailed)
    }

    private suspend fun withIsolatedChapter(block: suspend (Context, SessionRuntime) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val account = "structured-expression-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("isolated-token", account) }
        FormationDatabase(context).use { helper ->
            helper.writableDatabase.execSQL("insert into chapters values (?, 'structured-flow', ?, 0, 'NOT_STARTED')", arrayOf(account, chapter.chapterId))
            helper.writableDatabase.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?, ?, 's1', 0, '我很平静。', 'I felt calm.', 'FOCUS')", arrayOf(account, chapter.chapterId))
        }
        try { block(context, runtime) } finally {
            FormationDatabase(context).use { helper ->
                listOf("learning_explanation_cache", "learning_module_progress", "learning_checkpoints", "fixed_sentences", "chapters").forEach {
                    helper.writableDatabase.delete(it, "account_id=?", arrayOf(account))
                }
            }
        }
    }
}
