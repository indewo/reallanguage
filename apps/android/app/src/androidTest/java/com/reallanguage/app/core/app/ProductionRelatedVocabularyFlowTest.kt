package com.reallanguage.app.core.app

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.*
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual production pages/graph/JSON/encryption/SQLite. Knowledge examples and the second case's
 * incremental target response use explicit transport test doubles; neither case is real AI evidence. */
class ProductionRelatedVocabularyFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val uuid = UUID.randomUUID().toString()
    private val account = "related-flow-$uuid"
    private val privateFiles = File(base.cacheDir, "related-flow-$uuid")
    private val isolated = IsolatedLearningDatabaseContext(base, "related-flow")
    private val context = object : ContextWrapper(isolated) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = privateFiles
    }
    private val session = SessionRuntime().apply { update("isolated-token", account) }
    private val requests = CopyOnWriteArrayList<JSONObject>()
    private var allowIncremental = false
    private val english = "I used to drink tea."
    private val wordEvidence = SourceEvidence(1, 16, 19, "tea", "tea", listOf("tea"))
    private val item = RelatedVocabularyItem("tea-gloss", "u1", "tea", "茶", LearningTargetType.WORD, wordEvidence)
    private val word = LearningTarget("word", "u1", "tea", "茶", LearningTargetType.WORD, sourceEvidence = wordEvidence)
    private val pattern = LearningTarget("pattern", "u1", "used to + verb", "表示过去的习惯", LearningTargetType.PATTERN,
        structureParts = listOf("used to", "verb"), relatedVocabulary = listOf("tea"),
        sourceEvidence = SourceEvidence(1, 2, 19, "used to drink tea", "used to + verb", listOf("used to drink tea")),
        relatedVocabularyItems = listOf(item), relatedVocabularyItemRefs = listOf(item.itemRef))
    private val initial = HomeDiaryRoute.LearningTargetSelection("我以前常喝茶。", listOf(SemanticUnit("u1", 0, "我以前常喝茶。")),
        listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", "我以前常喝茶。", true,
            listOf(EnglishCandidate("e1", english)))))), mapOf("u1" to "e1"), listOf(word, pattern), emptyList(),
        entryId = "target-related-flow", sourceEntryId = "related-source")
    private val graph = ProductionAppGraph(context, session, HttpTransport { request ->
        if (request.path == "/api/v1/content-formation/learning-targets") {
            check(allowIncremental)
            val body = JSONObject(requireNotNull(request.body))
            requests.add(body)
            val accepted = body.getJSONObject("requestedExpression").getJSONObject("acceptedVocabulary")
            check(accepted.getString("senseZh") == "茶")
            val evidence = accepted.getJSONObject("sourceEvidence")
            check(evidence.getInt("startUtf16") == 16)
            return@HttpTransport HttpResponse(200, JSONObject()
                .put("targets", JSONArray().put(JSONObject().put("targetId", "generated-tea").put("unitId", "u1")
                    .put("type", "WORD").put("display", "tea").put("explanationZh", "茶")
                    .put("sourceEvidence", evidence).put("structureParts", JSONArray())
                    .put("relatedVocabulary", JSONArray()).put("relatedVocabularyItems", JSONArray())
                    .put("relatedVocabularyItemRefs", JSONArray())))
                .put("queryableExpressions", JSONArray()).put("learningScopes", JSONArray()).toString())
        }
        check(request.path == "/api/v1/knowledge/examples") { "No unplanned request is allowed in this isolated flow" }
        val body = JSONObject(requireNotNull(request.body))
        requests.add(body)
        check(body.getString("targetType") == "PATTERN") { "A cancelled word must not be generated or charged" }
        val examples = listOf("I used to walk." to "我以前常步行。", "She used to swim." to "她以前常游泳。",
            "We used to read." to "我们以前常阅读。")
        HttpResponse(200, JSONObject().put("itemId", body.getString("itemId")).put("phonetic", JSONObject.NULL)
            .put("pronunciationText", body.getString("display")).put("representativeScene", english)
            .put("naturalRevalidation", true).put("examples", JSONArray(examples.map { (en, zh) ->
                JSONObject().put("textEn", en).put("textZh", zh).put("pronunciationText", en)
            })).toString())
    })

    @After fun cleanupOnlyThisFixture() = runBlocking(Dispatchers.IO) {
        val hash = MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString("") { "%02x".format(it) }
        AndroidFormationPayloadKeys().deleteAccount(hash)
        AndroidFixedSourcePayloadKeys().deleteAccount(hash)
        context.deleteDatabase("real-language-learning.db")
        check(requireNotNull(privateFiles.parentFile).canonicalFile == base.cacheDir.canonicalFile && privateFiles.name == "related-flow-$uuid")
        check(!privateFiles.exists() || privateFiles.deleteRecursively())
        Unit
    }

    @Test(timeout = 60_000) fun cancellingWordOnRealPageKeepsPatternExplanationThroughFixedChapterReadback() {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.setContent { RlTheme { HomeProductionFlow(graph, initialRoute = initial) } }
        waitFor("learning_target_word")
        compose.onNodeWithTag("learning_target_word").performClick()
        val selected = awaitSnapshot { it.selectedTargetIds == setOf("pattern") }
        assertEquals(listOf(item), selected.targets.single { it.targetId == "pattern" }.relatedVocabularyItems)
        compose.onNodeWithText("tea：茶").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("继续").performClick()
        waitFor("final_fix_confirm")
        val final = awaitSnapshot { it.phase == FormationRecoveryPhase.FINAL_FIX }
        assertEquals(setOf("pattern"), final.selectedTargetIds)
        compose.onNodeWithTag("final_fix_confirm").performClick()
        waitFor("chapter_entry_start")
        assertEquals(1, requests.size)
        assertEquals("PATTERN", requests.single().getString("targetType"))
        runBlocking(Dispatchers.IO) {
            FormationDatabase(context).use { helper ->
                val db = helper.readableDatabase
                db.rawQuery("select t.unit_id,t.type,t.related_vocabulary_snapshot,s.text_en from target_selections t " +
                    "join fixed_sentences s on s.account_id=t.account_id and s.chapter_id=t.chapter_id and s.unit_id=t.unit_id " +
                    "where t.account_id=?", arrayOf(account)).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("PATTERN", cursor.getString(1))
                    assertFalse("The explanation must be committed, not only present in the page state", cursor.isNull(2))
                    val saved = JSONObject(cursor.getString(2))
                    assertEquals(1, saved.getInt("version"))
                    assertEquals(cursor.getString(0), saved.getString("unitId"))
                    assertEquals(cursor.getString(3), saved.getString("sourceTextEn"))
                    val savedItem = saved.getJSONArray("items").getJSONObject(0)
                    assertEquals(cursor.getString(0), savedItem.getString("unitId"))
                    assertEquals("tea", savedItem.getString("display"))
                    assertEquals("茶", savedItem.getString("senseZh"))
                    assertEquals(item.itemRef, saved.getJSONArray("refs").getString(0))
                    assertEquals(16, savedItem.getJSONObject("sourceEvidence").getInt("startUtf16"))
                    assertFalse(cursor.moveToNext())
                }
                for (table in listOf("vocabulary_items", "vocabulary_occurrences", "target_vocabulary_details", "learning_obligations")) {
                    db.rawQuery("select count(*) from $table where account_id=?", arrayOf(account)).use {
                        assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                    }
                }
            }
            assertNull(graph.readFormation(initial.sourceEntryId))
        }
    }

    @Test(timeout = 60_000) fun reopenedProductionSelectionCarriesAcceptedMeaningAndRepeatedTapOnlyToggles() {
        allowIncremental = true
        val route = initial.copy(targets = listOf(pattern), initialSelectedTargetIds = setOf("pattern"))
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.setContent { RlTheme { HomeProductionFlow(graph, initialRoute = route) } }
        waitFor("learning_target_pattern")
        val layouts = mutableListOf<TextLayoutResult>()
        val sentence = compose.onNodeWithText(english)
        sentence.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        sentence.performTouchInput { click(layouts.single().getBoundingBox(16).center) }
        val added = awaitSnapshot { it.targets.any { target -> target.targetId == "generated-tea" } }
        assertEquals("茶", added.targets.single { it.targetId == "generated-tea" }.explanationZh)
        assertEquals(1, requests.size)
        compose.onNodeWithTag("learning_target_generated-tea").performScrollTo().performClick()
        awaitSnapshot { "generated-tea" !in it.selectedTargetIds }
        assertEquals(1, requests.size)
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun awaitSnapshot(predicate: (FormationRecoverySnapshot) -> Boolean): FormationRecoverySnapshot {
        val result = AtomicReference<FormationRecoverySnapshot?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch {
            while (isActive) {
                graph.readFormation(initial.sourceEntryId)?.takeIf(predicate)?.let { result.set(it); return@launch }
                delay(20)
            }
        }
        try {
            compose.waitUntil(10_000) { result.get() != null }
            return requireNotNull(result.get())
        } finally { job.cancel(); scope.cancel() }
    }
}
