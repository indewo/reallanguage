package com.reallanguage.app.feature.learning.data

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.ReadSpeakCompletionSignal
import com.reallanguage.app.feature.learning.domain.ReadSpeakFeedbackResult
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Repository cancellation boundary only: real JSON/private SQLite, NOT_AUDIO and a non-cooperative fake HTTP callback.
 * Does not demonstrate a failure of the production cancellable socket, microphone, speech quality or AI billing. */
class AndroidReadSpeakCancellationBoundaryTest {
    @Test fun cancelledCompleteFeedbackCannotCacheLateSuccessOrDeleteTheOriginalBeforeExplicitRetry() = runBlocking {
        for (signal in listOf(ReadSpeakCompletionSignal.RECOGNITION_COMPLETE, ReadSpeakCompletionSignal.TOUCH_REACHED_END)) {
            verifyLateCancellation(signal, contentComplete = true)
        }
    }

    @Test fun cancelledIncompleteRecognitionCannotReturnOrCompleteAnAttemptWithoutASuspendingSave() = runBlocking {
        verifyLateCancellation(ReadSpeakCompletionSignal.RECOGNITION_COMPLETE, contentComplete = false)
    }

    @Test fun anAlreadyCancelledCallerCannotFinishCaptureOrSendFeedback() = runBlocking {
        Fixture().use { f ->
            f.prepare()
            var returned: ReadSpeakFeedbackResult? = null
            var propagated = false
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext()[Job]!!.cancel()
                try { f.feedback().also { returned = it } }
                catch (failure: CancellationException) { propagated = true; throw failure }
            }
            try {
                withTimeout(2_000) { cancelled.join() }
                assertTrue("Cancellation must be observed before consuming live audio", propagated)
                assertNull(returned)
                assertTrue(f.requests.isEmpty())
                assertEquals(0, f.finishes)
                assertTrue(f.live)
                assertTrue(f.audio.exists())
                f.assertTrainingCount(0)

                assertEquals(ready(true), f.feedback())
                assertEquals(1, f.requests.size)
                f.assertOnlyTrainingCommand(f.json(0).getString("commandId"))
                f.assertOneCapture()
            } finally {
                withContext(NonCancellable) { withTimeout(2_000) { cancelled.join() } }
            }
        }
    }

    @Test fun explicitRepositoryCancellationRejectsTheLateResponseWithoutTrainingOrRetryState() = runBlocking {
        Fixture().use { f ->
            f.prepare()
            val held = HeldResponse()
            f.respond = { held.await() }
            val pending = async(start = CoroutineStart.UNDISPATCHED) { f.feedback() }
            try {
                assertTrue(held.isWaiting)
                f.repository.cancelCapture()
                assertFalse(f.audio.exists())
                held.release(HttpResponse(200, responseBody(true)))
                assertEquals(ReadSpeakFeedbackResult.Failed, withTimeout(2_000) { pending.await() })
                assertFalse(f.repository.hasPendingFeedbackRetry())
                assertEquals(1, f.requests.size)
                f.assertTrainingCount(0)
                f.assertOneCapture()
            } finally {
                held.release(HttpResponse(503, ""))
                withContext(NonCancellable) { withTimeout(2_000) { pending.join() } }
            }
        }
    }

    private suspend fun verifyLateCancellation(signal: ReadSpeakCompletionSignal, contentComplete: Boolean) = coroutineScope {
        Fixture().use { f ->
            f.prepare()
            val held = HeldResponse()
            var returned: ReadSpeakFeedbackResult? = null
            var propagated = false
            f.respond = { held.await() }
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                try { f.feedback(signal).also { returned = it } }
                catch (failure: CancellationException) { propagated = true; throw failure }
            }
            try {
                assertTrue(held.isWaiting)
                pending.cancel() // Intentionally leave repository generation/owner unchanged.
                held.release(HttpResponse(200, responseBody(contentComplete)))
                withTimeout(2_000) { pending.join() }
                assertTrue("Returning into a cancelled Deferred is not cancellation propagation", propagated)
                assertNull(returned)
                f.assertTrainingCount(0)
                assertTrue("An undelivered cancelled result must not delete its retry evidence", f.audio.exists())
                assertTrue(f.repository.hasPendingFeedbackRetry())
                assertEquals(1, f.requests.size)
                f.assertOneCapture()
                f.respond = { HttpResponse(200, responseBody(contentComplete)) }

                assertEquals(ready(contentComplete), f.feedback(signal))
                assertEquals("The cancelled callback must not become an accepted local cache", 2, f.requests.size)
                f.assertFrozen(signal)
                if (contentComplete) f.assertOnlyTrainingCommand(f.json(0).getString("commandId"))
                else f.assertTrainingCount(0)
                assertFalse(f.audio.exists())
                assertFalse(f.repository.hasPendingFeedbackRetry())
                f.assertOneCapture()
            } finally {
                // Always release non-cooperative continuations, even when a RED assertion fails.
                held.release(HttpResponse(503, ""))
                withContext(NonCancellable) { withTimeout(2_000) { pending.join() } }
            }
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
        private val label = "read-cancel-${UUID.randomUUID()}"
        private val directory = File(parent, label).apply { check(mkdir()) }
        private val context = IsolatedLearningDatabaseContext(base, label)
        private val databaseFile = context.getDatabasePath(DATABASE).canonicalFile
        private val account = "owner-$label"
        private val chapter = "chapter-$label"
        private val runtime = SessionRuntime().apply { update("isolated-session", account) }
        val audio = File(directory, "synthetic.m4a")
        private val bytes = "NOT_AUDIO:$label".toByteArray(Charsets.UTF_8)
        private var starts = 0
        var finishes = 0
            private set
        var live = false
            private set
        val requests = mutableListOf<HttpRequest>()
        var respond: suspend (HttpRequest) -> HttpResponse = { HttpResponse(200, responseBody(true)) }
        val repository = AndroidReadSpeakRepository(
            transport = HttpTransport { request ->
                check(request.path == PATH)
                requests += request
                respond(request)
            },
            runtime = runtime,
            chapterId = chapter,
            targetTextEn = TEXT,
            commandId = UUID.randomUUID().toString(),
            capture = object : ReadSpeakAudioCapture {
                override fun start(): Boolean {
                    check(!live)
                    starts++
                    audio.writeBytes(bytes)
                    live = true
                    return true
                }
                override fun finish(): CapturedReadSpeakAudio? {
                    finishes++
                    if (!live) return null
                    live = false
                    return CapturedReadSpeakAudio(audio)
                }
                override fun cancel() { if (live) audio.delete(); live = false }
            },
            stepStore = AndroidLearningStepStore(context),
            codec = JsonReadSpeakWireCodec(),
        )
        suspend fun prepare() {
            withContext(Dispatchers.IO) {
                FormationDatabase(context).use { helper ->
                    val db = helper.writableDatabase
                    db.execSQL("insert into formation_flows values(?,?,'FIXED','DIARY','隔离测试')", arrayOf(account, label))
                    db.execSQL("insert into chapters values(?,?,?,0,'IN_PROGRESS')", arrayOf(account, label, chapter))
                    db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,?,'s1',0,'我很平静。',?,'FOCUS')",
                        arrayOf(account, chapter, TEXT))
                }
            }
            assertTrue(repository.startCapture())
        }
        suspend fun feedback(signal: ReadSpeakCompletionSignal = ReadSpeakCompletionSignal.RECOGNITION_COMPLETE) =
            repository.feedback("s1", signal)
        fun json(index: Int) = JSONObject(requireNotNull(requests[index].body))
        fun assertOneCapture() { assertEquals(1, starts); assertEquals(1, finishes); assertFalse(live) }
        fun assertFrozen(signal: ReadSpeakCompletionSignal) {
            assertEquals(requests[0].body, requests[1].body)
            for (index in 0..1) {
                val request = requests[index]
                assertEquals(PATH, request.path)
                assertEquals("POST", request.method)
                assertEquals("Bearer isolated-session", request.headers["Authorization"])
                val json = json(index)
                assertEquals(chapter, json.getString("chapterId"))
                assertEquals("s1", json.getString("sentenceId"))
                assertEquals(TEXT, json.getString("targetTextEn"))
                assertEquals(signal.name, json.getString("completionSignal"))
                assertEquals("audio/mp4", json.getString("audioMimeType"))
                assertTrue(json.getString("commandId").isNotBlank())
                assertArrayEquals(bytes, Base64.decode(json.getString("audioBase64"), Base64.NO_WRAP))
            }
        }
        fun assertTrainingCount(expected: Int) = FormationDatabase(context).use { helper ->
            helper.readableDatabase.rawQuery("select count(*) from learning_steps where account_id=?", arrayOf(account)).use {
                assertTrue(it.moveToFirst()); assertEquals(expected, it.getInt(0))
            }
        }
        fun assertOnlyTrainingCommand(command: String) {
            assertTrainingCount(1)
            FormationDatabase(context).use { helper ->
                helper.readableDatabase.rawQuery("select chapter_id,sentence_id,step,status,command_id from learning_steps where account_id=?", arrayOf(account)).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(chapter, it.getString(0)); assertEquals("s1", it.getString(1))
                    assertEquals("READ_SPEAK", it.getString(2)); assertEquals("COMPLETED", it.getString(3))
                    assertEquals(command, it.getString(4)); assertFalse(it.moveToNext())
                }
            }
        }
        override fun close() {
            check(directory.canonicalFile.parentFile == parent && directory.name == label)
            check(databaseFile.parentFile == parent && databaseFile.name.startsWith("$label-"))
            check(audio.canonicalFile.parentFile == directory.canonicalFile)
            try { repository.cancelCapture() } finally {
                runtime.clear()
                if (audio.exists()) check(audio.delete())
                check(context.deleteDatabase(DATABASE) || !databaseFile.exists())
                check(directory.listFiles()?.isEmpty() == true)
                check(directory.delete())
            }
        }
    }

    private companion object {
        const val DATABASE = "real-language-learning.db"
        const val PATH = "/api/v1/learning/reading-feedback"
        const val TEXT = "I felt calm."
        const val FEEDBACK = "隔离反馈，不是人声判断。"
        fun responseBody(complete: Boolean) = JSONObject().put("feedbackZh", FEEDBACK).put("contentComplete", complete)
            .put("transcript", TEXT).put("feedbackConfidence", 0.9)
            .put("action", if (complete) "CONTINUE" else "REPEAT_FOR_CLARITY").toString()
        fun ready(complete: Boolean) = ReadSpeakFeedbackResult.Ready(FEEDBACK, complete)
    }
}
