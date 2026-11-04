package com.reallanguage.app.core.app

import android.content.ContentValues
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.*
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.formation.presentation.*
import com.reallanguage.app.feature.safety.domain.SourceType
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real application factories and SQLite; only the network boundary is isolated. */
class ProductionFormationWiringTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private val runtime = SessionRuntime().apply { update("isolated-token", "isolated-owner") }
    private val requests = mutableListOf<HttpRequest>()
    private val transport = HttpTransport { request ->
        requests += request
        if (request.path == "/api/v1/settings/current") HttpResponse(200, """{"basicVocabulary":"CET_4","purposeVocabularies":[]}""")
        else HttpResponse(503, "{}")
    }
    private val units = listOf(SemanticUnit("s", 0, "她工作了。"))
    private val english = listOf(EnglishChapterDraft("c", listOf(EnglishSentenceDraft("s", "她工作了。", true, listOf(EnglishCandidate("e", "She worked.", listOf("VOCABULARY:v")))))))
    private val selections = mapOf("s" to "e")

    @Before fun setUp() {
        context = IsolatedLearningDatabaseContext(InstrumentationRegistry.getInstrumentation().targetContext, "production-formation")
        database = FormationDatabase(context)
        database.writableDatabase.insertOrThrow("vocabulary_items", null, ContentValues().apply {
            put("account_id", "isolated-owner"); put("item_id", "v"); put("normalized_form", "work")
            put("semantic_sense_key", "work|工作"); put("display", "work"); put("meaning_zh", "工作"); put("status", "LEARNING"); put("verified_success_count", 0)
        })
        database.writableDatabase.insertOrThrow("vocabulary_wordlists", null, ContentValues().apply {
            put("account_id", "isolated-owner"); put("item_id", "v"); put("wordlist", "CET_4")
        })
    }
    @After fun tearDown() { database.close(); context.deleteDatabase("real-language-learning.db") }

    @Test fun realEnglishFactoryUsesTheActualSourceAndScopedOwnedKnowledge() = runBlocking {
        val graph = ProductionAppGraph(context, runtime, transport)
        withContext(Dispatchers.Main) {
            val vm = graph.englishGenerationFactory(HomeDiaryRoute.EnglishGeneration("她工作了。", units, listOf("s"), listOf(ChapterDraft("c", listOf("s"))), sourceType = SourceType.AI_SCENE), "english-entry")
                .create(EnglishGenerationViewModel::class.java)
            withCleared(vm) { withTimeout(5000) { vm.uiState.first { it.failed } } }
        }
        assertEquals(1, requests.count { it.path == "/api/v1/settings/current" })
        val body = JSONObject(requests.single { it.path.endsWith("english-candidates") }.body)
        assertEquals("AI_SCENE", body.getString("sourceType"))
        assertEquals("VOCABULARY:v", body.getJSONArray("knownKnowledge").getJSONObject(0).getString("ref"))
    }

    @Test fun realTargetLoadingFactoryCarriesSelectedKnownIdentityAndMeaningToTheServer() = runBlocking {
        val graph = ProductionAppGraph(context, runtime, transport)
        withContext(Dispatchers.Main) {
            val vm = graph.learningTargetLoadingFactory(HomeDiaryRoute.LearningTargets("她工作了。", units, english, selections), "targets-entry")
                .create(LearningTargetLoadingViewModel::class.java)
            withCleared(vm) { withTimeout(5000) { vm.uiState.first { it.failed } } }
        }
        assertEquals(1, requests.size)
        val known = JSONObject(requests.single().body).getJSONArray("chapters").getJSONObject(0).getJSONArray("sentences").getJSONObject(0).getJSONArray("reusedKnowledge").getJSONObject(0)
        assertEquals("VOCABULARY:v", known.getString("ref")); assertEquals("工作", known.getString("meaningZh"))
    }

    @Test fun recreatingTheSameTargetSelectionKeepsItsRequestIdentityAndKnownContext() = runBlocking {
        val graph = ProductionAppGraph(context, runtime, transport)
        val route = HomeDiaryRoute.LearningTargetSelection("她工作了。", units, english, selections, emptyList(), emptyList(), entryId = "target-selection")
        repeat(2) {
            withContext(Dispatchers.Main) {
                val vm = graph.learningTargetSelectionFactory(route).create(LearningTargetViewModel::class.java)
                withCleared(vm) {
                    val expression = english.single().sentences.single().wordExpressions("e").first()
                    vm.onAction(LearningTargetAction.ExpressionRequested("s", expression.expressionId))
                    withTimeout(5000) { vm.uiState.first { it.generationFailed } }
                }
            }
        }
        assertEquals(2, requests.size)
        val bodies = requests.map { JSONObject(it.body) }
        assertEquals(bodies[0].getString("commandId"), bodies[1].getString("commandId"))
        assertEquals("VOCABULARY:v", bodies[0].getJSONArray("chapters").getJSONObject(0).getJSONArray("sentences").getJSONObject(0).getJSONArray("reusedKnowledge").getJSONObject(0).getString("ref"))
    }

    private suspend fun withCleared(vm: ViewModel, action: suspend () -> Unit) {
        val store = ViewModelStore().apply { put("isolated", vm) }
        try { action() } finally { store.clear() }
    }
}
