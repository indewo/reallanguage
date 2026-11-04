package com.reallanguage.app.core.app

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
import com.reallanguage.app.feature.learning.data.AndroidLearningProgressStore
import com.reallanguage.app.feature.learning.data.AndroidSentenceSpeaker
import com.reallanguage.app.feature.learning.data.CapturedReadSpeakAudio
import com.reallanguage.app.feature.learning.data.ReadSpeakAudioCapture
import com.reallanguage.app.feature.learning.data.SentenceSpeechEngine
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.FixedSceneResponseTask
import com.reallanguage.app.feature.learning.domain.SceneDialogueLine
import com.reallanguage.app.feature.learning.presentation.SceneResponseUiState
import com.reallanguage.app.feature.learning.presentation.encodeSceneProgress
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.util.Base64
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Actual Home/Graph, repositories, codecs and private SQLite; transport/capture/speech are doubles.
 * The fixed chapter/target and Scene's earlier rounds are fixtures, not a completed formation journey.
 * NOT_AUDIO bytes are not microphone, pronunciation, human acceptance, AI or OEM evidence. */
class ProductionLearningCaptureConnectionTest {
    private val recoveryKeys = com.reallanguage.app.testing.IsolatedFormationPayloadKeys()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "learning-capture-connection-${UUID.randomUUID()}"
    private val account = "owner-$prefix"
    private val chapterId = "chapter-$prefix"
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
    private val runtime = SessionRuntime().apply { update("ISOLATED_ACCESS", account) }
    private val credentials = MemoryCredentials(StoredSession(account, "ISOLATED_REFRESH", true))
    private val chapter = FixedLearningChapter(chapterId, 1,
        listOf(FixedLearningSentence("s1", ENGLISH, "我喝绿茶。", true)))
    private val task = FixedSceneResponseTask("t1", "s1", "Talk about your drink.",
        listOf(SceneDialogueLine("AI", "What do you drink?")), ENGLISH)
    private val host = mutableStateOf<ProductionAppGraph?>(null)
    private val calls = CopyOnWriteArrayList<String>()
    private val failure = AtomicReference<Throwable?>(null)
    private val active = AtomicBoolean()
    private val starts = AtomicInteger()
    private val finishes = AtomicInteger()
    private val liveCancels = AtomicInteger()
    private val captureCreations = AtomicInteger()
    private val original = AtomicReference<File?>(null)
    private val pending = CompletableDeferred<HttpResponse>()
    private lateinit var module: Module
    private lateinit var graph: ProductionAppGraph
    private var submitted = false
    private var mounted = false
    private var retryFeedback = false
    private var allowExplicitSecondRecording = false
    private val feedbackRequests = CopyOnWriteArrayList<HttpRequest>()

