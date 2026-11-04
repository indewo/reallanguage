package com.reallanguage.app.core.app

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
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
 * Actual Graph/pages, real JSON codecs and real SQLite results. Only HTTP and capture are doubles.
 * Synthetic bytes are deliberately NOT sound: no microphone, provider or pronunciation-quality claim.
 * Fixed source/targets/explanations are preaccepted fixtures, not proof of preceding diary formation.
 * The host must preserve/restore RECORD_AUDIO permission externally; this test never changes it.
 */
class ProductionFullSpeakingModuleTransitionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val runId = UUID.randomUUID().toString()
    private val account = "full-speaking-transition-$runId"
    private val chapterId = "chapter-$runId"
    private val directory = File(base.cacheDir, "full-speaking-transition-$runId")
    private val preferences = Collections.synchronizedSet(mutableSetOf<String>())
    private val isolatedDb = IsolatedLearningDatabaseContext(base, "full-speaking-transition-$runId")
    private val context = object : ContextWrapper(isolatedDb) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = File(directory, "private")
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "full-speaking-transition-$runId-$name"
            preferences.add(isolatedName)
            return base.getSharedPreferences(isolatedName, mode)
        }
    }
    private data class Focus(val id: String, val en: String, val zh: String, val targetId: String,
        val structure: String, val meaning: String, val evidenceEnd: Int, val explanation: String)
    private val focuses = listOf(
        Focus("s1", "I drink green tea.", "我喝绿茶。", "pattern-first", "drink + object",
            "说明喝的东西", 17, "第一句只说明喝绿茶。"),
        Focus("s2", "I take a long walk.", "我长距离散步。", "pattern-second", "take a + noun",
            "说明进行的活动", 18, "第二句只说明长距离散步。"),
    )
    private val exampleEn = "I drink warm milk."
    private val exampleZh = "我喝温牛奶。"
    private val host = mutableStateOf<ProductionAppGraph?>(null)
    private var mounted = false
    private val requests = Collections.synchronizedList(mutableListOf<HttpRequest>())
    private val fixtureFailure = AtomicReference<Throwable?>(null)
    private val credentialsAccess = AtomicInteger()
    private val captureCreations = AtomicInteger()
    private val captureStarts = AtomicInteger()
    private val captureFinishes = AtomicInteger()
    private val attemptFiles = Collections.synchronizedList(mutableListOf<File>())
    private val transcribedAudio = mutableMapOf<String, String>()

    @After fun cleanOnlyThisUuidFixture() {
        if (mounted) {
            compose.runOnIdle { host.value = null }
            compose.waitForIdle()
        }
        runBlocking(Dispatchers.IO) {
            context.deleteDatabase("real-language-learning.db")
            synchronized(preferences) { preferences.toList() }.forEach { name ->
                check(name.startsWith("full-speaking-transition-$runId-"))
                base.deleteSharedPreferences(name)
            }
            check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            check(directory.name == "full-speaking-transition-$runId")
            check(!directory.exists() || directory.deleteRecursively())
        }
    }

    @Test(timeout = 180_000)
    fun twoFocusSpeakingRoundsPersistThenFirstPatternRestoresThroughANewGraph() = runJourney(false)

    @Test(timeout = 180_000)
    fun aSavedFirstDecisionRecoversItsFailedCheckpointWithoutRepeatingEvaluationOrResultSave() = runJourney(true)

    private fun runJourney(failFirstCompletedCheckpoint: Boolean) {
        assertEquals("Host must explicitly preserve/restore microphone permission; no test-side grant",
            PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(base, Manifest.permission.RECORD_AUDIO))
        val runtime = session()
        awaitIo { seedAcceptedFixedFacts(runtime) }
        awaitIo { installDecisionWriteAudit() }
        awaitIo { installProgressWriteAudit() }
        val before = awaitIo { allRows() }
        assertEquals(emptyMap<String, Int>(), awaitIo { decisions() })
        val firstGraph = graph(runtime)
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.activityRule.scenario.onActivity { configureFocusedLearningWindow(it.window) }
        host.value = firstGraph
        compose.setContent { RlTheme { RlPaperBackground {
            host.value?.let { current -> key(current) {
                HomeProductionFlow(current, initialRoute = HomeDiaryRoute.IncompleteLearning())
            } }
        } } }
        mounted = true
        openUnfinished()

        focuses.forEachIndexed { focusIndex, focus ->
            waitTag("full-sentence-workspace")
            waitTag("learning-focus-${focus.id}")
            assertEquals("Missing capture seam must stop the test before any start button", focusIndex + 1, captureCreations.get())
            repeat(4) { round ->
                waitAttempt(if (round < 2) "中英跟读 · 第 ${round + 1} 次" else "英文朗读 · 第 ${round + 1} 次")
                compose.onNodeWithTag("learning-focus-${focus.id}").assertExists()
                click(if (round < 2) R.string.full_sentence_speaking_start_follow else R.string.full_sentence_speaking_start_read)
                waitTag("full-sentence-recording-status")
                click(if (round < 2) R.string.full_sentence_speaking_follow_finished else R.string.full_sentence_speaking_read_finished)
                waitText(base.getString(R.string.full_sentence_speaking_continue))
                assertEquals("Training cannot write an independent result", focuses.take(focusIndex).associate { it.id to 1 }, awaitIo { decisions() })
                click(R.string.full_sentence_speaking_continue)
            }
            waitAttempt("独立复述 · 第 5 次")
            click(R.string.full_sentence_speaking_start_retell)
            waitTag("full-sentence-recording-status")
            click(R.string.full_sentence_speaking_retell_finished)
            waitText(base.getString(R.string.full_sentence_speaking_confirm_transcript))
            compose.onNode(hasSetTextAction()).performScrollTo().assertIsDisplayed().assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(focus.en)),
            )
            assertEquals("Transcription alone cannot save a judgment", focuses.take(focusIndex).associate { it.id to 1 }, awaitIo { decisions() })
            assertTrue("The same attempt's bytes must remain until explicit transcript confirmation",
                synchronized(attemptFiles) { attemptFiles.last().exists() })
            if (failFirstCompletedCheckpoint && focusIndex == 0) awaitIo { rejectFirstCompletedCheckpoint() }
            click(R.string.full_sentence_speaking_confirm_transcript)
            awaitIo { withTimeout(15_000) {
                while (decisions() != focuses.take(focusIndex + 1).associate { it.id to 1 }) delay(20)
            } }
            if (failFirstCompletedCheckpoint && focusIndex == 0) {
                val failureText = base.getString(R.string.full_sentence_speaking_progress_failed)
                waitText(failureText)
                compose.onNodeWithText(failureText).performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("learning-focus-s1").assertExists()
                compose.onNodeWithTag("learning-focus-s2").assertDoesNotExist()
                assertEquals("The accepted independent result is already durable", mapOf("s1" to 1), awaitIo { decisions() })
                assertEquals("Exactly one independent SQL result write precedes the local retry", listOf("s1" to 1), awaitIo { decisionWrites() })
                assertEquals("Four training, one transcription and one evaluation only", 6, synchronized(requests) { requests.size })
                val beforeRetry = requireNotNull(awaitIo { AndroidLearningProgressStore(context, runtime, chapterId, "s1", "LR-06").read() })
                assertNotEquals("The trigger must actually prevent durable completion", "PASS", beforeRetry["finalDecision"])
                assertEquals("The already delivered candidate must remain recoverable", "PASS", beforeRetry["candidateDecision"])
                assertEquals(OrdinaryLearningCheckpoint(chapterId, "LR-06", "s1"), awaitIo { firstGraph.learningCheckpointRepository.read(chapterId) })
                assertEquals(5, captureFinishes.get())
                assertFalse("A local progress retry must not require an expired recording", synchronized(attemptFiles) { attemptFiles.any(File::exists) })
                val retryText = base.getString(R.string.full_sentence_speaking_retry_save)
                waitText(retryText)
                compose.onNodeWithText(retryText).assertIsDisplayed().assertIsEnabled()
                awaitIo { removeFirstCompletedCheckpointFailure() }
                click(R.string.full_sentence_speaking_retry_save)
            }
            awaitIo { withTimeout(15_000) {
                while (AndroidLearningProgressStore(context, runtime, chapterId, focus.id, "LR-06").read()?.get("finalDecision") != "PASS") delay(20)
            } }
            assertEquals("Continuing or retrying progress must never rewrite an accepted result",
                focuses.take(focusIndex + 1).map { it.id to 1 }, awaitIo { decisionWrites() })
            if (failFirstCompletedCheckpoint && focusIndex == 0) {
                assertEquals("Local save recovery cannot repeat any HTTP", 6, synchronized(requests) { requests.size })
                assertEquals(5, captureFinishes.get())
            }
            assertFalse("Evaluated audio is temporary, not a permanent recording", synchronized(attemptFiles) { attemptFiles.any(File::exists) })
        }

        assertPatternFirstPage()
        val savedPattern = awaitIo { withTimeout(15_000) {
            var saved: Map<String, String>?
            do {
                saved = AndroidLearningProgressStore(context, runtime, chapterId, "s1", "LR-07").read()
                if (saved?.get("stage") != "READING" || saved?.get("example.0.en") != exampleEn) delay(20)
            } while (saved?.get("stage") != "READING" || saved?.get("example.0.en") != exampleEn)
            requireNotNull(saved)
        } }
        awaitPatternCheckpoint(firstGraph)
        awaitIo { assertPatternBinding(firstGraph) }
        assertEquals(10, captureStarts.get())
        assertEquals(10, captureFinishes.get())
        assertExactRequests()
        val completedProgressHistory = awaitIo { progressWrites() }
        assertForwardOnlySpeakingRounds(completedProgressHistory)
        val after = awaitIo { allRows() }
        val permittedWrites = setOf("learning_checkpoints", "learning_module_progress", "full_speaking_result_drafts",
            "test_lr06_decision_write_audit", "test_lr06_progress_write_audit")
        assertEquals("The journey must not alter fixed source, knowledge, mastery, writing or archive tables",
            before.filterKeys { it !in permittedWrites }, after.filterKeys { it !in permittedWrites })

        compose.runOnIdle { host.value = null }
        compose.waitForIdle()
        val newRuntime = session()
        val reopened = graph(newRuntime)
        assertNotSame(firstGraph, reopened)
        assertEquals(expectedCheckpoint(), awaitIo { reopened.learningCheckpointRepository.read(chapterId) })
        compose.runOnIdle { host.value = reopened }
        openUnfinished()
        assertPatternFirstPage()
        awaitPatternCheckpoint(reopened)
        awaitIo { assertPatternBinding(reopened) }
        assertEquals(savedPattern, awaitIo { AndroidLearningProgressStore(context, newRuntime, chapterId, "s1", "LR-07").read() })
        assertEquals(mapOf("s1" to 1, "s2" to 1), awaitIo { decisions() })
        assertEquals("Graph recreation must not resave either accepted result", listOf("s1" to 1, "s2" to 1), awaitIo { decisionWrites() })
        val restoredProgressHistory = awaitIo { progressWrites() }
        assertForwardOnlySpeakingRounds(restoredProgressHistory)
        assertEquals("Reopening LR07 must not rewrite completed LR06 progress", completedProgressHistory, restoredProgressHistory)
        assertExactRequests()
        assertEquals("Reentry must not recreate a completed speaking capture", 2, captureCreations.get())
        assertEquals(0, credentialsAccess.get())
        assertFalse(synchronized(attemptFiles) { attemptFiles.any(File::exists) })
        Log.i("RealLanguageJourney", "LR06_TWO_FOCUS_TO_LR07_FIRST run=$runId checkpointFailure=$failFirstCompletedCheckpoint graphRebuilt=true captures=10 fixtureHttp=13 savedOralRows=2 sqlResultWrites=2 realAi=0 realMicrophone=0")
    }

    private fun session() = SessionRuntime().apply { update("synthetic-session-no-real-credential", account) }
    private fun graph(runtime: SessionRuntime) = ProductionAppGraph(context, runtime, HttpTransport { request ->
        try { fixtureResponse(request) }
        catch (error: Throwable) {
            fixtureFailure.compareAndSet(null, error)
            HttpResponse(500, "{\"error\":\"isolated-fixture-contract-failed\"}")
        }
    }, object : SessionCredentialStore {
        override suspend fun load(): StoredSession? { credentialsAccess.incrementAndGet(); error("No credential read") }
        override suspend fun save(session: StoredSession) { credentialsAccess.incrementAndGet(); error("No credential save") }
        override suspend fun clear() { credentialsAccess.incrementAndGet(); error("No credential clear") }
    }, fullSentenceSpeakingCaptureFactory = { _, owner ->
        check(owner.accountId == account)
        val ordinal = captureCreations.incrementAndGet()
        check(ordinal in 1..2) { "Only two LR06 page captures belong to this test" }
        val focus = focuses[ordinal - 1]
        object : ReadSpeakAudioCapture {
            private var currentAttempt: Int? = null
            private var localAttempts = 0
            override fun start(): Boolean {
                check(currentAttempt == null && localAttempts < 5)
                currentAttempt = ++localAttempts
                captureStarts.incrementAndGet()
                return true
            }
            override fun finish(): CapturedReadSpeakAudio {
                val round = requireNotNull(currentAttempt)
                currentAttempt = null
                val file = File(context.cacheDir, "synthetic-${focus.id}-$round.test")
                check(!file.exists())
                file.writeBytes(syntheticBytes(focus.id, round))
                attemptFiles.add(file)
                captureFinishes.incrementAndGet()
                return CapturedReadSpeakAudio(file)
            }
            override fun cancel() { currentAttempt = null }
        }
    })

    @Synchronized private fun fixtureResponse(request: HttpRequest): HttpResponse {
        assertEquals("POST", request.method)
        val position = synchronized(requests) { requests.size }
        assertTrue("No extra generation or repeated evaluation allowed", position < 13)
        val body = JSONObject(requireNotNull(request.body))
        assertEquals(chapterId, body.getString("chapterId"))
        assertFalse(body.getString("commandId").isBlank())
        if (position == 12) {
            val first = focuses.first()
            assertEquals("/api/v1/learning/pattern-practice-sets", request.path)
            assertEquals(first.id, body.getString("sourceSentenceId"))
            assertEquals(first.en, body.getString("sourceTextEn"))
            assertEquals(first.zh, body.getString("sourceTextZh"))
            assertEquals(first.targetId, body.getString("patternId"))
            assertEquals(first.structure, body.getString("structure"))
            assertEquals(first.meaning, body.getString("explanationZh"))
            requests.add(request)
            return HttpResponse(200, JSONObject().put("status", "READY").put("patternId", first.targetId)
                .put("examples", JSONArray().put(JSONObject().put("exampleId", "first-example")
                    .put("sceneZh", "早餐时说明喝的东西").put("aiPromptEn", "What do you drink?")
                    .put("targetResponseEn", exampleEn).put("targetResponseZh", exampleZh))).toString())
        }
        val focus = focuses[position / 6]
        val step = position % 6
        assertEquals(focus.id, body.getString("sentenceId"))
        assertEquals("audio/mp4", body.getString("audioMimeType"))
        val audio = body.getString("audioBase64")
        assertArrayEquals(syntheticBytes(focus.id, if (step < 4) step + 1 else 5), Base64.getDecoder().decode(audio))
        val response = when {
            step < 4 -> {
                assertEquals("/api/v1/learning/reading-feedback", request.path)
                assertEquals(focus.en, body.getString("targetTextEn"))
                assertEquals("MANUAL_CONFIRMATION", body.getString("completionSignal"))
                JSONObject().put("feedbackZh", "隔离训练反馈，非真人发音评价。")
                    .put("action", "CONTINUE").put("transcript", focus.en)
                    .put("contentComplete", true).put("feedbackConfidence", 0.95)
            }
            step == 4 -> {
                assertEquals("/api/v1/learning/speaking-transcriptions", request.path)
                transcribedAudio[focus.id] = audio
                JSONObject().put("transcript", focus.en).put("lowConfidenceSegments", JSONArray())
                    .put("confidence", JSONObject.NULL)
            }
            else -> {
                assertEquals("/api/v1/learning/full-sentence-speaking-evaluations", request.path)
                assertEquals(focus.en, body.getString("targetTextEn"))
                assertEquals(focus.en, body.getString("confirmedTranscript"))
                assertEquals(transcribedAudio[focus.id], audio)
                assertTrue(body.getBoolean("targetHidden"))
                assertTrue(body.getBoolean("userTranscriptConfirmed"))
                assertFalse(body.getBoolean("answerAudioPlayed"))
                JSONObject().put("decision", "PASS").put("completenessScore", 95)
                    .put("coreMeaningRecognizable", true).put("pronunciationIntelligible", true)
                    .put("pronunciationConfidence", 0.95).put("feedbackZh", "隔离合同通过，非真实原音评分。")
            }
        }
        requests.add(request)
        return HttpResponse(200, response.toString())
    }

    private fun syntheticBytes(sentenceId: String, round: Int) =
        "NOT_AUDIO:isolated:$runId:$sentenceId:$round".toByteArray(Charsets.UTF_8)

    private fun assertExactRequests() {
        assertFixtureHealthy()
        val observed = synchronized(requests) { requests.toList() }
        assertEquals(13, observed.size)
        assertEquals(8, observed.count { it.path.endsWith("/reading-feedback") })
        assertEquals(2, observed.count { it.path.endsWith("/speaking-transcriptions") })
        assertEquals(2, observed.count { it.path.endsWith("/full-sentence-speaking-evaluations") })
        assertEquals(1, observed.count { it.path.endsWith("/pattern-practice-sets") })
        assertEquals(13, observed.map { JSONObject(requireNotNull(it.body)).getString("commandId") }.toSet().size)
    }

    private suspend fun seedAcceptedFixedFacts(runtime: SessionRuntime) {
        FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("insert into formation_flows values(?,?,'FIXED','DIARY','隔离整句衔接')", arrayOf(account, "flow-$runId"))
                db.execSQL("insert into chapters values(?,?,?,0,'NOT_STARTED')", arrayOf(account, "flow-$runId", chapterId))
                focuses.forEachIndexed { index, focus ->
                    db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,?,?,?,?,?,'FOCUS')",
                        arrayOf<Any>(account, chapterId, focus.id, index * 2, focus.zh, focus.en))
                    val surface = focus.en.substring(2, focus.evidenceEnd)
                    val evidence = JSONObject().put("version", 1).put("startUtf16", 2).put("endUtf16", focus.evidenceEnd)
                        .put("surfaceForm", surface).put("normalizedForm", focus.structure)
                        .put("allowedForms", JSONArray().put(surface)).toString()
                    db.execSQL("insert into target_selections(account_id,chapter_id,target_id,unit_id,type,display,explanation_zh,structure_parts,related_vocabulary,source_evidence) values(?,?,?,?,'PATTERN',?,?,?,'',?)",
                        arrayOf(account, chapterId, focus.targetId, focus.id, focus.structure, focus.meaning,
                            focus.structure.split(" + ").joinToString("\u001f"), evidence))
                }
                db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,?,'context',1,'然后我去外面。','Then I go outside.','CONTEXT')", arrayOf(account, chapterId))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        val key = UUID.nameUUIDFromBytes("v1:1:sentence:".toByteArray(Charsets.UTF_8)).toString()
        val cache = AndroidExplanationCache(context)
        focuses.forEach { focus -> cache.write(account, chapterId, focus.id, key,
            JSONObject().put("kind", "SENTENCE").put("explanationZh", focus.explanation).toString()) }
        assertEquals(CheckpointSaveResult.Saved, AndroidLearningCheckpointRepository(context, runtime)
            .save(OrdinaryLearningCheckpoint(chapterId, "LR-06", "s1")))
        // No LR06 progress, speech judgment or mastery rows are supplied by the fixture.
    }

    private fun decisions(): Map<String, Int> = FormationDatabase(context).use { helper ->
        helper.readableDatabase.rawQuery("select sentence_id,passed from full_speaking_result_drafts where account_id=? and chapter_id=? order by sentence_id",
            arrayOf(account, chapterId)).use { cursor -> buildMap {
            while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1))
        } }
    }

    /** Observes real result INSERTs without substituting the production repository or its save method. */
    private fun installDecisionWriteAudit() = FormationDatabase(context).use { helper ->
        val db = helper.writableDatabase
        require(account.matches(Regex("[a-z0-9-]+")) && chapterId.matches(Regex("[a-z0-9-]+")))
        db.execSQL("create table test_lr06_decision_write_audit(sentence_id text not null, passed integer not null)")
        db.execSQL("""
            create trigger test_lr06_audit_result_insert after insert on full_speaking_result_drafts
            when NEW.account_id='$account' and NEW.chapter_id='$chapterId'
            begin
                insert into test_lr06_decision_write_audit(sentence_id,passed) values(NEW.sentence_id,NEW.passed);
            end
        """.trimIndent())
    }

    private fun decisionWrites(): List<Pair<String, Int>> = FormationDatabase(context).use { helper ->
        helper.readableDatabase.rawQuery("select sentence_id,passed from test_lr06_decision_write_audit order by rowid", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getInt(1)) }
        }
    }

    private data class ProgressWrite(val sequence: Long, val sentenceId: String, val payload: String)

    /** The audit lives only in this UUID database and rolls back with any rejected product write. */
    private fun installProgressWriteAudit() = FormationDatabase(context).use { helper ->
        val db = helper.writableDatabase
        require(account.matches(Regex("[a-z0-9-]+")) && chapterId.matches(Regex("[a-z0-9-]+")))
        db.execSQL("create table test_lr06_progress_write_audit(sequence integer primary key, sentence_id text not null, payload text not null)")
        db.execSQL("""
            create trigger test_lr06_audit_progress_insert after insert on learning_module_progress
            when NEW.account_id='$account' and NEW.chapter_id='$chapterId' and NEW.module_id='LR-06'
            begin
                insert into test_lr06_progress_write_audit(sentence_id,payload) values(NEW.sentence_id,NEW.payload);
            end
        """.trimIndent())
    }

    private fun progressWrites(): List<ProgressWrite> = FormationDatabase(context).use { helper ->
        helper.readableDatabase.rawQuery(
            "select sequence,sentence_id,payload from test_lr06_progress_write_audit order by sequence", null,
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) add(ProgressWrite(cursor.getLong(0), cursor.getString(1), cursor.getString(2)))
        } }
    }

    private fun assertForwardOnlySpeakingRounds(writes: List<ProgressWrite>) {
        assertEquals("Only this journey's two focus sentences may write LR06 progress",
            focuses.map { it.id }.toSet(), writes.map { it.sentenceId }.toSet())
        assertTrue("Audit sequence must retain the actual committed insertion order",
            writes.zipWithNext().all { (previous, next) -> next.sequence > previous.sequence })
        focuses.forEach { focus ->
            // These paths only advance 2 + 2 + 1; a different focus starts its own round zero.
            // They do not request a training restart, so no reset across attempts is authorized here.
            val rounds = writes.filter { it.sentenceId == focus.id }.map { write ->
                val json = JSONObject(write.payload)
                assertEquals("LR06 must persist its actual versioned snapshot", "1", json.get("version"))
                val encodedRound = json.get("attemptIndex")
                assertTrue("Persisted attemptIndex must remain a string", encodedRound is String)
                val round = (encodedRound as String).toInt()
                assertTrue("Persisted speaking round must belong to the existing five-round path", round in 0..4)
                write.sequence to round
            }
            assertEquals("Every round of ${focus.id}'s complete 2 + 2 + 1 path must be durable",
                (0..4).toSet(), rounds.map { it.second }.toSet())
            rounds.zipWithNext().forEach { (previous, next) ->
                assertTrue("Committed LR06 round regressed for ${focus.id}: seq ${previous.first}/${previous.second} -> ${next.first}/${next.second}",
                    next.second >= previous.second)
            }
        }
    }

    private fun rejectFirstCompletedCheckpoint() = FormationDatabase(context).use { helper ->
        require(account.matches(Regex("[a-z0-9-]+")) && chapterId.matches(Regex("[a-z0-9-]+")))
        helper.writableDatabase.execSQL("""
            create trigger test_lr06_reject_completed_progress before insert on learning_module_progress
            when NEW.account_id='$account' and NEW.chapter_id='$chapterId' and NEW.sentence_id='s1'
                and NEW.module_id='LR-06' and instr(NEW.payload,'"finalDecision":"PASS"')>0
            begin
                select raise(ABORT,'isolated completed progress failure');
            end
        """.trimIndent())
    }

    private fun removeFirstCompletedCheckpointFailure() = FormationDatabase(context).use { helper ->
        helper.writableDatabase.execSQL("drop trigger test_lr06_reject_completed_progress")
    }

    private fun allRows(): Map<String, List<List<String?>>> = FormationDatabase(context).use { helper ->
        val db = helper.readableDatabase
        val names = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        names.associateWith { name ->
            require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            db.rawQuery("select * from $name order by rowid", null).use { cursor -> buildList {
                while (cursor.moveToNext()) add(List(cursor.columnCount) { index -> if (cursor.isNull(index)) null else cursor.getString(index) })
            } }
        }
    }

    private fun expectedCheckpoint() = OrdinaryLearningCheckpoint(chapterId, "LR-07", "s1")
    private fun awaitPatternCheckpoint(graph: ProductionAppGraph) = awaitIo {
        withTimeout(15_000) { while (graph.learningCheckpointRepository.read(chapterId) != expectedCheckpoint()) delay(20) }
    }
    private suspend fun assertPatternBinding(graph: ProductionAppGraph) {
        val checkpoint = requireNotNull(graph.learningCheckpointRepository.read(chapterId))
        val route = graph.resolveResumeRoute(checkpoint.chapterId, checkpoint.moduleId, checkpoint.stepId)
        assertTrue(route is HomeDiaryRoute.PatternSpeaking)
        val actual = route as HomeDiaryRoute.PatternSpeaking
        assertEquals("s1", actual.focusSentenceId)
        assertEquals(listOf("s1", "context", "s2"), actual.chapter.sentences.map { it.sentenceId })
        assertEquals(focuses.first().explanation, actual.baseExplanationZh)
        assertEquals(listOf(focuses.first().targetId), requireNotNull(graph.loadSpeakingPatterns(chapterId, "s1")).map { it.patternId })
        assertEquals(listOf(focuses.last().targetId), requireNotNull(graph.loadSpeakingPatterns(chapterId, "s2")).map { it.patternId })
    }
    private fun assertPatternFirstPage() {
        waitTag("pattern-speaking-workspace")
        waitTag("learning-focus-s1")
        compose.onNodeWithTag("learning-focus-s2").assertDoesNotExist()
        waitText(exampleEn)
        compose.onNodeWithText(exampleEn).assertIsDisplayed()
        compose.onNodeWithText(exampleZh).assertIsDisplayed()
        compose.onNodeWithText(focuses.first().structure).assertIsDisplayed()
        compose.onNodeWithText(focuses.last().structure).assertDoesNotExist()
        compose.onNodeWithText(base.getString(R.string.pattern_speaking_start_responses)).assertIsNotEnabled()
    }
    private fun openUnfinished() { waitTag("incomplete-learning-resume-$chapterId"); compose.onNodeWithTag("incomplete-learning-resume-$chapterId").performClick() }
    private fun click(resource: Int) {
        val text = base.getString(resource)
        waitText(text)
        compose.onNodeWithText(text).assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun waitAttempt(text: String) {
        compose.waitUntil(15_000) {
            assertFixtureHealthy()
            compose.onAllNodesWithTag("full-sentence-attempt").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("full-sentence-attempt").assertTextEquals(text)
    }
    private fun waitTag(tag: String) {
        compose.waitUntil(15_000) { assertFixtureHealthy(); compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun waitText(text: String) {
        compose.waitUntil(15_000) { assertFixtureHealthy(); compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun assertFixtureHealthy() { fixtureFailure.get()?.let { throw AssertionError("Isolated HTTP/capture contract mismatch", it) } }
    private fun <T> awaitIo(block: suspend () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch { result.set(runCatching { block() }) }
        try {
            compose.waitUntil(20_000) { assertFixtureHealthy(); result.get() != null }
            return requireNotNull(result.get()).getOrThrow()
        } finally { job.cancel(); scope.cancel() }
    }
}
