package com.reallanguage.app.feature.learning.data

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.SceneEvaluationResult
import com.reallanguage.app.feature.learning.domain.SceneTaskGenerationResult
import com.reallanguage.app.feature.learning.domain.SceneTrainingFeedbackResult
import com.reallanguage.app.feature.learning.domain.SpeakingTranscriptionResult
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real repository/JSON and UUID-private SQLite. NOT_AUDIO/fake HTTP are not microphone, AI or billing evidence. */
class AndroidSceneResponseRetryBoundaryTest {
    @Test fun bothTrainingRoundsRetryUnknownDeliveryWithTheOriginalAudioAndExactBody() = runBlocking {
        for (phase in TRAINING) for (fault in Fault.entries) Fixture().use { f ->
            f.prepare(phase)
            f.respond = { fault.response() }
            assertEquals(phase.failed, f.call(phase))
            assertTrue(f.audio.exists())
            assertTrue(f.repository.hasPendingEvaluationRetry())
            assertFalse(f.repository.hasPendingTranscriptionRetry())
            assertFalse(f.repository.technicalRetryBlocked())
            f.respond = { HttpResponse(200, phase.success) }

            phase.assertReady(f.call(phase))
            f.assertFrozen(1, 2, phase)
            assertNotEquals(f.json(0).getString("commandId"), f.json(1).getString("commandId"))
            assertEquals(3, f.requests.size)
            f.assertOneRecording()
            assertFalse(f.audio.exists())
            assertFalse(f.repository.hasPendingEvaluationRetry())
        }
    }

    @Test fun onlyProcessingWithBooleanTrueCanRetryAndOtherConflictsOrGoneAreTerminal() = runBlocking {
        val terminal = listOf(
            HttpResponse(409, errorBody("AI_OPERATION_PROCESSING", false)),
            HttpResponse(409, errorBody("AI_OPERATION_PROCESSING", "true")),
            HttpResponse(409, errorBody("AI_OPERATION_PROCESSING", 1)),
            HttpResponse(409, errorBody("AI_OPERATION_PROCESSING", JSONObject.NULL)),
            HttpResponse(409, """{"error":{"code":"AI_OPERATION_PROCESSING"}}"""),
            HttpResponse(409, """{"error":{"retryable":true}}"""),
            HttpResponse(409, errorBody("IDEMPOTENCY_KEY_REUSED", true)),
            HttpResponse(409, "{}"),
            HttpResponse(410, errorBody("AI_OPERATION_PROCESSING", true)),
        )
        for (phase in TRAINING) for (response in terminal) Fixture().use { f ->
            f.prepare(phase)
            f.respond = { response }
            assertEquals(phase.failed, f.call(phase))
            val frozen = f.requests.last().body
            assertTrue("Terminal response: ${response.statusCode}/${response.body}", f.repository.technicalRetryBlocked())
            assertFalse(f.repository.hasPendingEvaluationRetry())
            f.respond = { throw AssertionError("Terminal feedback cannot create a replacement command") }

            repeat(2) { assertEquals(phase.failed, f.call(phase)) }
            assertEquals(2, f.requests.size)
            assertEquals(frozen, f.requests.last().body)
            f.assertOneRecording()
        }
    }

    @Test fun deliveredTrainingAndEvaluationIncludingInconclusiveAreCachedWithoutHttpOrRecapture() = runBlocking {
        for (phase in FEEDBACK) for (inconclusive in listOf(false, true)) Fixture().use { f ->
            f.prepare(phase)
            f.respond = { HttpResponse(200, if (!inconclusive) phase.success else phase.inconclusive) }
            val delivered = f.call(phase)
            phase.assertReady(delivered)
            assertFalse(f.audio.exists())
            f.respond = { throw AssertionError("An already delivered candidate must be reused") }

            repeat(2) { assertEquals(delivered, f.call(phase)) }
            assertTrue(f.repository.transcribeResponse("t1") is SpeakingTranscriptionResult.Ready)
            assertEquals(2, f.requests.size)
            f.assertOneRecording()
            assertFalse(f.repository.hasPendingTranscriptionRetry())
            assertFalse(f.repository.hasPendingEvaluationRetry())
            assertFalse(f.repository.technicalRetryBlocked())
        }
    }

