package com.reallanguage.app.core.app

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Bitmap
import android.view.ViewTreeObserver
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
import com.reallanguage.app.feature.formation.data.fixedSourceAccountHash
import com.reallanguage.app.feature.learning.data.AndroidFullSpeakingPayloadKeys
import com.reallanguage.app.feature.learning.data.AndroidFullSpeakingRecoveryJournal
import com.reallanguage.app.feature.learning.data.AndroidLearningProgressStore
import com.reallanguage.app.feature.learning.data.AndroidSentenceSpeaker
import com.reallanguage.app.feature.learning.data.CapturedReadSpeakAudio
import com.reallanguage.app.feature.learning.data.FullSpeakingRecoveryLoad
import com.reallanguage.app.feature.learning.data.FullSpeakingRecoveryPhase
import com.reallanguage.app.feature.learning.data.ReadSpeakAudioCapture
import com.reallanguage.app.feature.learning.data.SentenceSpeechEngine
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual Home/Graph/route/codecs and UUID SQLite. Only transport, capture and speech are doubles.
 * The first three rounds and fixed sentences are seeded inputs, not a preceding-learning claim.
 * NOT_AUDIO bytes prove identity and lifecycle only; no real microphone, TTS, server or AI. */
class ProductionFullTrainingRetryJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "full-training-journey-${UUID.randomUUID()}"
    private val account = "owner-$prefix"
    private val otherAccount = "other-$prefix"
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
    private val chapter = FixedLearningChapter(chapterId, 1, listOf(
        FixedLearningSentence(SENTENCE, TEXT, "我喝绿茶。", true),
        FixedLearningSentence("other-sentence", "I take a walk.", "我去散步。", true)))
    private val host = mutableStateOf<ProductionAppGraph?>(null)
    private val requests = CopyOnWriteArrayList<HttpRequest>()
    private val fixtureFailure = AtomicReference<Throwable?>(null)
    private val starts = AtomicInteger()
    private val finishes = AtomicInteger()
    private val creations = AtomicInteger()
    private val active = AtomicBoolean()
    private val original = AtomicReference<File?>(null)
    private val originalSha = AtomicReference<String?>(null)
    private val speechStarts = AtomicInteger()
    private val credentialCalls = AtomicInteger()

    @Test(timeout = 120_000)
    fun fourthTrainingUnknownDeliveryRetriesThenRestoresFeedbackBeforeExplicitFifthRound() {
        assertEquals("Host owns permission; this fixture never changes system permission", PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(base, Manifest.permission.RECORD_AUDIO))
        check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && directory.mkdir())
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        val runtime = session()
        awaitIo { seed(runtime) }
        val protectedBefore = awaitIo { protectedRows() }
        val first = graph(runtime)
        host.value = first
        compose.setContent { CompositionLocalProvider(LocalContext provides context,
            LocalActivityResultRegistryOwner provides compose.activity,
            LocalSentenceSpeakerFactory provides { _, allowed -> AndroidSentenceSpeaker(SilentEngine(), allowed) }) {
            RlTheme { RlPaperBackground { host.value?.let { current -> key(current) {
                HomeProductionFlow(current, initialRoute = route())
            } } } }
        } }
        waitAttempt("英文朗读 · 第 4 次")
        tap(R.string.full_sentence_speaking_start_read)
        compose.waitUntil(10_000) { assertHealthy(); active.get() && starts.get() == 1 &&
            compose.onAllNodesWithTag("full-sentence-recording-status").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("full-sentence-recording-status").assertIsDisplayed()
        tap(R.string.full_sentence_speaking_read_finished)
        compose.waitUntil(10_000) {
            assertHealthy()
            listOf(R.string.full_sentence_speaking_retry_capture, R.string.learning_audio_retry_pending).any {
                compose.onAllNodesWithText(base.getString(it)).fetchSemanticsNodes().isNotEmpty()
            }
        }
        captureIfRequested("failed")
        compose.onNodeWithText(base.getString(R.string.learning_audio_retry_pending)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(base.getString(R.string.full_sentence_speaking_retry_capture)).assertDoesNotExist()
        assertTrue("Unknown response retains the submitted original", requireNotNull(original.get()).isFile)
        assertEquals(1, requests.size)
        val pending = awaitIo { journal(runtime).load() } as FullSpeakingRecoveryLoad.Loaded
        assertEquals(FullSpeakingRecoveryPhase.TrainingPending, pending.snapshot.phase)
        assertEquals(requests[0].body, pending.snapshot.requestBody)
        assertEquals(requireNotNull(originalSha.get()), sha(Base64.getDecoder().decode(
            JSONObject(requireNotNull(pending.snapshot.requestBody)).getString("audioBase64"))))
        assertEquals(command(0), JSONObject(requireNotNull(pending.snapshot.requestBody)).getString("commandId"))
        assertEquals(protectedBefore, awaitIo { protectedRows() })
        compose.onNodeWithText(base.getString(R.string.read_speak_retry)).assertIsDisplayed().assertIsEnabled()
        tap(R.string.read_speak_retry)
        waitText(FEEDBACK)
        compose.onNodeWithText(FEEDBACK).performScrollTo().assertIsDisplayed()
        val saved = awaitProgress(runtime, "3", trainingDone = true)
        assertEquals(FEEDBACK, saved["feedbackZh"])
        assertEquals("", saved["finalDecision"])
        assertFalse(saved.keys.any { it.startsWith("candidate") })
        assertEquals(2, requests.size)
        assertEquals("Actual Retry must reuse the complete original request", requests[0].body, requests[1].body)
        assertEquals(command(0), command(1))
        assertEquals(1, starts.get())
        assertEquals(1, finishes.get())
        assertFalse(requireNotNull(original.get()).exists())
        val delivered = awaitIo { journal(runtime).load() } as FullSpeakingRecoveryLoad.Loaded
        assertEquals(FullSpeakingRecoveryPhase.TrainingDelivered, delivered.snapshot.phase)
        assertEquals(pending.snapshot.recordingId, delivered.snapshot.recordingId)
        assertNull("Delivered feedback no longer retains the audio-bearing request", delivered.snapshot.requestBody)
        assertEquals(FEEDBACK, delivered.snapshot.training?.feedbackZh)
        assertEquals(protectedBefore, awaitIo { protectedRows() })

        compose.runOnIdle { host.value = null }
        compose.waitForIdle()
        val newRuntime = session()
        val reopened = graph(newRuntime)
        assertNotSame(first, reopened)
        assertNotSame(runtime, newRuntime)
        assertEquals(saved, awaitIo { progress(newRuntime).read() })
        compose.runOnIdle { host.value = reopened }
        waitAttempt("英文朗读 · 第 4 次")
        waitText(FEEDBACK)
        compose.onNodeWithText(FEEDBACK).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("full-sentence-recording-status").assertDoesNotExist()
        compose.onNodeWithText(base.getString(R.string.full_sentence_speaking_continue)).assertIsDisplayed().assertIsEnabled()
        assertEquals(saved, awaitIo { progress(newRuntime).read() })
        captureIfRequested("restored")
        tap(R.string.full_sentence_speaking_continue)
        waitAttempt("独立复述 · 第 5 次")
        val next = awaitProgress(newRuntime, "4", trainingDone = false)
        assertEquals("", next["feedbackZh"])
        assertEquals("", next["finalDecision"])
        assertEquals(OrdinaryLearningCheckpoint(chapterId, "LR-06", SENTENCE),
            awaitIo { reopened.learningCheckpointRepository.read(chapterId) })
        assertEquals("Reentry and Continue cannot generate or evaluate anything", 2, requests.size)
        assertEquals(2, creations.get())
        assertEquals(1, starts.get())
        assertEquals(1, finishes.get())
        assertEquals(0, speechStarts.get())
        assertEquals(0, credentialCalls.get())
        assertEquals("All formal results, fixed data, other sentences and accounts remain byte-exact",
            protectedBefore, awaitIo { protectedRows() })
        assertHealthy()
    }

    private fun graph(runtime: SessionRuntime) = ProductionAppGraph(context, runtime, HttpTransport { request ->
        try {
            check(request.method == "POST" && request.path == "/api/v1/learning/reading-feedback")
            check(requests.size < 2)
            val body = JSONObject(requireNotNull(request.body))
            check(body.getString("chapterId") == chapterId && body.getString("sentenceId") == SENTENCE)
            check(body.getString("targetTextEn") == TEXT && body.getString("completionSignal") == "MANUAL_CONFIRMATION")
            check(body.getString("audioMimeType") == "audio/mp4" && body.getString("commandId").isNotBlank())
            check(Base64.getDecoder().decode(body.getString("audioBase64")).contentEquals(syntheticAudio()))
            requests += request
            if (requests.size == 1) HttpResponse(503, "{}") else HttpResponse(200, JSONObject()
                .put("feedbackZh", FEEDBACK).put("action", "CONTINUE").put("transcript", TEXT)
                .put("contentComplete", true).put("feedbackConfidence", .9).toString())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) { fixtureFailure.compareAndSet(null, failure); HttpResponse(500, "{}") }
    }, object : SessionCredentialStore {
        override suspend fun load(): StoredSession? { credentialCalls.incrementAndGet(); return null }
        override suspend fun save(session: StoredSession) { credentialCalls.incrementAndGet() }
        override suspend fun clear() { credentialCalls.incrementAndGet() }
    }, fullSentenceSpeakingCaptureFactory = { _, owner ->
        check(owner === runtime && creations.incrementAndGet() <= 2)
        object : ReadSpeakAudioCapture {
            override fun start(): Boolean { check(active.compareAndSet(false, true)); starts.incrementAndGet(); return true }
            override fun finish(): CapturedReadSpeakAudio? {
                finishes.incrementAndGet()
                if (!active.compareAndSet(true, false)) return null
                val file = File(context.cacheDir, "not-audio-training.test")
                check(!file.exists()); file.writeBytes(syntheticAudio()); original.set(file)
                originalSha.set(sha(file.readBytes()))
                return CapturedReadSpeakAudio(file)
            }
            override fun cancel() { active.set(false) }
        }
    })
    private suspend fun seed(runtime: SessionRuntime) {
        FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            for (owner in listOf(account, otherAccount)) {
                db.execSQL("insert into formation_flows values(?,?,'FIXED','DIARY','隔离训练重试')", arrayOf(owner, prefix))
                db.execSQL("insert into chapters values(?,?,?,0,'IN_PROGRESS')", arrayOf(owner, prefix, chapterId))
                chapter.sentences.forEachIndexed { index, sentence -> db.execSQL(
                    "insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,?,?,?,?,?,'FOCUS')",
                    arrayOf<Any>(owner, chapterId, sentence.sentenceId, index, sentence.textZh, sentence.textEn)) }
                db.execSQL("insert into learning_module_progress values(?,?,?,'LR-06',?)", arrayOf(owner, chapterId,
                    "other-sentence", JSONObject(mapOf("fixture" to "protected-$owner")).toString()))
            }
        }
        val initial = mapOf("version" to "1", "attemptIndex" to "3",
            "answerAudioPlayed" to "false", "transcript" to "", "feedbackZh" to "",
            "trainingCanContinue" to "false", "finalDecision" to "")
        assertTrue(progress(runtime).write(initial))
        val otherRuntime = SessionRuntime().apply { update("ISOLATED_OTHER_ACCESS", otherAccount) }
        assertTrue(progress(otherRuntime).write(initial + mapOf("attemptIndex" to "1", "feedbackZh" to "另一账号原有反馈")))
    }
    /** Exclude only this exact mutable progress/checkpoint, not either entire product table. */
    private fun protectedRows(): Map<String, List<List<String>>> = FormationDatabase(context).use { helper ->
        val db = helper.readableDatabase
        val names = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name", null)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        names.associateWith { name ->
            require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            db.rawQuery("select * from $name order by rowid", null).use { cursor -> buildList {
                while (cursor.moveToNext()) {
                    fun field(key: String) = cursor.getString(cursor.getColumnIndexOrThrow(key))
                    val mutable = name in setOf("learning_module_progress", "learning_checkpoints") &&
                        field("account_id") == account && field("chapter_id") == chapterId && field("module_id") == "LR-06" &&
                        field(if (name == "learning_module_progress") "sentence_id" else "step_id") == SENTENCE
                    if (!mutable) add(List(cursor.columnCount) { index -> when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> "null"
                        Cursor.FIELD_TYPE_BLOB -> "blob:${Base64.getEncoder().encodeToString(cursor.getBlob(index))}"
                        else -> "${cursor.getType(index)}:${cursor.getString(index)}"
                    } })
                }
            } }
        }
    }
    private fun session() = SessionRuntime().apply { update("ISOLATED_ACCESS", account) }
    private fun route() = HomeDiaryRoute.FullSentenceSpeaking(chapter, SENTENCE, "隔离既有讲解", emptyList())
    private fun progress(runtime: SessionRuntime) = AndroidLearningProgressStore(context, runtime, chapterId, SENTENCE, "LR-06")
    private fun journal(runtime: SessionRuntime) = AndroidFullSpeakingRecoveryJournal(context, runtime, chapterId, SENTENCE, TEXT, 3)
    private fun syntheticAudio() = "NOT_AUDIO:$prefix".toByteArray(Charsets.UTF_8)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun command(index: Int) = JSONObject(requireNotNull(requests[index].body)).getString("commandId")
    private fun awaitProgress(runtime: SessionRuntime, round: String, trainingDone: Boolean) = awaitIo { withTimeout(10_000) {
        var saved: Map<String, String>?
        do { saved = progress(runtime).read(); if (saved?.get("attemptIndex") != round || saved["trainingCanContinue"] != trainingDone.toString()) delay(20) }
        while (saved?.get("attemptIndex") != round || saved["trainingCanContinue"] != trainingDone.toString())
        requireNotNull(saved)
    } }
    private fun waitText(text: String) = compose.waitUntil(10_000) { assertHealthy(); compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun waitAttempt(text: String) { waitText(text); compose.onNodeWithTag("full-sentence-attempt").assertTextEquals(text) }
    private fun tap(resource: Int) {
        val text = base.getString(resource)
        compose.waitUntil(10_000) { assertHealthy()
            runCatching { compose.onNodeWithText(text).assertIsDisplayed().assertIsEnabled(); true }.getOrDefault(false) }
        compose.onNodeWithText(text).assertIsDisplayed().assertIsEnabled().performTouchInput { click(center) }
    }
    private fun assertHealthy() { fixtureFailure.get()?.let { throw AssertionError("Isolated training fixture mismatch", it) } }
    private fun <T> awaitIo(block: suspend () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch { result.set(runCatching { block() }) }
        try { compose.waitUntil(15_000) { assertHealthy(); result.get() != null }; return requireNotNull(result.get()).getOrThrow() }
        finally { job.cancel(); scope.cancel() }
    }
    private inner class SilentEngine : SentenceSpeechEngine {
        override suspend fun awaitReady() = true
        override fun start(text: String, rate: Float, locale: Locale, finished: (Boolean) -> Unit): Boolean { speechStarts.incrementAndGet(); return false }
        override fun stop() = Unit
        override fun close() = Unit
    }
    private fun captureIfRequested(stage: String) {
        val phase = InstrumentationRegistry.getArguments().getString("fullTrainingRetryCapture") ?: return
        require(phase in setOf("before", "after", "verified") && stage in setOf("failed", "restored"))
        val view = compose.activity.window.decorView
        val drawn = AtomicBoolean()
        val listener = ViewTreeObserver.OnDrawListener { drawn.set(true) }
        compose.activityRule.scenario.onActivity { view.viewTreeObserver.addOnDrawListener(listener); view.postInvalidateOnAnimation() }
        try { compose.waitUntil(5_000) { drawn.get() } }
        finally { compose.activityRule.scenario.onActivity { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener) } }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(200, 5_000)
        val destination = File(requireNotNull(base.getExternalFilesDir(null)), "27-full-training-retry-$phase-$stage.png")
        check(destination.createNewFile())
        val bitmap = requireNotNull(automation.takeScreenshot())
        try { destination.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    @After fun cleanupOnlyThisUuidFixture() {
        compose.runOnIdle { host.value = null; compose.activity.viewModelStore.clear() }
        compose.waitForIdle()
        check(account == "owner-$prefix")
        runBlocking(Dispatchers.IO) { AndroidFullSpeakingPayloadKeys().deleteAccount(fixedSourceAccountHash(account)) }
        val database = privateDb.getDatabasePath("real-language-learning.db").canonicalFile
        check(database.parentFile == base.cacheDir.canonicalFile && database.name.startsWith("$prefix-"))
        check(privateDb.deleteDatabase("real-language-learning.db") || !database.exists())
        synchronized(preferences) { preferences.toList() }.forEach { name -> check(name.startsWith("$prefix-")); base.deleteSharedPreferences(name) }
        check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && directory.name == prefix)
        check(!directory.exists() || directory.deleteRecursively())
    }
    private companion object {
        const val SENTENCE = "s1"
        const val TEXT = "I drink green tea."
        const val FEEDBACK = "隔离训练反馈：内容已收到，请注意连读。"
    }
}