    @Before fun prepareWindowAndPrivateDirectory() {
        assertEquals("Root owns device permission; this test never changes it", PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(base, Manifest.permission.RECORD_AUDIO))
        check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && directory.mkdir())
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @After fun cleanOnlyThisFixture() {
        pending.complete(HttpResponse(503, "{}"))
        if (mounted) {
            compose.runOnIdle { host.value = null; compose.activity.viewModelStore.clear() }
            compose.waitForIdle()
        }
        val database = privateDb.getDatabasePath(DATABASE).canonicalFile
        check(database.parentFile == base.cacheDir.canonicalFile && database.name.startsWith("$prefix-"))
        check(privateDb.deleteDatabase(DATABASE) || !database.exists())
        synchronized(preferences) { preferences.toList() }.forEach { name ->
            check(name.startsWith("$prefix-")); base.deleteSharedPreferences(name)
        }
        check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && directory.name == prefix)
        check(!directory.exists() || directory.deleteRecursively())
        awaitIo {
            check(account == "owner-$prefix")
            com.reallanguage.app.feature.learning.data.AndroidReadSpeakPayloadKeys(canUseAccount = { true })
                .deleteAccount(com.reallanguage.app.feature.formation.data.fixedSourceAccountHash(account))
        }
    }

    @Test fun readSpeakLiveCaptureStopsUnderCoverAndOnlyExplicitRetryRestarts() = live(Module.READ)
    @Test fun patternLiveCaptureStopsUnderCoverWithoutDiscardingItsAcceptedExamples() = live(Module.PATTERN)
    @Test fun sceneLiveCaptureStopsUnderCoverWithoutAdvancingItsRound() = live(Module.SCENE)
    @Test fun readSpeakSubmittedFeedbackPersistsUnderCoverAndNewGraphDoesNotRepeatHttp() = delivered(Module.READ)
    @Test fun patternSubmittedReadingPersistsUnderCoverAndNewGraphDoesNotRegenerate() = delivered(Module.PATTERN)
    @Test fun sceneSubmittedFinalCandidatePersistsUnderCoverAndNewGraphDoesNotReevaluate() = delivered(Module.SCENE)

    @Test fun patternUnknownReadingHasAnActualRetryEntryAndSavesTheOriginalAttempt() =
        retryOriginalFeedback(Module.PATTERN, "patternRetryScreenshot")

    @Test fun sceneUnknownFinalResponseHasAnActualRetryEntryAndSavesTheOriginalAttempt() =
        retryOriginalFeedback(Module.SCENE, "sceneRetryScreenshot")

    @Test fun readUnknownFeedbackSurvivesReentryAndRetriesOriginalUserFinishedAudio() {
        retryFeedback = true
        mount(Module.READ, awaitingResult = true)
        val protectedRows = awaitIo { productRows() }.filterKeys { it !in mutableTables() }
        startCapture()
        compose.waitUntil(5_000) { active.get() }
        tapText(R.string.read_speak_manual_finished)
        compose.waitUntil(5_000) { feedbackRequests.size == 1 }
        pending.complete(HttpResponse(503, "{}"))
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText(base.getString(R.string.read_speak_retry)).assertIsEnabled(); true }.getOrDefault(false)
        }
        screenshotIfRequested("readRetryBeforeScreenshot")
        compose.runOnIdle { host.value = null }
        compose.waitForIdle()
        graph = newGraph()
        compose.runOnIdle { host.value = graph }
        waitReady()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText(base.getString(R.string.read_speak_retry)).assertIsEnabled(); true }.getOrDefault(false)
        }
        assertEquals(1, feedbackRequests.size)
        tapText(R.string.read_speak_retry)
        compose.waitUntil(5_000) { healthy(); starts.get() > 1 || feedbackRequests.size == 2 }
        assertEquals("Entry recovery must not restart the microphone", 1, starts.get())
        assertEquals(1, finishes.get())
        assertEquals(feedbackRequests[0].body, feedbackRequests[1].body)
        assertEquals("MANUAL_CONFIRMATION", JSONObject(feedbackRequests[1].body).getString("completionSignal"))
        val saved = awaitIo { withTimeout(10_000) {
            var value: Map<String, String>?
            do { value = progress().read(); if (!isDelivered(value)) delay(20) } while (!isDelivered(value))
            requireNotNull(value)
        } }
        assertDeliveredUi()
        compose.onNodeWithText(base.getString(R.string.read_speak_continue)).assertExists().assertIsEnabled()
        assertLocalPosition(saved, delivered = true)
        assertEquals(protectedRows, awaitIo { productRows() }.filterKeys { it !in mutableTables() })
        screenshotIfRequested("readRetryAfterScreenshot")
        healthy()
    }

    @Test fun readTerminalFailureRequiresExplicitNewRecordingAfterReentry() = terminalFailureRecovery(newRecording = true)

    @Test fun readTerminalFailureStillAllowsOnlyUserConfirmedTrainingWithoutAudio() = terminalFailureRecovery(newRecording = false)

    private fun terminalFailureRecovery(newRecording: Boolean) {
        retryFeedback = true
        allowExplicitSecondRecording = true
        mount(Module.READ, awaitingResult = true)
        val protectedRows = awaitIo { productRows() }.filterKeys { it !in mutableTables() }
        startCapture()
        compose.waitUntil(5_000) { active.get() }
        tapText(R.string.read_speak_manual_finished)
        compose.waitUntil(5_000) { feedbackRequests.size == 1 }
        pending.complete(HttpResponse(422, "{}"))
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("read_speak_manual_finished").assertIsEnabled(); true }.getOrDefault(false)
        }
        screenshotIfRequested("readTerminalBeforeScreenshot")
        compose.onNodeWithText(base.getString(R.string.full_sentence_speaking_retry_blocked)).assertIsDisplayed()
        compose.onNodeWithText(base.getString(R.string.read_speak_retry)).assertDoesNotExist()
        assertFalse(requireNotNull(original.get()).exists())
        compose.runOnIdle { host.value = null }
        compose.waitForIdle()
        graph = newGraph()
        compose.runOnIdle { host.value = graph }
        waitReady()
        compose.onNodeWithText(base.getString(R.string.full_sentence_speaking_retry_blocked)).assertIsDisplayed()
        compose.onNodeWithText(base.getString(R.string.read_speak_retry)).assertDoesNotExist()
        assertEquals(1, starts.get()); assertEquals(1, finishes.get()); assertEquals(1, feedbackRequests.size)
        assertFalse(active.get())
        assertTrue("Rejected feedback must not create a training result", awaitIo { productRows() }.getValue("learning_steps").isEmpty())
        if (newRecording) screenshotIfRequested("readTerminalAfterScreenshot")
        if (!newRecording) {
            tapText(R.string.read_speak_manual_finished)
            val saved = awaitIo { withTimeout(10_000) {
                var value: Map<String, String>?
                do { value = progress().read(); if (value?.get("completed") != "true") delay(20) } while (value?.get("completed") != "true")
                requireNotNull(value)
            } }
            assertEquals("", saved["feedbackZh"])
            compose.onNodeWithText("本句已由你确认读完。本次无法提供发音反馈。").assertDoesNotExist()
            compose.onNodeWithText(base.getString(R.string.read_speak_continue)).assertIsEnabled()
            assertEquals(1, starts.get()); assertEquals(1, finishes.get()); assertEquals(1, feedbackRequests.size)
            assertLocalPosition(saved, delivered = true)
            assertEquals(protectedRows, awaitIo { productRows() }.filterKeys { it !in mutableTables() })
            healthy()
            return
        }
        tapText(R.string.full_sentence_speaking_new_recording)
        compose.waitUntil(5_000) { active.get() }
        assertEquals(2, starts.get())
        assertEquals("Starting capture alone must not submit a new paid request", 1, feedbackRequests.size)
        tapText(R.string.read_speak_manual_finished)
        compose.waitUntil(5_000) { healthy(); feedbackRequests.size == 2 }
        assertEquals(2, finishes.get())
        assertNotEquals(JSONObject(feedbackRequests[0].body).getString("commandId"),
            JSONObject(feedbackRequests[1].body).getString("commandId"))
        val saved = awaitIo { withTimeout(10_000) {
            var value: Map<String, String>?
            do { value = progress().read(); if (!isDelivered(value)) delay(20) } while (!isDelivered(value))
            requireNotNull(value)
        } }
        assertDeliveredUi()
        assertLocalPosition(saved, delivered = true)
        assertEquals(protectedRows, awaitIo { productRows() }.filterKeys { it !in mutableTables() })
        healthy()
    }

    private fun retryOriginalFeedback(selected: Module, screenshotArgument: String) {
        retryFeedback = true
        mount(selected, awaitingResult = true)
        val protectedRows = awaitIo { productRows() }.filterKeys { it !in mutableTables() }
        startCapture()
        compose.waitUntil(5_000) { active.get() }
        tapText(R.string.pattern_speaking_response_finished)
        if (module == Module.SCENE) tapText(R.string.scene_response_confirm_transcript)
        compose.waitUntil(5_000) { feedbackRequests.size == 1 }
        pending.complete(HttpResponse(503, "{}"))
        compose.waitUntil(5_000) {
            if (module == Module.SCENE) {
                listOf(R.string.scene_response_inconclusive, R.string.learning_audio_retry_pending).any { label ->
                    compose.onAllNodesWithText(base.getString(label)).fetchSemanticsNodes().isNotEmpty()
                }
            } else listOf(R.string.pattern_speaking_read_once, R.string.read_speak_retry).any { label ->
                runCatching { compose.onNodeWithText(base.getString(label)).assertIsEnabled(); true }.getOrDefault(false)
            }
        }
        compose.waitForIdle()
        screenshotIfRequested(screenshotArgument)
        compose.onNodeWithText(base.getString(R.string.learning_audio_retry_pending)).assertExists()
        assertTrue(requireNotNull(original.get()).isFile)
        tapText(R.string.read_speak_retry, firstMatch = module == Module.SCENE)
        compose.waitUntil(5_000) { healthy(); feedbackRequests.size == 2 }
        val saved = awaitIo { withTimeout(10_000) {
            var value: Map<String, String>?
            do { value = progress().read(); if (!isDelivered(value)) delay(20) } while (!isDelivered(value))
            requireNotNull(value)
        } }
        assertDeliveredUi()
        assertEquals(feedbackRequests[0].body, feedbackRequests[1].body)
        assertEquals(1, starts.get())
        assertEquals(1, finishes.get())
        assertFalse(requireNotNull(original.get()).exists())
        assertLocalPosition(saved, delivered = true)
        compose.runOnIdle { host.value = null }
        compose.waitForIdle()
        graph = newGraph()
        compose.runOnIdle { host.value = graph }
        waitReady()
        assertDeliveredUi()
        assertEquals(saved, awaitIo { progress().read() })
        assertEquals(initialCalls() + (if (module == Module.SCENE) listOf(TRANSCRIBE) else emptyList()) +
            listOf(module.feedbackCall, module.feedbackCall), calls.toList())
        assertEquals(protectedRows, awaitIo { productRows() }.filterKeys { it !in mutableTables() })
        healthy()
    }

    private fun screenshotIfRequested(argument: String) {
        val name = InstrumentationRegistry.getArguments().getString(argument) ?: return
        require(name.matches(Regex("[a-zA-Z0-9_.-]+\\.png")))
        val view = compose.activity.window.decorView
        val drawn = AtomicBoolean()
        val listener = android.view.ViewTreeObserver.OnDrawListener { drawn.set(true) }
        compose.activityRule.scenario.onActivity {
            view.viewTreeObserver.addOnDrawListener(listener)
            view.postInvalidateOnAnimation()
        }
        try { compose.waitUntil(5_000) { drawn.get() } }
        finally { compose.activityRule.scenario.onActivity {
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener)
        } }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(200, 5_000)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { File(base.cacheDir, name).outputStream().use { check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
    }

    private fun live(selected: Module) {
        mount(selected, awaitingResult = false)
        val initialCalls = calls.toList()
        val protectedRows = awaitIo { productRows() }.filterKeys { it !in mutableTables() }
        startCapture()
        compose.waitUntil(5_000) { active.get() }
        loseConnection()
        compose.waitUntil(5_000) { !active.get() }
        assertEquals(1, liveCancels.get())
        assertEquals(0, finishes.get())
        assertEquals(initialCalls, calls.toList())
        reconnect()
        assertFalse(active.get())
        assertEquals("Reconnect is not permission to restart capture", 1, starts.get())
        assertEquals(initialCalls + listOf(HEALTH, REFRESH), calls.toList())
        assertLocalPosition(requireNotNull(awaitIo { progress().read() }), delivered = false)
        // Both existing Scene retry buttons dispatch the same reset; neither starts recording.
        if (module == Module.SCENE) tapText(R.string.read_speak_retry, firstMatch = true)
        tapText(if (module == Module.READ) R.string.read_speak_retry else module.startLabel)
        compose.waitUntil(5_000) { active.get() }
        assertEquals(2, starts.get())
        assertEquals(0, finishes.get())
        assertEquals(initialCalls + listOf(HEALTH, REFRESH), calls.toList())
        assertEquals(protectedRows, awaitIo { productRows() }.filterKeys { it !in mutableTables() })
        healthy()
    }

    private fun delivered(selected: Module) {
        mount(selected, awaitingResult = true)
        val protectedRows = awaitIo { productRows() }.filterKeys { it !in mutableTables() }
        startCapture()
        compose.waitUntil(5_000) { active.get() }
        tapText(if (module == Module.READ) R.string.read_speak_manual_finished else R.string.pattern_speaking_response_finished)
        if (module == Module.SCENE) tapText(R.string.scene_response_confirm_transcript)
        compose.waitUntil(5_000) { calls.contains(module.feedbackCall) }
        assertEquals(1, finishes.get())
        assertFalse(active.get())
        assertTrue(requireNotNull(original.get()).isFile)
        loseConnection()
        assertEquals("An already-issued request is not a live microphone", 0, liveCancels.get())
        assertTrue("The cover must preserve in-flight evidence", requireNotNull(original.get()).isFile)
        pending.complete(HttpResponse(200, feedbackJson().toString()))
        val saved = awaitIo { withTimeout(10_000) {
            var value: Map<String, String>?
            do { value = progress().read(); if (!isDelivered(value)) delay(20) } while (!isDelivered(value))
            requireNotNull(value)
        } }
        assertEquals(FEEDBACK, saved[if (module == Module.READ) "feedbackZh" else "feedback"])
        if (module == Module.SCENE) assertEquals(FEEDBACK, saved["candidateFeedback"])
        assertLocalPosition(saved, delivered = true)
        compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
        compose.onAllNodesWithText(FEEDBACK).assertCountEquals(0)
        assertFalse(requireNotNull(original.get()).exists())
        val expected = initialCalls() + (if (module == Module.SCENE) listOf(TRANSCRIBE) else emptyList()) + module.feedbackCall
        assertEquals(expected, calls.toList())
        reconnect()
        assertDeliveredUi()
        assertEquals(saved, awaitIo { progress().read() })
        compose.runOnIdle { host.value = null }
        compose.waitForIdle()
        graph = newGraph()
        compose.runOnIdle { host.value = graph }
        waitReady()
        assertDeliveredUi()
        assertEquals(saved, awaitIo { progress().read() })
        assertEquals(expected + listOf(HEALTH, REFRESH), calls.toList())
        assertEquals(1, starts.get())
        assertEquals(1, finishes.get())
        assertEquals(0, liveCancels.get())
        assertEquals(2, captureCreations.get())
        assertEquals(protectedRows, awaitIo { productRows() }.filterKeys { it !in mutableTables() })
        healthy()
    }

    private fun mount(selected: Module, awaitingResult: Boolean) {
        module = selected
        submitted = awaitingResult
        awaitIo { seed() }
        graph = newGraph()
        host.value = graph
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalActivityResultRegistryOwner provides compose.activity,
                LocalSentenceSpeakerFactory provides { _, allowed -> AndroidSentenceSpeaker(SilentSpeech(), allowed) },
            ) {
                RlTheme { RlPaperBackground { host.value?.let { current -> key(current) {
                    HomeProductionFlow(current, initialRoute = route())
                } } } }
            }
        }
        mounted = true
        waitReady()
        assertEquals(1, captureCreations.get())
        assertEquals(initialCalls(), calls.toList())
    }

    private fun route(): HomeDiaryRoute = when (module) {
        Module.READ -> HomeDiaryRoute.ReadSpeak(chapter, "s1", "隔离既有讲解", emptyList())
        Module.PATTERN -> HomeDiaryRoute.PatternSpeaking(chapter, "s1", "隔离既有讲解", emptyList())
        Module.SCENE -> HomeDiaryRoute.SceneResponse(chapter, "s1", "隔离既有讲解", emptyList())
    }
    private fun newGraph() = ProductionAppGraph(context, runtime, HttpTransport { request ->
        try { respond(request) } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Throwable) { failure.compareAndSet(null, problem); HttpResponse(500, "{}") }
    }, credentials, patternSpeakingCaptureFactory = { _, owner -> newCapture(owner) },
        sceneResponseCaptureFactory = { _, owner -> newCapture(owner) },
        readSpeakCaptureFactory = { _, owner -> newCapture(owner) }, patternSpeakingRecoveryKeys = recoveryKeys,
        sceneResponseRecoveryKeys = recoveryKeys)

    private fun newCapture(owner: SessionRuntime): ReadSpeakAudioCapture {
        check(owner === runtime && captureCreations.incrementAndGet() in 1..2)
        return object : ReadSpeakAudioCapture {
            override fun start(): Boolean { check(active.compareAndSet(false, true)); starts.incrementAndGet(); return true }
            override fun finish(): CapturedReadSpeakAudio {
                check(active.compareAndSet(true, false) && finishes.incrementAndGet() in 1..(if (allowExplicitSecondRecording) 2 else 1))
                val file = File(context.cacheDir, "synthetic-attempt.test")
                check(!file.exists()); file.writeBytes(syntheticAudio()); original.set(file)
                return CapturedReadSpeakAudio(file)
            }
            override fun cancel() { if (active.compareAndSet(true, false)) liveCancels.incrementAndGet() }
            override suspend fun awaitSpeechEnd(): Boolean = CompletableDeferred<Boolean>().await()
        }
    }

    private suspend fun seed() {
        FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("insert into formation_flows values(?,?,'FIXED','DIARY','隔离录音连接')", arrayOf(account, prefix))
            db.execSQL("insert into chapters values(?,?,?,0,'IN_PROGRESS')", arrayOf(account, prefix, chapterId))
            db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,?,'s1',0,'我喝绿茶。',?,'FOCUS')", arrayOf(account, chapterId, ENGLISH))
            if (module == Module.PATTERN) {
                val surface = ENGLISH.substring(2, 17)
                val evidence = JSONObject().put("version", 1).put("startUtf16", 2).put("endUtf16", 17)
                    .put("surfaceForm", surface).put("normalizedForm", STRUCTURE).put("allowedForms", JSONArray().put(surface))
                db.execSQL("insert into target_selections(account_id,chapter_id,target_id,unit_id,type,display,explanation_zh,structure_parts,related_vocabulary,source_evidence) values(?,?,'p1','s1','PATTERN',?,'说明喝的饮品',?,'',?)",
                    arrayOf(account, chapterId, STRUCTURE, "drink\u001fnoun", evidence.toString()))
            }
            if (module == Module.SCENE) db.execSQL(
                "insert into scene_response_tasks(account_id,chapter_id,task_id,sentence_id,sentence_order,background_en,context_lines,target_sentence_en) values(?,?,'t1','s1',0,?,?,?)",
                arrayOf(account, chapterId, task.backgroundEn, "AI\u001fWhat do you drink?", ENGLISH))
        }
        if (module == Module.SCENE) assertTrue(progress().write(encodeSceneProgress(
            SceneResponseUiState(chapter, listOf(task), roundIndex = if (submitted) 2 else 0, audioMuted = true), null)))
    }

    private suspend fun respond(request: HttpRequest): HttpResponse {
        val call = "${request.method} ${request.path}"
        val allowed = initialCalls() + listOf(HEALTH, REFRESH, module.feedbackCall) +
            (if (module == Module.SCENE) listOf(TRANSCRIBE) else emptyList())
        check(call in allowed)
        check(call !in calls || (retryFeedback && call == module.feedbackCall && feedbackRequests.size == 1))
        calls += call
        if (call == HEALTH) return HttpResponse(200, """{"status":"ok","service":"api","requestId":"isolated-capture"}""")
        if (call == REFRESH) return HttpResponse(200, """{"status":"AUTHENTICATED","accountId":"$account","accessToken":"ISOLATED_ROTATED_ACCESS","refreshToken":"ISOLATED_ROTATED_REFRESH","settingsCompleted":true}""")
        val json = JSONObject(request.body)
        check(json.getString("chapterId") == chapterId && json.getString("commandId").isNotBlank())
        if (call == PATTERN_GENERATE) {
            check(json.getString("patternId") == "p1" && json.getString("sourceSentenceId") == "s1")
            check(json.getString("sourceTextEn") == ENGLISH && json.getString("structure") == STRUCTURE)
            return HttpResponse(200, JSONObject().put("status", "READY").put("patternId", "p1")
                .put("examples", JSONArray().put(JSONObject().put("exampleId", "e1").put("sceneZh", "聊饮品")
                    .put("aiPromptEn", "What do you drink?").put("targetResponseEn", PATTERN_ENGLISH).put("targetResponseZh", "我喝红茶。"))).toString())
        }
        check(submitted && json.getString("audioMimeType") == "audio/mp4")
        check(Base64.getDecoder().decode(json.getString("audioBase64")).contentEquals(syntheticAudio()))
        if (module == Module.PATTERN) check(json.getString("patternId") == "p1" && json.getString("exampleId") == "e1" &&
            json.getString("mode") == "READING" && json.getString("targetTextEn") == PATTERN_ENGLISH)
        else check(json.getString("sentenceId") == "s1")
        if (call == TRANSCRIBE) return HttpResponse(200, JSONObject().put("transcript", ENGLISH)
            .put("lowConfidenceSegments", JSONArray()).put("confidence", 0.95).toString())
        if (module == Module.SCENE) check(json.getString("confirmedTranscript") == ENGLISH && json.getBoolean("userTranscriptConfirmed") && json.getInt("roundIndex") == 2)
        feedbackRequests += request
        if (retryFeedback && feedbackRequests.size == 2) return HttpResponse(200, feedbackJson().toString())
        return pending.await()
    }

    private fun feedbackJson() = JSONObject().put("feedbackZh", FEEDBACK).apply {
        when (module) {
            Module.READ -> { put("contentComplete", true); put("transcript", ENGLISH); put("feedbackConfidence", 0.95); put("action", "CONTINUE") }
            Module.PATTERN -> { put("status", "READY"); put("repeatRecommended", false); put("transcript", PATTERN_ENGLISH); put("confidence", 0.95) }
            Module.SCENE -> { put("decision", "INCONCLUSIVE"); put("correctTurn", false); put("targetSentenceComplete", false); put("pronunciationIntelligible", false); put("pronunciationConfidence", 0.4) }
        }
    }
    private fun isDelivered(value: Map<String, String>?): Boolean {
        if (value == null) return false
        return when (module) {
            Module.READ -> value["completed"] == "true" && value["feedbackZh"] == FEEDBACK
            Module.PATTERN -> value["example.0.read"] == "true" && value["feedback"] == FEEDBACK
            Module.SCENE -> value["candidateDecision"] == "INCONCLUSIVE" && value["candidateFeedback"] == FEEDBACK
        }
    }
    private fun initialCalls() = if (module == Module.PATTERN) listOf(PATTERN_GENERATE) else emptyList()
    private fun mutableTables() = setOf("learning_module_progress", "learning_checkpoints") +
        (if (module == Module.READ && submitted) setOf("learning_steps") else emptySet())
    private fun assertLocalPosition(saved: Map<String, String>, delivered: Boolean) {
        when (module) {
            Module.READ -> {
                assertEquals(delivered.toString(), saved["completed"])
                if (delivered) awaitIo { FormationDatabase(context).use { helper ->
                    helper.readableDatabase.rawQuery("select account_id,chapter_id,sentence_id,step,status,command_id from learning_steps", null).use {
                        assertTrue(it.moveToFirst())
                        assertEquals(listOf(account, chapterId, "s1", "READ_SPEAK", "COMPLETED"), List(5) { column -> it.getString(column) })
                        assertTrue(it.getString(5).isNotBlank())
                        assertFalse("Only this training row may be committed", it.moveToNext())
                    }
                } }
            }
            Module.PATTERN -> {
                assertEquals("p1", saved["pattern.0.id"])
                assertEquals("s1", saved["pattern.0.source"])
                assertEquals("READING", saved["stage"])
                assertEquals("0", saved["processedPatternCount"])
                assertEquals("false", saved["completed"])
                assertEquals("e1", saved["example.0.id"])
                assertEquals(PATTERN_ENGLISH, saved["example.0.en"])
                assertEquals(delivered.toString(), saved["example.0.read"])
            }
            Module.SCENE -> {
                assertEquals("t1", saved["task.0.id"])
                assertEquals("s1", saved["task.0.sentence"])
                assertEquals("0", saved["taskIndex"])
                assertEquals(if (delivered) "2" else "0", saved["roundIndex"])
                assertEquals("false", saved["completed"])
                assertEquals(if (delivered) "INCONCLUSIVE" else "", saved["finalDecision"])
                assertEquals(if (delivered) ENGLISH else "", saved["transcript"])
            }
        }
    }
    private fun progress() = AndroidLearningProgressStore(context, runtime, chapterId, "s1", module.id)
    private fun syntheticAudio() = "NOT_AUDIO:$prefix".toByteArray(Charsets.UTF_8)
    private fun waitReady() {
        compose.waitUntil(10_000) { healthy(); compose.onAllNodesWithTag(module.tag).fetchSemanticsNodes().isNotEmpty() }
        if (module == Module.PATTERN) compose.waitUntil(10_000) { healthy(); compose.onAllNodesWithText(PATTERN_ENGLISH).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun loseConnection() {
        compose.runOnIdle { graph.connectionGate.networkLost() }
        compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
        compose.onNodeWithTag(module.tag).assertDoesNotExist()
    }
    private fun reconnect() {
        val epoch = runtime.accountEpoch
        compose.onNodeWithTag("connection-error-reconnect").assertIsDisplayed().performTouchInput { click(center) }
        compose.waitUntil(5_000) { graph.connectionGate.incident.value == null }
        waitReady()
        assertEquals(account, runtime.accountId)
        assertEquals(epoch, runtime.accountEpoch)
    }
    private fun assertDeliveredUi() {
        val text = if (module == Module.PATTERN) base.getString(R.string.pattern_speaking_read_complete) else FEEDBACK
        compose.waitUntil(10_000) { healthy(); compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        if (module == Module.PATTERN) {
            // READING exposes completed reading, not the feedback panel used in RESPONDING.
            compose.onNodeWithText(text).assertExists().assertIsNotEnabled()
        } else compose.onNodeWithText(FEEDBACK).assertExists()
    }
    private fun tapText(resource: Int, firstMatch: Boolean = false) {
        val text = base.getString(resource)
        fun target() = if (firstMatch) compose.onAllNodesWithText(text).onFirst() else compose.onNodeWithText(text)
        compose.waitUntil(10_000) { healthy(); runCatching { target().assertIsEnabled(); true }.getOrDefault(false) }
        val node = target()
        if (runCatching { node.assertIsDisplayed() }.isFailure) node.performScrollTo()
        node.assertIsDisplayed().assertIsEnabled().performTouchInput { click(center) }
    }
    private fun startCapture() {
        if (module != Module.READ) {
            tapText(module.startLabel)
            return
        }
        compose.onNodeWithTag("learning-english-s1").assertIsDisplayed().performTouchInput {
            down(centerLeft + androidx.compose.ui.geometry.Offset(8f, 0f))
            moveTo(center, 400L)
            up()
        }
    }
    private fun productRows(): Map<String, List<List<String?>>> = FormationDatabase(context).use { helper ->
        val db = helper.readableDatabase
        val names = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        names.associateWith { name ->
            require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            db.rawQuery("select * from $name order by rowid", null).use { cursor -> buildList {
                while (cursor.moveToNext()) add(List(cursor.columnCount) { if (cursor.isNull(it)) null else cursor.getString(it) })
            } }
        }
    }
    private fun healthy() { failure.get()?.let { throw AssertionError("Isolated fixture contract failed", it) } }
    private fun <T> awaitIo(block: suspend () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch { result.set(runCatching { block() }) }
        try {
            compose.waitUntil(15_000) { healthy(); result.get() != null }
            return requireNotNull(result.get()).getOrThrow()
        } finally { job.cancel(); scope.cancel() }
    }
    private class MemoryCredentials(var value: StoredSession?) : SessionCredentialStore {
        override suspend fun load() = value
        override suspend fun save(session: StoredSession) { value = session }
        override suspend fun clear() { value = null }
    }
    private class SilentSpeech : SentenceSpeechEngine {
        override suspend fun awaitReady() = true
        override fun start(text: String, rate: Float, locale: Locale, finished: (Boolean) -> Unit): Boolean {
            finished(true)
            return true
        }
        override fun stop() = Unit
        override fun close() = Unit
    }
    private enum class Module(val id: String, val tag: String, val startLabel: Int, val feedbackCall: String) {
        READ("LR-03", "read_speak_status", R.string.read_speak_start, "POST /api/v1/learning/reading-feedback"),
        PATTERN("LR-07", "pattern-speaking-workspace", R.string.pattern_speaking_read_once, "POST /api/v1/learning/pattern-practice-feedback"),
        SCENE("LR-09", "scene-response-workspace", R.string.pattern_speaking_start_response, "POST /api/v1/learning/scene-response-evaluations"),
    }
    private companion object {
        const val DATABASE = "real-language-learning.db"
        const val ENGLISH = "I drink green tea."
        const val PATTERN_ENGLISH = "I drink black tea."
        const val STRUCTURE = "drink + noun"
        const val FEEDBACK = "隔离已交付结果：仅测试保存恢复。"
        const val HEALTH = "GET /api/v1/health"
        const val REFRESH = "POST /api/v1/auth/sessions/refresh"
        const val TRANSCRIBE = "POST /api/v1/learning/speaking-transcriptions"
        const val PATTERN_GENERATE = "POST /api/v1/learning/pattern-practice-sets"
    }
}