    @Test fun unknownFeedbackCannotChangeTheExistingTaskRoundOrConfirmedTranscript() = runBlocking {
        for (phase in FEEDBACK) Fixture().use { f ->
            f.prepare(phase)
            f.respond = { HttpResponse(503, "") }
            assertEquals(phase.failed, f.call(phase))
            f.respond = { throw AssertionError("Changed evidence cannot issue a new body during retry") }

            assertEquals(phase.failed, f.call(phase, taskId = "t2")) // A real, restored second task.
            assertEquals(phase.failed, f.call(phase, transcript = "I drink water."))
            val changedRound = if (phase == Phase.TRAINING_ZERO) 1 else 0
            assertEquals(SceneTrainingFeedbackResult.Failed,
                f.repository.trainingFeedback("t1", changedRound, TEXT))
            if (phase != Phase.EVALUATION) assertEquals(SceneEvaluationResult.Failed,
                f.repository.evaluateFinalRound("t1", TEXT))
            assertEquals(SpeakingTranscriptionResult.Failed, f.repository.transcribeResponse("t2"))
            assertEquals(2, f.requests.size)
            assertTrue(f.audio.exists())
            assertTrue(f.repository.hasPendingEvaluationRetry())
            f.respond = { HttpResponse(200, phase.success) }

            phase.assertReady(f.call(phase))
            f.assertFrozen(1, 2, phase)
            assertEquals(3, f.requests.size)
            f.assertOneRecording()
        }
    }

