package com.reallanguage.app.core.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.database.Cursor
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneStart
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** SPEC FR-LEN-002A / AC-E2E-057: unchanged upstream input retains its unfixed result.
 * Real continuous Home flow, Graph and JSON repository; only UUID-private storage and memory HTTP.
 * This is neither a process-recovery test nor evidence of AI quality, billing or a real service. */
class ProductionSceneGenerationReturnTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val prefix = "scene-generation-return-$id"
    private val directory = File(base.cacheDir, prefix)
    private val privateDb = IsolatedLearningDatabaseContext(base, prefix)
    private val preferences = Collections.synchronizedSet(mutableSetOf<String>())
    private val context = object : ContextWrapper(privateDb) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "$prefix-$name"
            preferences.add(isolated)
            return base.getSharedPreferences(isolated, mode)
        }
    }
    private val account = "scene-owner-$id"
    private val runtime = SessionRuntime().apply { update("ISOLATED_ACCESS", account) }
    private val questionCalls = CopyOnWriteArrayList<HttpRequest>()
    private val draftCalls = CopyOnWriteArrayList<HttpRequest>()
    private val reviewCalls = CopyOnWriteArrayList<HttpRequest>()
    private val unexpectedCalls = CopyOnWriteArrayList<String>()
    private val accepted = linkedMapOf<String, Pair<String, String>>()
    private val mounted = mutableStateOf(false)
    private var failFirstDraft = false
    private var ownsStorage = false
    private lateinit var graph: ProductionAppGraph
    private lateinit var protectedRows: Map<String, List<List<String?>>>

    @Before fun preparePrivateStorageAndTheRealFocusedWindow() {
        val db = privateDb.getDatabasePath(DATABASE).canonicalFile
        check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && !directory.exists())
        check(db.parentFile == base.cacheDir.canonicalFile && db.name.startsWith("$prefix-") && !db.exists())
        check(directory.mkdir())
        ownsStorage = true
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        graph = ProductionAppGraph(context, runtime, HttpTransport { request -> respond(request) }, MemoryCredentials())
        protectedRows = allRows()
    }

    @After fun disposeBeforeDeletingOnlyThisUuidStorage() {
        if (!ownsStorage) return
        compose.runOnIdle { mounted.value = false; compose.activity.viewModelStore.clear() }
        compose.waitForIdle()
        val db = privateDb.getDatabasePath(DATABASE).canonicalFile
        check(db.parentFile == base.cacheDir.canonicalFile && db.name.startsWith("$prefix-") && db.extension == "db")
        check(privateDb.deleteDatabase(DATABASE) || !db.exists())
        synchronized(preferences) { preferences.toList() }.forEach { name ->
            check(name.startsWith("$prefix-")); base.deleteSharedPreferences(name)
        }
        check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && directory.name == prefix)
        check(!directory.exists() || directory.deleteRecursively())
    }

    @Test fun unchangedAnswersAfterReturningReuseTheDeliveredDraftWithoutAnotherPost() {
        mountAndSubmitFirstAnswer()
        waitReview()
        assertDraft(1)
        assertEquals(1, draftCalls.size)
        returnToLastAnswer()
        submitRestoredAnswer()
        waitReview()
        assertEquals("Returning with unchanged answers must not start another paid draft request", 1, draftCalls.size)
        assertDraft(1)
        assertQuestionReplayAndNoFormalChanges()
    }

    @Test fun unknownGenerationReturningThroughQuestionsRetriesTheExactOriginalBodyAndCommand() {
        failFirstDraft = true
        mountAndSubmitFirstAnswer()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(base.getString(R.string.scene_generation_failed_title)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, draftCalls.size)
        val original = draftCalls.single().body
        compose.onNodeWithText(base.getString(R.string.scene_generation_return)).assertIsDisplayed().performClick()
        restoreLastAnswer()
        assertEquals("Returning and opening the previous answer is not an automatic generation retry", 1, draftCalls.size)
        submitRestoredAnswer()
        waitReview()
        assertEquals(2, draftCalls.size)
        assertEquals("The unknown paid POST must retain its exact body, including commandId", original, draftCalls.last().body)
        assertEquals(command(original), command(draftCalls.last().body))
        assertDraft(1)
        assertQuestionReplayAndNoFormalChanges()
    }

    @Test fun explicitRegenerateRequestsOneNewDraftWithTheVisiblePreviousUnits() {
        mountAndSubmitFirstAnswer()
        waitReview()
        assertDraft(1)
        val original = draftCalls.single().body
        tap("scene-review-regenerate")
        compose.waitUntil(5_000) { draftCalls.size == 2 }
        waitReviewVersion(2)
        assertEquals(2, draftCalls.size)
        val regenerated = draftCalls.last().body
        assertNotEquals(command(original), command(regenerated))
        assertEquals(JSONObject(original).getJSONObject("session").toString(), JSONObject(regenerated).getJSONObject("session").toString())
        assertEquals(draftJson(1).getJSONArray("units").toString(), JSONObject(regenerated).getJSONArray("previousUnits").toString())
        assertEquals("Regenerate does not silently ask the same question again", 2, questionCalls.size)
        assertNoFormalChanges()
    }

    @Test fun editingTheRestoredAnswerRequestsOneNewInputWithoutReusingTheOldCommand() {
        mountAndSubmitFirstAnswer()
        waitReview()
        val original = draftCalls.single().body
        returnToLastAnswer()
        compose.onNodeWithTag("scene-questions-answer").performTextReplacement(CHANGED_ANSWER)
        tap("scene-questions-submit")
        waitReviewVersion(2)
        assertEquals(2, draftCalls.size)
        val edited = draftCalls.last().body
        assertNotEquals(command(original), command(edited))
        val oldSession = JSONObject(original).getJSONObject("session")
        val newSession = JSONObject(edited).getJSONObject("session")
        assertEquals(oldSession.getString("operationId"), newSession.getString("operationId"))
        assertEquals(CHANGED_ANSWER, newSession.getJSONArray("answers").getJSONObject(0).getString("answerZh"))
        assertNotEquals(questionCalls[1].body, questionCalls[2].body)
        assertNoFormalChanges()
    }

    @Test fun editedChineseSurvivesUnchangedQuestionsAndIsStillReviewedAgainstTheOriginalDraft() {
        mountAndSubmitFirstAnswer()
        waitReview()
        compose.onNode(hasSetTextAction() and hasText(unitText(1))).performTextReplacement(EDITED_CHINESE)
        returnToLastAnswer()
        submitRestoredAnswer()
        waitReview()
        compose.onNode(hasSetTextAction() and hasText(EDITED_CHINESE)).assertIsDisplayed()
        assertEquals("Restoring the edited draft is not regeneration", 1, draftCalls.size)
        assertEquals(0, reviewCalls.size)
        tap("scene-review-confirm")
        compose.waitUntil(5_000) {
            // The real page prefixes each returned issue with a bullet.
            compose.onAllNodesWithText(REVIEW_ISSUE, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, reviewCalls.size)
        val wire = JSONObject(reviewCalls.single().body)
        assertEquals("DIALOGUE", wire.getString("contentType"))
        assertEquals(draftJson(1).getJSONArray("units").toString(), wire.getJSONArray("originalUnits").toString())
        val edited = wire.getJSONArray("editedUnits")
        assertEquals(EDITED_CHINESE, edited.getJSONObject(0).getString("textZh"))
        assertEquals("u1", edited.getJSONObject(0).getString("unitId"))
        assertEquals(draftJson(1).getJSONArray("units").getJSONObject(1).toString(), edited.getJSONObject(1).toString())
        assertEquals(listOf("u1"), wire.getJSONArray("changedUnitIds").let { values ->
            List(values.length()) { values.getString(it) }
        })
        compose.onNode(hasSetTextAction() and hasText(EDITED_CHINESE)).assertIsDisplayed()
        compose.onNodeWithTag("scene-review-confirm").assertIsEnabled()
        assertNoFormalChanges(expectedReviewCalls = 1)
    }

    @Test fun confirmedAbandonThenNewOperationWithTheSameAnswersCannotResurrectTheOldDraft() {
        // Suppress only this UUID account's optional Home reminder; do not touch notification permissions.
        graph.closeReminderForThisSession(account)
        mountAndSubmitFirstAnswer(fromSceneHome = true)
        waitReview()
        val original = draftCalls.single().body
        returnToLastAnswer()
        compose.onNodeWithText(base.getString(R.string.scene_questions_exit)).assertIsDisplayed().performClick()
        waitTag("scene-abandon-confirm")
        tap("scene-abandon-confirm")
        waitTag("home-scene-card")
        assertEquals("Abandoning an unfixed draft is not an AI operation", 1, draftCalls.size)
        assertNoFormalChanges()
        tap("home-scene-card")
        waitTag("scene-home-direct-panel")
        compose.onNodeWithTag("scene-home-direct-panel").performTouchInput { click(center) }
        waitTag("scene-home-direct")
        assertEquals("A new scene must not expose the abandoned description", "", compose.onNodeWithTag("scene-home-direct")
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("scene-home-direct").performTextReplacement(DESCRIPTION)
        finishDescriptionInput()
        waitTag("scene-type-dialogue")
        tap("scene-type-dialogue")
        tap("scene-type-continue")
        waitTag("scene-questions-answer")
        assertEquals("A new operation must not reuse the abandoned answer", "", compose.onNodeWithTag("scene-questions-answer")
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("scene-questions-answer").performTextReplacement(ANSWER)
        tap("scene-questions-submit")
        waitReviewVersion(2)
        assertEquals(2, draftCalls.size)
        val fresh = draftCalls.last().body
        assertNotEquals(command(original), command(fresh))
        val before = JSONObject(original).getJSONObject("session")
        val after = JSONObject(fresh).getJSONObject("session")
        assertNotEquals(before.getString("operationId"), after.getString("operationId"))
        assertEquals(before.getJSONObject("start").toString(), after.getJSONObject("start").toString())
        assertEquals(before.getJSONArray("answers").toString(), after.getJSONArray("answers").toString())
        compose.onAllNodes(hasSetTextAction() and hasText(unitText(1))).assertCountEquals(0)
        assertNoFormalChanges()
    }

    private fun mountAndSubmitFirstAnswer(fromSceneHome: Boolean = false) {
        mounted.value = true
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalActivityResultRegistryOwner provides compose.activity) {
                RlTheme { if (mounted.value) HomeProductionFlow(graph, initialRoute = if (fromSceneHome) HomeDiaryRoute.SceneHome()
                    else HomeDiaryRoute.SceneQuestions(SceneStart.DirectDescription(DESCRIPTION), SceneContentType.DIALOGUE, id)) }
            }
        }
        if (fromSceneHome) {
            waitTag("scene-home-direct-panel")
            compose.onNodeWithTag("scene-home-direct-panel").performTouchInput { click(center) }
            waitTag("scene-home-direct")
            compose.onNodeWithTag("scene-home-direct").performTextReplacement(DESCRIPTION)
            finishDescriptionInput()
            waitTag("scene-type-dialogue")
            tap("scene-type-dialogue")
            tap("scene-type-continue")
        }
        waitTag("scene-questions-answer")
        compose.onNodeWithTag("scene-questions-answer").performTextReplacement(ANSWER)
        tap("scene-questions-submit")
    }

    private fun returnToLastAnswer() { tap("scene-review-return"); restoreLastAnswer() }
    private fun finishDescriptionInput() {
        // This is flow/identity recovery, not an IME-layout fixture. Match the existing
        // SceneHome input test: dismiss the real keyboard before locating its scrollable action.
        compose.activityRule.scenario.onActivity { it.window.insetsController?.hide(WindowInsets.Type.ime()) }
        compose.waitUntil(5_000) { compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true }
        compose.onNodeWithTag("scene-home-continue").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun restoreLastAnswer() {
        waitTag("scene-questions-previous")
        // Reuse the real questions VM's history; never inject an action or manufacture a replacement route.
        tap("scene-questions-previous")
        waitTag("scene-questions-answer")
        compose.onNodeWithTag("scene-questions-answer").assertTextContains(ANSWER)
    }
    private fun submitRestoredAnswer() { tap("scene-questions-submit") }
    private fun waitReview() { waitTag("scene-review-editor") }
    private fun waitReviewVersion(version: Int) {
        try {
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasSetTextAction() and hasText(unitText(version))).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            val visibleVersions = (1..3).filter { candidate ->
                compose.onAllNodes(hasSetTextAction() and hasText(unitText(candidate))).fetchSemanticsNodes().isNotEmpty()
            }
            throw AssertionError("Expected review version $version; visible=$visibleVersions; drafts=${draftCalls.size}", failure)
        }
        assertDraft(version)
    }
    private fun assertDraft(version: Int) {
        compose.onNode(hasSetTextAction() and hasText(unitText(version))).assertIsDisplayed()
        compose.onNodeWithTag("scene-review-confirm").assertIsEnabled()
    }
    private fun waitTag(tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun tap(tag: String) { compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick() }
    private fun assertQuestionReplayAndNoFormalChanges() {
        // A retained next-question decision may also avoid its HTTP replay. Any replay must be exact.
        assertTrue(questionCalls.size in 2..3)
        assertEquals(1, questionCalls.drop(1).map { it.body }.distinct().size)
        assertNoFormalChanges()
    }
    private fun assertNoFormalChanges(expectedReviewCalls: Int = 0) {
        assertTrue("Only the declared scene endpoints are permitted", unexpectedCalls.isEmpty())
        assertEquals("Only explicit confirmation of edited content may request edit review", expectedReviewCalls, reviewCalls.size)
        assertEquals("Unfixed generated content must not create formal learning records", protectedRows, allRows())
    }

    @Synchronized private fun respond(request: HttpRequest): HttpResponse {
        if (request.method != "POST" || request.path !in setOf(QUESTIONS, DRAFTS, REVIEWS)) {
            unexpectedCalls += "${request.method} ${request.path}"
            return HttpResponse(500, "{}")
        }
        val value = JSONObject(request.body)
        if (request.path == REVIEWS) {
            reviewCalls += request
            // Stay on the real review page: the test verifies submission, not downstream safety/fixing.
            return HttpResponse(200, JSONObject().put("decision", "NEEDS_USER_EDIT").put("issues", JSONArray()
                .put(JSONObject().put("unitId", "u1").put("startOffset", 0).put("endOffset", 2)
                    .put("reason", "REQUIRED_INFORMATION_MISSING").put("explanationZh", REVIEW_ISSUE))).toString())
        }
        if (request.path == QUESTIONS) {
            questionCalls += request
            val answers = value.getJSONArray("confirmedAnswers")
            return if (answers.length() == 0) HttpResponse(200,
                """{"action":"ASK","questionId":"q1","questionZh":"需要延期几天？","options":[]}""")
            else HttpResponse(200, JSONObject().put("action", "GENERATE")
                .put("collectedFacts", JSONArray().put(answers.getJSONObject(0).getString("answerZh")))
                .put("missingNonBlockingFacts", JSONArray()).toString())
        }
        draftCalls += request
        val command = value.getString("commandId")
        val prior = accepted[command]
        if (prior != null && prior.first != request.body) return HttpResponse(409,
            """{"error":{"code":"IDEMPOTENCY_KEY_REUSED","retryable":false}}""")
        val delivery = prior ?: (request.body to draftJson(accepted.size + 1).toString()).also { accepted[command] = it }
        // The isolated ledger retained a result, but the client did not receive a usable response.
        if (failFirstDraft && draftCalls.size == 1) return HttpResponse(503, "{}")
        return HttpResponse(200, delivery.second)
    }

    private fun draftJson(version: Int): JSONObject = JSONObject().put("draftId", "draft-$id-$version")
        .put("contentType", "DIALOGUE").put("units", JSONArray()
            .put(JSONObject().put("unitId", "u1").put("speaker", "USER").put("textZh", unitText(version)).put("factReferences", JSONArray().put("q1")))
            .put(JSONObject().put("unitId", "u2").put("speaker", "OTHER").put("textZh", "好的，我会调整安排。").put("factReferences", JSONArray().put("q1"))))
    private fun unitText(version: Int) = "请调整会议安排（版本$version）。"
    private fun command(body: String) = JSONObject(body).getString("commandId")
    private fun allRows(): Map<String, List<List<String?>>> = FormationDatabase(context).use { helper ->
        val db = helper.readableDatabase
        val tables = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name", null).use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
        tables.associateWith { table ->
            require(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            db.rawQuery("select * from $table order by rowid", null).use { cursor -> buildList {
                while (cursor.moveToNext()) add(List(cursor.columnCount) { column -> when (cursor.getType(column)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_BLOB -> Base64.getEncoder().encodeToString(cursor.getBlob(column))
                    else -> cursor.getString(column)
                } })
            } }
        }
    }
    private inner class MemoryCredentials : SessionCredentialStore {
        private var value: StoredSession? = StoredSession(account, "ISOLATED_REFRESH", true)
        override suspend fun load() = value
        override suspend fun save(session: StoredSession) { value = session }
        override suspend fun clear() { value = null }
    }
    private companion object {
        const val DATABASE = "real-language-learning.db"
        const val QUESTIONS = "/api/v1/sources/scenes/next-question"
        const val DRAFTS = "/api/v1/sources/scenes/drafts"
        const val REVIEWS = "/api/v1/sources/scenes/edit-reviews"
        const val DESCRIPTION = "练习会议延期沟通"
        const val ANSWER = "延后两天"
        const val CHANGED_ANSWER = "延后三天"
        const val EDITED_CHINESE = "请把会议延后两天，我会通知大家。"
        const val REVIEW_ISSUE = "隔离审核位置反馈"
    }
}
