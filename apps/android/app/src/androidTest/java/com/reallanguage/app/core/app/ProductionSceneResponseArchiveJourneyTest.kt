package com.reallanguage.app.core.app

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.home.data.AndroidIncompleteLearningRepository
import com.reallanguage.app.feature.home.domain.IncompleteLearningLoadResult
import com.reallanguage.app.feature.learning.data.AndroidExplanationCache
import com.reallanguage.app.feature.learning.data.AndroidLearningCheckpointRepository
import com.reallanguage.app.feature.learning.data.AndroidLearningProgressStore
import com.reallanguage.app.feature.learning.data.CapturedReadSpeakAudio
import com.reallanguage.app.feature.learning.data.ReadSpeakAudioCapture
import com.reallanguage.app.feature.learning.domain.CheckpointSaveResult
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Actual UI/Graph/VM/JSON/SQLite: LR09 two focus sentences, three rounds each, atomic archive,
 * result -> same-source next chapter -> LR02 -> pause -> new Graph -> Home resume.
 * Fixed source and already-accepted upstream LR06/LR08 drafts are explicit local fixtures.
 * No LR09 task, judgment, completion, capability or archive is seeded. Zero selected optional
 * vocabulary/pattern targets is legal; this case does not certify knowledge-item archival.
 * 14 local POST fixtures plus 2 GET voice-preference reads, 6 NOT_AUDIO captures;
 * no real microphone or AI quality claim.
 * RECORD_AUDIO is never granted/revoked here; the host must preserve its prior permission.
 */
class ProductionSceneResponseArchiveJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val runId = UUID.randomUUID().toString()
    private val account = "scene-archive-journey-$runId"
    private val sourceId = "source-$runId"
    private val chapterId = "first-chapter-$runId"
    private val nextChapterId = "next-chapter-$runId"
    private val nextSentenceId = "next-focus-$runId"
    private val contextSentenceId = "context-$runId"
    private val nextEnglish = "I wrote a short note."
    private val nextChinese = "我写了一张简短的便条。"
    private val nextExplanation = "隔离的已接受基础讲解：这句话说明写了一张简短便条。"
    private val directory = File(base.cacheDir, "scene-archive-journey-$runId")
    private val preferences = Collections.synchronizedSet(mutableSetOf<String>())
    private val isolatedDb = IsolatedLearningDatabaseContext(base, "scene-archive-journey-$runId")
    private val context = object : ContextWrapper(isolatedDb) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = File(directory, "private")
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "scene-archive-journey-$runId-$name"
            preferences.add(isolatedName)
            return base.getSharedPreferences(isolatedName, mode)
        }
    }
    private data class Focus(val id: String, val en: String, val zh: String, val taskId: String,
        val background: String?, val lines: List<Pair<String, String>>)
    private val focuses = listOf(
        Focus("first-focus-$runId", "I felt calm.", "我感到平静。", "first-task-$runId",
            "At the end of a quiet afternoon.", listOf("AI" to "How did you feel?")),
        Focus("second-focus-$runId", "I started to read.", "我开始阅读。", "second-task-$runId",
            null, listOf("AI" to "I felt calm.", "AI" to "What did you do next?")),
    )
    private data class Mounted(val graph: ProductionAppGraph, val initialRoute: HomeDiaryRoute)
    private val host = mutableStateOf<Mounted?>(null)
    private var mounted = false
    private var initialPermission: Int? = null
    private val requests = Collections.synchronizedList(mutableListOf<HttpRequest>())
    private val sceneRecoveryKeys = com.reallanguage.app.testing.IsolatedFormationPayloadKeys()
    private val fixtureFailure = AtomicReference<Throwable?>(null)
    private val credentialAccesses = AtomicInteger()
    private val voicePreferenceReads = AtomicInteger()
    private val captureCreations = AtomicInteger()
    private val captureStarts = AtomicInteger()
    private val captureFinishes = AtomicInteger()
    private val attemptFiles = Collections.synchronizedList(mutableListOf<File>())
    private var inconclusiveFinalFixture = false
    private val inconclusiveRetryChosen = AtomicBoolean(false)
    private fun expectedSceneCaptures() = if (inconclusiveFinalFixture) 7 else 6
    private fun expectedScenePosts() = if (inconclusiveFinalFixture) 16 else 14

    @After fun cleanOnlyThisUuidFixture() {
        if (mounted) {
            compose.runOnIdle { host.value = null }
            compose.waitForIdle()
        }
        runBlocking(Dispatchers.IO) {
            val databaseFile = context.getDatabasePath("real-language-learning.db").canonicalFile
            check(databaseFile.parentFile == base.cacheDir.canonicalFile)
            check(databaseFile.name.startsWith("scene-archive-journey-$runId-") && databaseFile.name.endsWith(".db"))
            context.deleteDatabase("real-language-learning.db")
            synchronized(preferences) { preferences.toList() }.forEach { name ->
                check(name.startsWith("scene-archive-journey-$runId-")); base.deleteSharedPreferences(name)
            }
            check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            check(directory.name == "scene-archive-journey-$runId")
            check(!directory.exists() || directory.deleteRecursively())
        }
        initialPermission?.let { assertEquals("The test must not change microphone permission", it,
            ContextCompat.checkSelfPermission(base, Manifest.permission.RECORD_AUDIO)) }
    }

    @Test(timeout = 240_000)
    fun threeRealSceneRoundsPerFocusArchiveThenNextChapterResumesFromHomeThroughANewGraph() = runJourney(false)

    @Test(timeout = 240_000)
    fun lateArchiveSqlFailureRollsBackFormalStateThenActualRetryArchivesOnceWithoutRepeatingScenes() = runJourney(true)

    @Test(timeout = 240_000)
    fun thirdRoundInconclusivePersistsNoJudgmentThenUserRetryPassesWithoutRepeatingTraining() = runJourney(false, true)

    @Test(timeout = 240_000)
    fun ordinarySceneHeaderRoundAndActualExitDoNotOverlap() = runJourney(false, verifyHeader = true)

    private fun runJourney(failArchiveAfterCapabilitySettlement: Boolean, retryInconclusiveFinal: Boolean = false, verifyHeader: Boolean = false) {
        check(!failArchiveAfterCapabilitySettlement || !retryInconclusiveFinal)
        inconclusiveFinalFixture = retryInconclusiveFinal
        initialPermission = ContextCompat.checkSelfPermission(base, Manifest.permission.RECORD_AUDIO)
        assertEquals("Host owns permission setup/restoration; this test never grants it", PackageManager.PERMISSION_GRANTED, initialPermission)
        val runtime = session()
        awaitIo { seedAcceptedUpstreamFacts(runtime) }
        val before = awaitIo { allRows() }
        awaitIo { assertNoSceneOrArchiveWasSeeded() }
        val firstGraph = graph(runtime)
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        host.value = Mounted(firstGraph, HomeDiaryRoute.IncompleteLearning())
        compose.setContent { RlTheme { RlPaperBackground {
            host.value?.let { current -> key(current.graph) {
                HomeProductionFlow(current.graph, initialRoute = current.initialRoute)
            } }
        } } }
        mounted = true
        openUnfinished(chapterId)
        waitTag("scene-response-workspace")
        assertEquals("A missing seam must fail before any real recording action", 1, captureCreations.get())
        awaitSceneProgress(runtime) { it["taskCount"] == "2" && it["taskIndex"] == "0" && it["roundIndex"] == "0" }
        assertEquals(2, observedRequests().size)
        awaitIo { assertSavedTasks() }
        if (verifyHeader) {
            val round = compose.onNodeWithText(base.getString(R.string.scene_response_round_total, 1))
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val exit = compose.onNodeWithText(base.getString(R.string.learning_exit_open))
                .assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot
            assertFalse("The actual exit control must not cover the current round: round=$round, exit=$exit", round.overlaps(exit))
            click(R.string.learning_exit_open)
            waitText(base.getString(R.string.learning_exit_page_title))
            compose.onNodeWithTag("learning-exit-pause").assertIsDisplayed().assertIsEnabled().performClick()
            waitTag("home-incomplete-card")
            assertEquals(2, observedRequests().size)
            assertEquals(0, captureStarts.get())
            assertEquals(0, awaitIo { count("chapter_archives", chapterId) })
            return
        }
        // Playback is an existing page action, not fake completion; OS TTS quality is out of scope.
        click(R.string.scene_response_play_previous)

        var beforeArchiveFailure: Map<String, List<List<String?>>>? = null
        var beforeInconclusive: Map<String, List<List<String?>>>? = null
        focuses.forEachIndexed { focusIndex, focus ->
            repeat(3) { round ->
                waitTag("scene-response-workspace")
                awaitSceneProgress(runtime) { it["taskIndex"] == focusIndex.toString() && it["roundIndex"] == round.toString() }
                compose.onNodeWithText(base.getString(R.string.scene_response_round_total, round + 1)).assertIsDisplayed()
                compose.onNodeWithTag("scene-dialogue-scroll").assertTextEquals(
                    focus.lines.joinToString("   ") { "${it.first}: ${it.second}" })
                focus.background?.let { compose.onNodeWithTag("scene-background-scroll").assertTextEquals(it) }
                if (round == 2) {
                    // Before recording/transcription the user's fixed answer must be hidden.
                    compose.onNodeWithTag("scene-transcript-scroll").assertTextEquals("")
                    compose.onAllNodesWithText(focus.en).assertCountEquals(0)
                } else compose.onNodeWithTag("scene-transcript-scroll").assertTextEquals(focus.en)
                if (focusIndex == 0 && round == 2) captureJourneyFrame("30-third-round-hidden", "scene-response-workspace")

                click(R.string.pattern_speaking_start_response)
                waitText(base.getString(R.string.scene_response_listening))
                click(R.string.pattern_speaking_response_finished)
                waitText(base.getString(R.string.scene_response_confirm_transcript))
                compose.onNode(hasSetTextAction()).assertIsDisplayed().assert(
                    SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(focus.en)),
                )
                assertEquals("Transcription and training never save a formal scene judgment",
                    focuses.take(focusIndex).associate { it.id to 1 }, awaitIo { sceneDecisions() })
                assertEquals(0, awaitIo { count("chapter_archives", chapterId) })
                assertTrue("The same synthetic attempt remains available until explicit confirmation",
                    synchronized(attemptFiles) { attemptFiles.last().exists() })
                if (failArchiveAfterCapabilitySettlement && focusIndex == focuses.lastIndex && round == 2) {
                    awaitIo { installLateArchiveFailure() }
                    beforeArchiveFailure = awaitIo { allRows() }
                }
                if (retryInconclusiveFinal && focusIndex == focuses.lastIndex && round == 2) {
                    beforeInconclusive = awaitIo { allRows() }
                }
                click(R.string.scene_response_confirm_transcript)
                if (retryInconclusiveFinal && focusIndex == focuses.lastIndex && round == 2) {
                    retryInconclusiveSceneThroughActualControls(runtime, requireNotNull(beforeInconclusive))
                }
                if (round < 2) {
                    val saved = awaitSceneProgress(runtime) { it["taskIndex"] == focusIndex.toString() &&
                        it["roundIndex"] == round.toString() && it["trainingCompleted"] == "true" }
                    assertEquals(focus.en, saved["transcript"])
                    assertEquals(trainingFeedback(focusIndex, round), saved["feedback"])
                    assertEquals("", saved["finalDecision"])
                    assertEquals(focuses.take(focusIndex).associate { it.id to 1 }, awaitIo { sceneDecisions() })
                    compose.onNodeWithTag("scene-feedback-scroll").assertTextEquals(trainingFeedback(focusIndex, round))
                    click(R.string.scene_response_next_round)
                    awaitSceneProgress(runtime) { it["taskIndex"] == focusIndex.toString() && it["roundIndex"] == (round + 1).toString() &&
                        it["trainingCompleted"] == "false" && it["transcript"] == "" }
                } else if (focusIndex == 0) {
                    awaitIo { withTimeout(15_000) { while (sceneDecisions() != mapOf(focus.id to 1)) delay(20) } }
                    val next = awaitSceneProgress(runtime) { it["taskIndex"] == "1" && it["roundIndex"] == "0" }
                    assertEquals("", next["finalDecision"])
                    assertEquals("", next["transcript"])
                    assertFalse(next.containsKey("candidateDecision"))
                    assertEquals(0, awaitIo { count("chapter_archives", chapterId) })
                } else {
                    // The final confirmation now includes encrypted I/O. Compose idle is not
                    // completion of that work: wait for the real accepted SQL result (or its
                    // atomic archive), then retain the original temporary-audio deletion check.
                    awaitIo { withTimeout(15_000) {
                        val expected = focuses.associate { it.id to 1 }
                        while (count("chapter_archives", chapterId) == 0 && sceneDecisions() != expected) delay(20)
                    } }
                }
                assertFalse("Finished feedback/evaluation must delete its temporary synthetic bytes",
                    synchronized(attemptFiles) { attemptFiles.any(File::exists) })
            }
        }

        // The last actual accepted evaluation drives the real chapter-result factory and archive.
        // Do not seed an archive, call archive from this test, or navigate straight to this page.
        if (failArchiveAfterCapabilitySettlement) {
            val failureText = base.getString(R.string.chapter_result_failed)
            waitText(failureText)
            compose.onNodeWithText(failureText).assertIsDisplayed()
            compose.onNodeWithTag("chapter-result-summary").assertDoesNotExist()
            val savedCompletion = awaitSceneProgress(runtime) {
                it["taskIndex"] == focuses.lastIndex.toString() && it["roundIndex"] == "2" && it["completed"] == "true"
            }
            assertEquals("PASS", savedCompletion["finalDecision"])
            assertEquals("PASS", savedCompletion["candidateDecision"])
            assertEquals(focuses.associate { it.id to 1 }, awaitIo { sceneDecisions() })
            assertEquals(OrdinaryLearningCheckpoint(chapterId, "LR-09", focuses.first().id),
                awaitIo { firstGraph.learningCheckpointRepository.read(chapterId) })
            val afterFailedArchive = awaitIo { assertLateArchiveRolledBack(); assertSavedTasks(); allRows() }
            val permittedFinalSceneWrites = setOf("scene_response_result_drafts", "learning_module_progress", "learning_checkpoints")
            assertEquals("A failed archive must retain every other product table exactly, including upstream drafts, fixed source and cached explanations",
                requireNotNull(beforeArchiveFailure).filterKeys { it !in permittedFinalSceneWrites },
                afterFailedArchive.filterKeys { it !in permittedFinalSceneWrites })
            assertFixtureBudget(expectedSettingsReads = 0)
            assertEquals(6, captureStarts.get()); assertEquals(6, captureFinishes.get())
            assertFalse(synchronized(attemptFiles) { attemptFiles.any(File::exists) })

            val retryText = base.getString(R.string.read_speak_retry)
            val retry = compose.onNodeWithText(retryText).assertIsDisplayed().assertIsEnabled()
            val retryClick = requireNotNull(retry.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            awaitIo { removeLateArchiveFailure() }
            // Invoke the same actual retry button callback twice in one UI turn. A second
            // physical tap could land on the newly displayed result page instead of retry.
            compose.runOnIdle { assertTrue(retryClick()); assertTrue(retryClick()) }
        }
        waitTag("chapter-result-summary")
        compose.onNodeWithText("本章已完成并归档").assertIsDisplayed()
        compose.onNodeWithText("第 1 章 · 学习结果").assertIsDisplayed()
        captureJourneyFrame("32-archived-result", "chapter-result-summary")
        val archived = awaitIo { assertArchived(); allRows() }
        assertFixtureBudget()
        assertEquals(expectedSceneCaptures(), captureStarts.get()); assertEquals(expectedSceneCaptures(), captureFinishes.get())
        assertEquals(before.filterKeys { it !in permittedTables }, archived.filterKeys { it !in permittedTables })
        val formalReceipt = awaitIo { archiveAndCapabilities() }

        click(R.string.chapter_result_next_chapter)
        waitTag("chapter-selection-start-$nextChapterId")
        compose.onNodeWithTag("chapter-selection-start-$chapterId").assertDoesNotExist()
        compose.onNodeWithText("这份日记还有 1 个未开始章节").assertIsDisplayed()
        captureJourneyFrame("33-next-chapter", "chapter-selection-start-$nextChapterId")
        compose.onNodeWithTag("chapter-selection-start-$nextChapterId").assertIsDisplayed().performClick()
        waitText("第 2 章 · 共同学习")
        compose.onNodeWithTag("chapter_entry_sentences").assertIsDisplayed()
        compose.onNodeWithText(nextChinese, substring = true).assertExists()
        compose.onNodeWithTag("chapter_entry_start").assertIsDisplayed().assertIsEnabled().performClick()
        assertNextExplanation()
        captureJourneyFrame("23-next-explanation", "explanation_content")
        awaitCheckpoint(firstGraph)
        // Initial LR02 entry has a durable resume pointer, but no changed working snapshot.
        // Exercise the existing skip-explanation action before requiring saved module state.
        click(R.string.explanation_skip)
        val nextProgress = awaitIo { withTimeout(15_000) {
            var value: Map<String, String>?
            do {
                value = AndroidLearningProgressStore(context, runtime, nextChapterId, nextSentenceId, "LR-02").read()
                if (value == null) delay(20)
            } while (value == null)
            requireNotNull(value)
        } }
        assertEquals("0", nextProgress["reads"])
        assertEquals("false", nextProgress["explaining"])
        click(R.string.learning_exit_open)
        waitText(base.getString(R.string.learning_exit_page_title))
        captureJourneyFrame("31-learning-exit", "learning-exit-pause")
        compose.onNodeWithTag("learning-exit-pause").assertIsDisplayed().assertIsEnabled().performClick()
        waitTag("home-incomplete-card")
        compose.onNodeWithTag("home-incomplete-card").assertIsDisplayed().assertIsEnabled()
        awaitIo { assertOnlyNextIncomplete(runtime); assertArchived() }
        assertEquals(formalReceipt, awaitIo { archiveAndCapabilities() })
        assertFixtureBudget(expectedSettingsReads = 1)

        compose.runOnIdle { host.value = null }
        compose.waitForIdle()
        val newRuntime = session()
        val newGraph = graph(newRuntime)
        assertNotSame(firstGraph, newGraph)
        assertEquals(expectedNextCheckpoint(), awaitIo { newGraph.learningCheckpointRepository.read(nextChapterId) })
        compose.runOnIdle { host.value = Mounted(newGraph, HomeDiaryRoute.Home) }
        waitTag("home-incomplete-card")
        compose.onNodeWithTag("home-incomplete-card").assertIsDisplayed().assertIsEnabled().performClick()
        waitTag("incomplete-learning-resume-$nextChapterId")
        compose.onNodeWithTag("incomplete-learning-resume-$chapterId").assertDoesNotExist()
        openUnfinished(nextChapterId)
        assertNextExplanation()
        awaitCheckpoint(newGraph)
        val restored = awaitIo { newGraph.resolveResumeRoute(nextChapterId, "LR-02", expectedNextCheckpoint().stepId) }
        // Resume intentionally resolves through preparation; the mounted route above has
        // already loaded its accepted cache and displayed the real explanation page.
        assertTrue(restored is HomeDiaryRoute.ChapterExplanation)
        restored as HomeDiaryRoute.ChapterExplanation
        assertEquals(nextChapterId, restored.chapter.chapterId)
        assertEquals(nextSentenceId, restored.focusSentenceId)
        assertEquals(listOf(nextSentenceId), restored.chapter.sentences.map { it.sentenceId })
        assertEquals(nextEnglish, restored.chapter.sentences.single().textEn)
        assertEquals(nextProgress, awaitIo { AndroidLearningProgressStore(context, newRuntime, nextChapterId, nextSentenceId, "LR-02").read() })
        awaitIo { assertOnlyNextIncomplete(newRuntime); assertArchived() }
        assertEquals(formalReceipt, awaitIo { archiveAndCapabilities() })
        assertFixtureBudget(expectedSettingsReads = 2)
        assertEquals(1, captureCreations.get()); assertEquals(expectedSceneCaptures(), captureStarts.get()); assertEquals(expectedSceneCaptures(), captureFinishes.get())
        assertEquals(0, credentialAccesses.get())
        assertFalse(synchronized(attemptFiles) { attemptFiles.any(File::exists) })
        Log.i("RealLanguageJourney", "LR09_THREE_ROUNDS_ARCHIVE_NEXT_HOME run=$runId inconclusiveRetry=$retryInconclusiveFinal focus=2 rounds=6 fixturePost=${expectedScenePosts()} settingsGet=2 syntheticCaptures=${expectedSceneCaptures()} archives=1 nextChapter=2 newGraph=true realAi=0 realMicrophone=0")
    }

    /** Exercises an already-INCONCLUSIVE HTTP response, not real-audio confidence estimation. */
    private fun retryInconclusiveSceneThroughActualControls(
        runtime: SessionRuntime,
        beforeEvaluation: Map<String, List<List<String?>>>,
    ) {
        val focus = focuses.last()
        val uncertain = awaitSceneProgress(runtime) {
            it["taskIndex"] == focuses.lastIndex.toString() && it["roundIndex"] == "2" &&
                it["finalDecision"] == "INCONCLUSIVE"
        }
        assertEquals("INCONCLUSIVE", uncertain["candidateDecision"])
        assertEquals("false", uncertain["completed"])
        assertEquals("false", uncertain["trainingCompleted"])
        assertEquals(focus.en, uncertain["transcript"])
        assertEquals(focus.taskId, uncertain["task.${focuses.lastIndex}.id"])
        assertEquals(mapOf(focuses.first().id to 1), awaitIo { sceneDecisions() })
        val afterEvaluation = awaitIo { assertLateArchiveRolledBack(); assertSavedTasks(); allRows() }
        val workingProgressOnly = setOf("learning_module_progress", "learning_checkpoints")
        assertEquals("Inconclusive evidence may save working state but no PASS, FAIL, mastery, obligation or archive",
            beforeEvaluation.filterKeys { it !in workingProgressOnly }, afterEvaluation.filterKeys { it !in workingProgressOnly })
        waitText(base.getString(R.string.scene_response_inconclusive))
        compose.onNodeWithText(base.getString(R.string.scene_response_inconclusive)).assertIsDisplayed()
        compose.onNodeWithText(base.getString(R.string.full_sentence_speaking_stop)).assertDoesNotExist()
        compose.onNodeWithTag("chapter-result-summary").assertDoesNotExist()
        assertFixtureBudget(inconclusiveRetryCompleted = false)
        assertEquals(6, captureStarts.get()); assertEquals(6, captureFinishes.get())
        assertFalse(synchronized(attemptFiles) { attemptFiles.any(File::exists) })

        // Both existing retry buttons dispatch the same action. Select an actual clickable
        // button instead of assuming its duplicate label uniquely identifies a node.
        val retryText = base.getString(R.string.read_speak_retry)
        compose.onAllNodes(hasText(retryText) and hasClickAction()).onFirst()
            .assertIsDisplayed().assertIsEnabled().performClick()
        check(inconclusiveRetryChosen.compareAndSet(false, true))
        val reset = awaitSceneProgress(runtime) {
            it["taskIndex"] == focuses.lastIndex.toString() && it["roundIndex"] == "2" && it["finalDecision"] == ""
        }
        assertEquals("", reset["transcript"])
        assertFalse(reset.containsKey("candidateDecision"))
        assertEquals("false", reset["completed"])
        compose.onNodeWithTag("scene-transcript-scroll").assertTextEquals("")
        compose.onNodeWithText(base.getString(R.string.scene_response_round_total, 3)).assertIsDisplayed()
        assertFixtureBudget(inconclusiveRetryCompleted = false)
        assertEquals(6, captureStarts.get()); assertEquals(6, captureFinishes.get())
        assertEquals(mapOf(focuses.first().id to 1), awaitIo { sceneDecisions() })

        click(R.string.pattern_speaking_start_response)
        waitText(base.getString(R.string.scene_response_listening))
        click(R.string.pattern_speaking_response_finished)
        waitText(base.getString(R.string.scene_response_confirm_transcript))
        compose.onNode(hasSetTextAction()).assertIsDisplayed().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(focus.en)),
        )
        assertEquals(15, observedRequests().size)
        assertEquals(7, captureStarts.get()); assertEquals(7, captureFinishes.get())
        assertEquals(mapOf(focuses.first().id to 1), awaitIo { sceneDecisions() })
        assertEquals(0, awaitIo { count("chapter_archives", chapterId) })
        assertTrue(synchronized(attemptFiles) { attemptFiles.last().exists() })
        click(R.string.scene_response_confirm_transcript)
        waitTag("chapter-result-summary")
        // The enclosing journey independently reads the one archive and all six actual
        // capabilities, then proves next-chapter/Home reentry adds no scene requests.
        assertFixtureBudget()
        assertFalse(synchronized(attemptFiles) { attemptFiles.any(File::exists) })
    }

    private fun session() = SessionRuntime().apply { update("isolated-not-a-real-session", account) }
    private fun graph(runtime: SessionRuntime) = ProductionAppGraph(context, runtime, HttpTransport { request ->
        try { fixtureResponse(request) }
        catch (failure: Throwable) { fixtureFailure.compareAndSet(null, failure); HttpResponse(500, "{\"error\":\"isolated-contract-failed\"}") }
    }, object : SessionCredentialStore {
        override suspend fun load(): StoredSession? { credentialAccesses.incrementAndGet(); error("No credential read") }
        override suspend fun save(session: StoredSession) { credentialAccesses.incrementAndGet(); error("No credential save") }
        override suspend fun clear() { credentialAccesses.incrementAndGet(); error("No credential clear") }
    }, sceneResponseCaptureFactory = { _, owner ->
        check(owner.accountId == account)
        check(captureCreations.incrementAndGet() == 1)
        object : ReadSpeakAudioCapture {
            private var active: Int? = null
            private var attempts = 0
            override fun start(): Boolean {
                check(active == null && attempts < expectedSceneCaptures())
                active = ++attempts; captureStarts.incrementAndGet(); return true
            }
            override fun finish(): CapturedReadSpeakAudio {
                val attempt = requireNotNull(active); active = null
                val file = File(context.cacheDir, "synthetic-scene-$attempt.test")
                check(!file.exists()); file.writeBytes(syntheticBytes(attempt))
                attemptFiles.add(file); captureFinishes.incrementAndGet()
                return CapturedReadSpeakAudio(file)
            }
            override fun cancel() { active = null }
        }
    }, sceneResponseRecoveryKeys = sceneRecoveryKeys).also { it.closeReminderForThisSession(account) }

    @Synchronized private fun fixtureResponse(request: HttpRequest): HttpResponse {
        if (request.method == "GET") {
            assertEquals("/api/v1/settings/current/revision", request.path)
            assertEquals("", request.body)
            assertEquals("Voice settings are read only after the completed scene journey", expectedScenePosts(), observedRequests().size)
            check(voicePreferenceReads.incrementAndGet() <= 2) { "Only the two LR02 entries may read preferences" }
            val settings = JSONObject().put("formalityLevel", 3).put("slangEnabled", false)
                .put("basicVocabulary", "CET_4").put("purposeVocabularies", JSONArray())
                .put("styleReference", "").put("voiceEnabled", false).put("autoPlayEnabled", false)
                .put("speechSpeedLevel", 3)
            return HttpResponse(200, JSONObject().put("rowVersion", 1).put("settings", settings).toString())
        }
        assertEquals("POST", request.method)
        val position = synchronized(requests) { requests.size }
        check(position < expectedScenePosts()) { "No automatic retry, extra generation or next-chapter AI is allowed" }
        val body = JSONObject(requireNotNull(request.body))
        assertEquals(chapterId, body.getString("chapterId"))
        assertTrue(body.getString("commandId").isNotBlank())
        val output = if (position < 2) {
            val focus = focuses[position]
            assertEquals("/api/v1/learning/scene-response-tasks", request.path)
            assertEquals(focus.id, body.getString("focusSentenceId"))
            val chapter = body.getJSONArray("chapterSentences")
            assertEquals(3, chapter.length())
            assertEquals(listOf(focuses[0].id, contextSentenceId, focuses[1].id), (0..2).map { chapter.getJSONObject(it).getString("sentenceId") })
            assertEquals(listOf(focuses[0].en, "Then I went home.", focuses[1].en), (0..2).map { chapter.getJSONObject(it).getString("textEn") })
            assertEquals(listOf(true, false, true), (0..2).map { chapter.getJSONObject(it).getBoolean("isFocus") })
            JSONObject().put("taskId", focus.taskId).put("sentenceId", focus.id)
                .put("backgroundEn", focus.background ?: JSONObject.NULL).put("contextLines", linesJson(focus))
                .put("targetSentenceEn", focus.en)
        } else {
            val feedbackPosition = position - 2
            val finalRetry = inconclusiveFinalFixture && position >= 14
            check(!finalRetry || inconclusiveRetryChosen.get()) { "An inconclusive result must not automatically create another request" }
            val focusIndex = if (finalRetry) focuses.lastIndex else feedbackPosition / 6
            val focus = focuses[focusIndex]
            val round = if (finalRetry) 2 else (feedbackPosition % 6) / 2
            val transcribing = feedbackPosition % 2 == 0
            assertEquals(focus.id, body.getString("sentenceId"))
            assertEquals("audio/mp4", body.getString("audioMimeType"))
            assertArrayEquals(syntheticBytes(if (finalRetry) 7 else focusIndex * 3 + round + 1),
                Base64.getDecoder().decode(body.getString("audioBase64")))
            if (transcribing) {
                assertEquals("/api/v1/learning/speaking-transcriptions", request.path)
                assertFalse(body.has("confirmedTranscript"))
                JSONObject().put("transcript", focus.en).put("lowConfidenceSegments", JSONArray()).put("confidence", 0.99)
            } else {
                assertEquals(focus.taskId, body.getString("taskId"))
                assertEquals(focus.en, body.getString("targetTextEn"))
                assertEquals(focus.en, body.getString("confirmedTranscript"))
                assertTrue(body.getBoolean("userTranscriptConfirmed"))
                assertEquals(round, body.getInt("roundIndex"))
                assertEquals(linesJson(focus).toString(), body.getJSONArray("contextLines").toString())
                if (round < 2) {
                    assertEquals("/api/v1/learning/scene-response-training-feedback", request.path)
                    assertFalse(body.has("targetHidden"))
                    JSONObject().put("status", "READY").put("feedbackZh", trainingFeedback(focusIndex, round))
                        .put("pronunciationConfidence", 0.99)
                } else {
                    assertEquals("/api/v1/learning/scene-response-evaluations", request.path)
                    assertTrue(body.getBoolean("targetHidden"))
                    val inconclusive = inconclusiveFinalFixture && position == 13
                    // This is the complete service response after its evidence gate. True
                    // content flags must not override its explicit INCONCLUSIVE decision.
                    JSONObject().put("decision", if (inconclusive) "INCONCLUSIVE" else "PASS")
                        .put("correctTurn", true).put("targetSentenceComplete", true).put("pronunciationIntelligible", true)
                        .put("pronunciationConfidence", if (inconclusive) 0.4 else 0.99)
                        .put("feedbackZh", if (inconclusive) "隔离证据不足结果，不是实际录音判断。"
                            else "隔离终轮结果 $focusIndex，不是原音质量证明。")
                }
            }
        }
        requests.add(request)
        return HttpResponse(200, output.toString())
    }
    private fun linesJson(focus: Focus) = JSONArray().apply { focus.lines.forEach { put(JSONObject().put("speaker", it.first).put("textEn", it.second)) } }
    private fun trainingFeedback(focus: Int, round: Int) = "隔离训练反馈：重点句 ${focus + 1}，第 ${round + 1} 轮；不作正式判定。"
    private fun syntheticBytes(attempt: Int) = "NOT_AUDIO:isolated:$runId:$attempt".toByteArray(Charsets.UTF_8)
    private fun observedRequests() = synchronized(requests) { requests.toList() }
    private fun assertFixtureBudget(expectedSettingsReads: Int = 0, inconclusiveRetryCompleted: Boolean = true) {
        assertFixtureHealthy()
        val actual = observedRequests()
        val extraAttempt = if (inconclusiveFinalFixture && inconclusiveRetryCompleted) 1 else 0
        val expectedPostCount = 14 + extraAttempt * 2
        assertEquals(expectedPostCount, actual.size)
        assertEquals(mapOf(
            "/api/v1/learning/scene-response-tasks" to 2,
            "/api/v1/learning/speaking-transcriptions" to 6 + extraAttempt,
            "/api/v1/learning/scene-response-training-feedback" to 4,
            "/api/v1/learning/scene-response-evaluations" to 2 + extraAttempt,
        ), actual.groupingBy { it.path }.eachCount())
        assertEquals(expectedPostCount, actual.map { JSONObject(requireNotNull(it.body)).getString("commandId") }.toSet().size)
        assertEquals(0, credentialAccesses.get())
        assertEquals(expectedSettingsReads, voicePreferenceReads.get())
    }

    private suspend fun seedAcceptedUpstreamFacts(runtime: SessionRuntime) {
        FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("insert into formation_flows values(?,?,'FIXED','DIARY','隔离完整场景旅程')", arrayOf(account, sourceId))
                db.execSQL("insert into chapters values(?,?,?,0,'NOT_STARTED')", arrayOf(account, sourceId, chapterId))
                db.execSQL("insert into chapters values(?,?,?,1,'NOT_STARTED')", arrayOf(account, sourceId, nextChapterId))
                focuses.forEachIndexed { index, focus ->
                    db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,?,?,?,?,?,'FOCUS')",
                        arrayOf<Any>(account, chapterId, focus.id, index * 2, focus.zh, focus.en))
                    // Upstream already-accepted facts only: not exercised as AI judgments here.
                    db.execSQL("insert into writing_result_drafts values(?,?,?,'WHOLE_WRITING','',1)", arrayOf(account, chapterId, focus.id))
                    db.execSQL("insert into full_speaking_result_drafts values(?,?,?,1)", arrayOf(account, chapterId, focus.id))
                }
                db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,?,?,1,'然后我回家了。','Then I went home.','CONTEXT')",
                    arrayOf(account, chapterId, contextSentenceId))
                db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,?,?,0,?,?,'FOCUS')",
                    arrayOf(account, nextChapterId, nextSentenceId, nextChinese, nextEnglish))
                db.execSQL("insert into writing_batch_completions values(?,?,?,'INDEPENDENT')", arrayOf(account, chapterId, "accepted-write-$runId"))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        val cache = AndroidExplanationCache(context)
        val key = UUID.nameUUIDFromBytes("v1:1:sentence:".toByteArray(Charsets.UTF_8)).toString()
        focuses.forEach { focus -> cache.write(account, chapterId, focus.id, key,
            JSONObject().put("kind", "SENTENCE").put("explanationZh", "隔离已接受讲解：${focus.zh}").toString()) }
        cache.write(account, nextChapterId, nextSentenceId, key,
            JSONObject().put("kind", "SENTENCE").put("explanationZh", nextExplanation).toString())
        assertEquals(CheckpointSaveResult.Saved, AndroidLearningCheckpointRepository(context, runtime)
            .save(OrdinaryLearningCheckpoint(chapterId, "LR-09", focuses.first().id)))
    }

    private fun assertNoSceneOrArchiveWasSeeded() {
        listOf("scene_response_tasks", "scene_response_result_drafts", "chapter_archives", "learning_module_progress").forEach { assertEquals(0, count(it, chapterId)) }
        FormationDatabase(context).use { helper ->
            helper.readableDatabase.rawQuery("select count(*) from sentence_capabilities where account_id=?", arrayOf(account)).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
    }
    private fun assertSavedTasks() = FormationDatabase(context).use { helper ->
        helper.readableDatabase.rawQuery("select task_id,sentence_id,sentence_order,background_en,context_lines,target_sentence_en from scene_response_tasks where account_id=? and chapter_id=? order by sentence_order",
            arrayOf(account, chapterId)).use { cursor ->
            focuses.forEachIndexed { index, focus ->
                assertTrue(cursor.moveToNext()); assertEquals(focus.taskId, cursor.getString(0)); assertEquals(focus.id, cursor.getString(1))
                assertEquals(index, cursor.getInt(2)); assertEquals(focus.background.orEmpty(), cursor.getString(3))
                assertEquals(focus.lines.joinToString("\u001e") { "${it.first}\u001f${it.second}" }, cursor.getString(4))
                assertEquals(focus.en, cursor.getString(5))
            }
            assertFalse(cursor.moveToNext())
        }
    }
    private fun sceneDecisions(): Map<String, Int> = FormationDatabase(context).use { helper ->
        helper.readableDatabase.rawQuery("select sentence_id,passed from scene_response_result_drafts where account_id=? and chapter_id=?", arrayOf(account, chapterId)).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1)) }
        }
    }
    private fun count(table: String, chapter: String): Int = FormationDatabase(context).use { helper ->
        check(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
        helper.readableDatabase.rawQuery("select count(*) from $table where account_id=? and chapter_id=?", arrayOf(account, chapter)).use { check(it.moveToFirst()); it.getInt(0) }
    }
    private fun lateArchiveFailureTrigger(): String {
        val databaseFile = context.getDatabasePath("real-language-learning.db").canonicalFile
        check(databaseFile.parentFile == base.cacheDir.canonicalFile)
        check(databaseFile.name.startsWith("scene-archive-journey-$runId-") && databaseFile.name.endsWith(".db"))
        check(UUID.fromString(runId).toString() == runId)
        check(account == "scene-archive-journey-$runId" && chapterId == "first-chapter-$runId")
        check(account.matches(Regex("[a-z0-9-]+")) && chapterId.matches(Regex("[a-z0-9-]+")))
        return "test_scene_archive_failure_${runId.replace('-', '_')}".also {
            check(it.matches(Regex("test_scene_archive_failure_[a-f0-9_]+")))
        }
    }
    private fun installLateArchiveFailure() {
        val trigger = lateArchiveFailureTrigger()
        FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            db.rawQuery("select count(*) from sqlite_master where type='trigger' and name=?", arrayOf(trigger)).use {
                check(it.moveToFirst()); check(it.getInt(0) == 0)
            }
            // Only this private UUID account/chapter can fail. The predicate intentionally
            // requires all six in-transaction capabilities: failure must occur after their
            // settlement, not at the initial completeness gate. RAISE rolls them all back.
            db.execSQL("""
                create trigger $trigger before insert on chapter_archives
                when NEW.account_id='$account' and NEW.chapter_id='$chapterId'
                    and (select count(*) from sentence_capabilities p
                        join fixed_sentences s on s.account_id=p.account_id and s.unit_id=p.sentence_id
                        where p.account_id='$account' and s.chapter_id='$chapterId' and s.role='FOCUS'
                            and p.status='VERIFIED')=${focuses.size * 3}
                begin
                    select raise(ABORT,'isolated late scene archive failure');
                end
            """.trimIndent())
        }
    }
    private fun removeLateArchiveFailure() {
        val trigger = lateArchiveFailureTrigger()
        FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            db.rawQuery("select count(*) from sqlite_master where type='trigger' and name=?", arrayOf(trigger)).use {
                check(it.moveToFirst()); check(it.getInt(0) == 1)
            }
            db.execSQL("drop trigger $trigger")
            db.rawQuery("select count(*) from sqlite_master where type='trigger' and name=?", arrayOf(trigger)).use {
                check(it.moveToFirst()); check(it.getInt(0) == 0)
            }
        }
    }
    private fun assertLateArchiveRolledBack() = FormationDatabase(context).use { helper ->
        val db = helper.readableDatabase
        listOf("chapter_archives", "sentence_capabilities", "learning_obligations", "correction_flags",
            "normal_learning_days", "vocabulary_items", "pattern_items").forEach { table ->
            db.rawQuery("select count(*) from $table where account_id=?", arrayOf(account)).use {
                assertTrue(it.moveToFirst()); assertEquals("No partial formal archive may survive: $table", 0, it.getInt(0))
            }
        }
        db.rawQuery("select status from chapters where account_id=? and chapter_id=?", arrayOf(account, chapterId)).use {
            assertTrue(it.moveToFirst()); assertNotEquals("COMPLETED", it.getString(0)); assertFalse(it.moveToNext())
        }
    }
    private fun assertArchived() {
        FormationDatabase(context).use { helper ->
            val db = helper.readableDatabase
            db.rawQuery("select chapter_id,status from chapters where account_id=? and flow_id=? order by chapter_order", arrayOf(account, sourceId)).use {
                assertTrue(it.moveToNext()); assertEquals(chapterId, it.getString(0)); assertEquals("COMPLETED", it.getString(1))
                assertTrue(it.moveToNext()); assertEquals(nextChapterId, it.getString(0)); assertEquals("NOT_STARTED", it.getString(1)); assertFalse(it.moveToNext())
            }
            db.rawQuery("select chapter_id,summary from chapter_archives where account_id=?", arrayOf(account)).use {
                assertTrue(it.moveToFirst()); assertEquals(chapterId, it.getString(0))
                val summary = JSONObject(it.getString(1))
                assertEquals(chapterId, summary.getString("chapterId")); assertTrue(summary.getBoolean("hasNextChapter")); assertEquals(1, summary.getInt("chapterNumber"))
                assertFalse(it.moveToNext())
            }
            db.rawQuery("select sentence_id,capability_type,status from sentence_capabilities where account_id=?", arrayOf(account)).use { cursor ->
                val actual = buildSet { while (cursor.moveToNext()) add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))) }
                val expected = focuses.flatMap { focus -> listOf("FULL_WRITING", "FULL_SPEAKING", "SCENE_RESPONSE").map { Triple(focus.id, it, "VERIFIED") } }.toSet()
                assertEquals(expected, actual)
            }
            listOf("learning_obligations", "correction_flags", "normal_learning_days", "vocabulary_items", "pattern_items").forEach { table ->
                db.rawQuery("select count(*) from $table where account_id=?", arrayOf(account)).use { assertTrue(it.moveToFirst()); assertEquals("No invented mastery, failure or whole-source completion: $table", 0, it.getInt(0)) }
            }
        }
        listOf("learning_module_progress", "learning_checkpoints", "scene_response_result_drafts", "writing_result_drafts", "writing_batch_completions", "full_speaking_result_drafts", "learning_explanation_cache").forEach {
            assertEquals("Archived chapter working state must be removed: $it", 0, count(it, chapterId))
        }
        assertSavedTasks()
    }
    private fun archiveAndCapabilities() = allRows().filterKeys { it in setOf("chapter_archives", "sentence_capabilities", "scene_response_tasks", "learning_obligations", "correction_flags", "normal_learning_days") }
    private suspend fun assertOnlyNextIncomplete(runtime: SessionRuntime) {
        val loaded = AndroidIncompleteLearningRepository(context, runtime).loadOrdinaryLearning()
        assertTrue(loaded is IncompleteLearningLoadResult.Ready)
        val entries = (loaded as IncompleteLearningLoadResult.Ready).entries
        assertEquals(listOf(nextChapterId), entries.map { it.learningId })
        assertEquals("LR-02", entries.single().savedStepId)
        assertEquals(expectedNextCheckpoint().stepId, entries.single().checkpointStepId)
    }
    private fun expectedNextCheckpoint() = OrdinaryLearningCheckpoint(nextChapterId, "LR-02", "explanation:$nextSentenceId")
    private fun awaitCheckpoint(graph: ProductionAppGraph) = awaitIo {
        withTimeout(15_000) { while (graph.learningCheckpointRepository.read(nextChapterId) != expectedNextCheckpoint()) delay(20) }
    }
    private fun assertNextExplanation() {
        waitTag("explanation_content")
        waitTag("learning-focus-$nextSentenceId")
        compose.onNodeWithText(nextExplanation, substring = true).assertExists()
        compose.onNodeWithTag("learning-focus-${focuses.first().id}").assertDoesNotExist()
        compose.onNodeWithTag("learning-focus-${focuses.last().id}").assertDoesNotExist()
    }
    private fun awaitSceneProgress(runtime: SessionRuntime, ready: (Map<String, String>) -> Boolean): Map<String, String> = awaitIo {
        withTimeout(15_000) {
            var saved: Map<String, String>?
            do {
                saved = AndroidLearningProgressStore(context, runtime, chapterId, focuses.first().id, "LR-09").read()
                if (saved == null || !ready(saved)) delay(20)
            } while (saved == null || !ready(saved))
            requireNotNull(saved)
        }
    }
    private fun allRows(): Map<String, List<List<String?>>> = FormationDatabase(context).use { helper ->
        val db = helper.readableDatabase
        val names = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        names.associateWith { name ->
            require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            db.rawQuery("select rowid,* from $name order by rowid", null).use { cursor -> buildList {
                add(cursor.columnNames.toList())
                while (cursor.moveToNext()) add(List(cursor.columnCount) { index -> when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_BLOB -> "blob:" + Base64.getEncoder().encodeToString(cursor.getBlob(index))
                    else -> "${cursor.getType(index)}:${cursor.getString(index)}"
                } })
            } }
        }
    }
    private val permittedTables = setOf("chapters", "scene_response_tasks", "scene_response_result_drafts", "learning_module_progress", "learning_checkpoints",
        "learning_explanation_cache", "writing_result_drafts", "writing_batch_completions", "full_speaking_result_drafts", "chapter_archives", "sentence_capabilities")
    private fun openUnfinished(chapter: String) {
        waitTag("incomplete-learning-resume-$chapter")
        compose.onNodeWithTag("incomplete-learning-resume-$chapter").assertIsDisplayed().performClick()
    }
    /** Opt-in screenshots of the currently mounted production route, before UUID cleanup. */
    private fun captureJourneyFrame(state: String, visibleTag: String) {
        val resolution = InstrumentationRegistry.getArguments().getString("sceneJourneySnapshot") ?: return
        require(resolution in setOf("1672x941", "1691x930", "1832x859"))
        val phase = InstrumentationRegistry.getArguments().getString("sceneJourneySnapshotPhase")
        require(phase == null || phase in setOf("before", "after"))
        require(state.matches(Regex("[a-z0-9-]+")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.waitForIdle()
        val anchor = compose.onNodeWithTag(visibleTag).assertIsDisplayed()
        val before = anchor.fetchSemanticsNode().boundsInRoot
        val view = compose.activity.window.decorView
        val drawn = java.util.concurrent.CountDownLatch(1)
        val listener = android.view.ViewTreeObserver.OnDrawListener { drawn.countDown() }
        instrumentation.runOnMainSync {
            view.viewTreeObserver.addOnDrawListener(listener)
            view.postInvalidateOnAnimation()
        }
        try { check(drawn.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "The active production route did not redraw" } }
        finally { instrumentation.runOnMainSync {
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener)
        } }
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        assertEquals(before, anchor.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(resolution, "${bitmap.width}x${bitmap.height}")
            assertEquals(before, anchor.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
            val folder = File(base.getExternalFilesDir(null), "visual-qa")
            check(folder.isDirectory || folder.mkdirs())
            val output = File(folder, "scene-archive-$resolution-$state${phase?.let { "-$it" }.orEmpty()}.png")
            check(output.canonicalFile.parentFile == folder.canonicalFile)
            output.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
    private fun click(resource: Int) {
        val text = base.getString(resource); waitText(text)
        compose.onNodeWithText(text).assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun waitTag(tag: String) {
        compose.waitUntil(15_000) { assertFixtureHealthy(); compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun waitText(text: String) { compose.waitUntil(15_000) { assertFixtureHealthy(); compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun assertFixtureHealthy() { fixtureFailure.get()?.let { throw AssertionError("Isolated scene HTTP contract mismatch", it) } }
    private fun <T> awaitIo(block: suspend () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch { result.set(runCatching { block() }) }
        try {
            compose.waitUntil(20_000) { assertFixtureHealthy(); result.get() != null }
            return requireNotNull(result.get()).getOrElse { throw AssertionError("Isolated Graph persistence step failed", it) }
        }
        finally { job.cancel(); scope.cancel() }
    }
}