    @Test fun coroutineCancellationRejectsNonCooperativeLateSuccessAndKeepsTheFrozenRetry() = runBlocking {
        for (phase in Phase.entries) Fixture().use { f ->
            f.prepare(phase)
            val before = f.requests.size
            val held = HeldResponse()
            var returned: Any? = null
            var cancellationPropagated = false
            f.respond = { held.await() }
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                try { f.call(phase).also { returned = it } }
                catch (cancelled: CancellationException) { cancellationPropagated = true; throw cancelled }
            }
            try {
                assertTrue(held.isWaiting)
                pending.cancel() // Do not call repository.cancelCapture: only the waiting coroutine is cancelled.
                held.release(HttpResponse(200, phase.success))
                withTimeout(2_000) { pending.join() }
                assertTrue("The repository must throw, not merely return into a cancelled Deferred", cancellationPropagated)
                assertNull(returned)
                assertTrue(f.audio.exists())
                assertEquals(phase == Phase.TRANSCRIPTION, f.repository.hasPendingTranscriptionRetry())
                assertEquals(phase != Phase.TRANSCRIPTION, f.repository.hasPendingEvaluationRetry())
                f.respond = { HttpResponse(200, phase.success) }

                phase.assertReady(f.call(phase))
                assertEquals(before + 2, f.requests.size)
                f.assertFrozen(before, before + 1, phase)
                f.assertOneRecording()
            } finally {
                held.release(HttpResponse(503, ""))
                withContext(NonCancellable) { withTimeout(2_000) { pending.join() } }
            }
        }
    }

    @Test fun anUncancelledPendingRequestRejectsNewCaptureAndDuplicateFeedbackWithoutQueueing() = runBlocking {
        for (phase in Phase.entries) Fixture().use { f ->
            f.prepare(phase)
            val before = f.requests.size
            val originalAudio = f.audio
            val held = HeldResponse()
            f.respond = { if (f.requests.size == before + 1) held.await() else HttpResponse(503, "") }
            val pending = async(start = CoroutineStart.UNDISPATCHED) { f.call(phase) }
            var duplicate: Deferred<Any>? = null
            try {
                assertTrue(held.isWaiting)
                assertFalse(f.repository.startCapture())
                assertEquals(originalAudio, f.audio)
                assertTrue(originalAudio.exists())
                assertFalse(f.repository.hasPendingTranscriptionRetry())
                assertFalse(f.repository.hasPendingEvaluationRetry())
                val second = async(start = CoroutineStart.UNDISPATCHED) { f.call(phase) }
                duplicate = second
                assertTrue("Repeated feedback must reject now, not queue another HTTP call", second.isCompleted)
                assertEquals(phase.failed, second.await())
                assertEquals(before + 1, f.requests.size)
                f.assertOneRecording()

                held.release(HttpResponse(200, phase.success))
                phase.assertReady(pending.await())
                yield()
                assertEquals(before + 1, f.requests.size)
                f.assertOneRecording()
            } finally {
                held.release(HttpResponse(503, ""))
                withContext(NonCancellable) { withTimeout(2_000) { pending.join(); duplicate?.join() } }
            }
        }
    }

    private enum class Phase(val round: Int) {
        TRANSCRIPTION(-1), TRAINING_ZERO(0), TRAINING_ONE(1), EVALUATION(2);
        val path get() = when (this) { TRANSCRIPTION -> TRANSCRIBE_PATH; EVALUATION -> EVALUATE_PATH; else -> TRAINING_PATH }
        val success get() = when (this) { TRANSCRIPTION -> TRANSCRIPTION_BODY; EVALUATION -> EVALUATION_BODY; else -> TRAINING_BODY }
        val inconclusive get() = if (this == EVALUATION) EVALUATION_BODY.replace("PASS", "INCONCLUSIVE")
            else """{"status":"INCONCLUSIVE"}"""
        val failed: Any get() = when (this) {
            TRANSCRIPTION -> SpeakingTranscriptionResult.Failed
            EVALUATION -> SceneEvaluationResult.Failed
            else -> SceneTrainingFeedbackResult.Failed
        }
        fun assertReady(result: Any) = assertTrue(when (this) {
            TRANSCRIPTION -> result is SpeakingTranscriptionResult.Ready
            EVALUATION -> result is SceneEvaluationResult.Ready
            else -> result is SceneTrainingFeedbackResult.Ready || result == SceneTrainingFeedbackResult.Inconclusive
        })
    }

    private enum class Fault {
        UNAVAILABLE, PROCESSING, MALFORMED_SUCCESS, RESPONSE_LOST;
        fun response(): HttpResponse = when (this) {
            UNAVAILABLE -> HttpResponse(503, "")
            PROCESSING -> HttpResponse(409, errorBody("AI_OPERATION_PROCESSING", true))
            MALFORMED_SUCCESS -> HttpResponse(200, "{}")
            RESPONSE_LOST -> throw IOException("Isolated unknown delivery")
        }
    }

    private class HeldResponse {
        private var continuation: Continuation<HttpResponse>? = null
        val isWaiting get() = continuation != null
        suspend fun await(): HttpResponse = suspendCoroutine { check(continuation == null); continuation = it }
        fun release(response: HttpResponse) {
            val pending = continuation ?: return
            continuation = null
            pending.resume(response)
        }
    }

    private class Fixture : Closeable {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val parent = base.cacheDir.canonicalFile
        private val label = "scene-boundary-${UUID.randomUUID()}"
        private val directory = File(parent, label).apply { check(mkdir()) }
        private val context = IsolatedLearningDatabaseContext(base, label)
        private val databaseFile = context.getDatabasePath(DATABASE).canonicalFile
        private val account = "owner-$label"
        private val runtime = SessionRuntime().apply { update("isolated-session", account) }
        private val chapter = FixedLearningChapter("chapter-$label", 1, listOf(
            FixedLearningSentence("s1", TEXT, "我喝茶。", true),
            FixedLearningSentence("s2", "I drink water.", "我喝水。", true),
        ))
        private var seeded = false
        private var starts = 0
        private var finishes = 0
        private var liveAudio: File? = null
        private var lastAudio: File? = null
        val audio get() = requireNotNull(lastAudio)
        val requests = mutableListOf<HttpRequest>()
        var respond: suspend (HttpRequest) -> HttpResponse = { request ->
            HttpResponse(200, Phase.entries.first { it.path == request.path }.success)
        }
        val repository = AndroidSceneResponseRepository(context, HttpTransport { request ->
            check(request.path in listOf(TRANSCRIBE_PATH, TRAINING_PATH, EVALUATE_PATH))
            requests += request
            respond(request)
        }, runtime, object : ReadSpeakAudioCapture {
            override fun start(): Boolean {
                check(liveAudio == null)
                starts++
                liveAudio = File(directory, "synthetic-$starts.test").apply { writeBytes(bytes(starts)) }
                    .also { lastAudio = it }
                return true
            }
            override fun finish(): CapturedReadSpeakAudio? {
                finishes++
                val file = liveAudio ?: return null
                liveAudio = null
                return CapturedReadSpeakAudio(file)
            }
            override fun cancel() { liveAudio?.delete(); liveAudio = null }
        }, JsonSceneResponseWireCodec())

        suspend fun prepare(phase: Phase) {
            check(!seeded)
            withContext(Dispatchers.IO) {
                FormationDatabase(context).use { helper ->
                    val db = helper.writableDatabase
                    db.execSQL("insert into formation_flows values(?,?,'FIXED','DIARY','隔离测试')", arrayOf(account, label))
                    db.execSQL("insert into chapters values(?,?,?,0,'IN_PROGRESS')", arrayOf(account, label, chapter.chapterId))
                    chapter.sentences.forEachIndexed { index, sentence ->
                        db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,?,?,?,?,?,'FOCUS')",
                            arrayOf<Any>(account, chapter.chapterId, sentence.sentenceId, index, sentence.textZh, sentence.textEn))
                        db.execSQL("insert into scene_response_tasks(account_id,chapter_id,task_id,sentence_id,sentence_order,background_en,context_lines,target_sentence_en) values(?,?,?,?,?,'Talk about drinks.',?,?)",
                            arrayOf<Any>(account, chapter.chapterId, "t${index + 1}", sentence.sentenceId, index, "AI\u001fWhat do you drink?", sentence.textEn))
                    }
                    seeded = true
                }
            }
            val restored = repository.restoreTasks(chapter) as SceneTaskGenerationResult.Ready
            assertEquals(listOf("t1", "t2"), restored.tasks.map { it.taskId })
            assertTrue(requests.isEmpty())
            assertTrue(repository.startCapture())
            if (phase != Phase.TRANSCRIPTION) assertTrue(repository.transcribeResponse("t1") is SpeakingTranscriptionResult.Ready)
        }
        suspend fun call(phase: Phase, taskId: String = "t1", transcript: String = TEXT): Any = when (phase) {
            Phase.TRANSCRIPTION -> repository.transcribeResponse(taskId)
            Phase.EVALUATION -> repository.evaluateFinalRound(taskId, transcript)
            else -> repository.trainingFeedback(taskId, phase.round, transcript)
        }
        fun json(index: Int) = JSONObject(requireNotNull(requests[index].body))
        fun assertOneRecording() { assertEquals(1, starts); assertEquals(1, finishes); assertNull(liveAudio) }
        private fun bytes(index: Int) = "NOT_AUDIO:$label:$index".toByteArray(Charsets.UTF_8)
        fun assertFrozen(first: Int, retry: Int, phase: Phase) {
            assertEquals(requests[first].body, requests[retry].body)
            assertEquals(json(first).getString("commandId"), json(retry).getString("commandId"))
            assertTrue(json(first).getString("commandId").isNotBlank())
            for (index in listOf(first, retry)) {
                assertEquals("POST", requests[index].method)
                assertEquals(phase.path, requests[index].path)
                val json = json(index)
                assertEquals(chapter.chapterId, json.getString("chapterId"))
                assertEquals("s1", json.getString("sentenceId"))
                assertEquals("audio/mp4", json.getString("audioMimeType"))
                assertArrayEquals(bytes(1), Base64.getDecoder().decode(json.getString("audioBase64")))
                if (phase != Phase.TRANSCRIPTION) {
                    assertEquals("t1", json.getString("taskId"))
                    assertEquals(TEXT, json.getString("confirmedTranscript"))
                    assertEquals(phase.round, json.getInt("roundIndex"))
                    assertTrue(json.getBoolean("userTranscriptConfirmed"))
                }
            }
        }
        override fun close() {
            check(directory.canonicalFile.parentFile == parent && directory.name == label)
            check(databaseFile.parentFile == parent && databaseFile.name.startsWith("$label-"))
            try {
                repository.cancelCapture()
                if (seeded) FormationDatabase(context).use { helper ->
                    for (table in listOf("scene_response_result_drafts", "learning_module_progress", "learning_steps")) {
                        helper.readableDatabase.rawQuery("select count(*) from $table where account_id=?", arrayOf(account)).use {
                            assertTrue(it.moveToFirst()); assertEquals("Delivery alone must not advance learning", 0, it.getInt(0))
                        }
                    }
                }
            } finally {
                runtime.clear()
                directory.listFiles().orEmpty().forEach { file ->
                    check(file.canonicalFile.parentFile == directory.canonicalFile && file.name.matches(Regex("synthetic-[0-9]+\\.test")))
                    check(file.delete())
                }
                check(context.deleteDatabase(DATABASE) || !databaseFile.exists())
                check(directory.delete())
            }
        }
    }

    private companion object {
        val TRAINING = listOf(Phase.TRAINING_ZERO, Phase.TRAINING_ONE)
        val FEEDBACK = TRAINING + Phase.EVALUATION
        const val DATABASE = "real-language-learning.db"
        const val TRANSCRIBE_PATH = "/api/v1/learning/speaking-transcriptions"
        const val TRAINING_PATH = "/api/v1/learning/scene-response-training-feedback"
        const val EVALUATE_PATH = "/api/v1/learning/scene-response-evaluations"
        const val TEXT = "I drink tea."
        const val TRANSCRIPTION_BODY = """{"transcript":"I drink tea.","lowConfidenceSegments":[],"confidence":0.9}"""
        const val TRAINING_BODY = """{"status":"READY","feedbackZh":"隔离训练反馈，不是原音评价。","pronunciationConfidence":0.9}"""
        const val EVALUATION_BODY = """{"decision":"PASS","correctTurn":true,"targetSentenceComplete":true,"pronunciationIntelligible":true,"feedbackZh":"隔离候选，不是原音评价。","pronunciationConfidence":0.9}"""
        fun errorBody(code: String, retryable: Any) = JSONObject().put("error", JSONObject()
            .put("code", code).put("retryable", retryable).put("message", "isolated")
            .put("requestId", "isolated").put("details", JSONObject())).toString()
    }
}
