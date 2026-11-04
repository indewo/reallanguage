package com.reallanguage.app.feature.review.data

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.feature.learning.data.CapturedReadSpeakAudio
import com.reallanguage.app.feature.learning.data.ReadSpeakAudioCapture
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.feature.review.domain.PatternIndependentEvaluationResult
import com.reallanguage.app.feature.review.domain.PatternIndependentGenerationResult
import com.reallanguage.app.feature.review.domain.PatternIndependentReceipt
import com.reallanguage.app.feature.review.domain.PatternIndependentReceiptStore
import com.reallanguage.app.feature.review.domain.ReviewPatternEntryLease
import com.reallanguage.app.testing.ReviewNoJudgmentFixture
import com.reallanguage.app.testing.receiptContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual repository/JSON/receipt store and UUID-private SQLite, with synthetic NOT_AUDIO and fake HTTP.
 * No microphone, service, network, provider, acoustic-quality or billing evidence. Generation is setup only. */
class AndroidPatternIndependentRetryBoundaryTest {
    @Test fun unknownEvaluationKeepsTheExactAudioBodyAndCommandForExplicitRetry() = runBlocking {
        for (fault in UnknownFault.entries) Fixture().use { f ->
            f.prepare()
            val original = f.capture.audio
            val originalBytes = original.readBytes()
            f.respond = { fault.response() }
            assertEquals(PatternIndependentEvaluationResult.Failed, f.evaluate())
            assertEquals(0, f.local.count("review_working_state"))
            assertTrue("$fault: an unknown delivery still owns its original audio", original.isFile)
            assertEquals(1, f.requests.size)
            val command = JSONObject(f.requests.single().body).getString("commandId")
            f.respond = { HttpResponse(200, evaluated()) }

            val accepted = f.evaluate() as PatternIndependentEvaluationResult.Ready
            assertEquals(2, f.requests.size)
            assertEquals(f.requests[0].body, f.requests[1].body)
            assertEquals(command, requireNotNull(accepted.evaluation.receipt).commandId)
            assertArrayEquals(originalBytes, Base64.getDecoder().decode(JSONObject(f.requests[1].body).getString("audioBase64")))
            f.assertOneCapture()
            f.assertRegistered(requireNotNull(accepted.evaluation.receipt))
            assertFalse(original.exists())
            assertEquals("Accepted current-attempt delivery must be reusable", accepted, f.evaluate())
            assertEquals(2, f.requests.size)
            assertEquals(1, f.receipts.registrations.size)
            f.assertOneCapture()
        }
    }

    @Test fun terminalDeliveryCannotResendOrRefinishUntilAnExplicitNewRecording() = runBlocking {
        val faults = listOf(
            HttpResponse(409, errorBody("IDEMPOTENCY_KEY_REUSED", false)),
            HttpResponse(409, errorBody("AI_OPERATION_PROCESSING", false)),
            HttpResponse(409, errorBody("AI_OPERATION_PROCESSING", "true")),
            HttpResponse(410, errorBody("AI_OPERATION_EXPIRED", false)),
        )
        for (fault in faults) Fixture().use { f ->
            f.prepare()
            f.respond = { fault }
            assertEquals(PatternIndependentEvaluationResult.Failed, f.evaluate())
            val firstCommand = JSONObject(f.requests.single().body).getString("commandId")
            f.respond = { error("Terminal retry cannot authorize replacement HTTP") }
            repeat(2) { assertEquals(PatternIndependentEvaluationResult.Failed, f.evaluate()) }
            assertEquals(1, f.requests.size)
            f.assertOneCapture()
            assertEquals(0, f.local.count("review_working_state"))

            assertTrue("Only an explicit fresh recording starts a new attempt", f.repository.startCapture())
            f.respond = { HttpResponse(200, evaluated()) }
            val accepted = f.evaluate() as PatternIndependentEvaluationResult.Ready
            assertEquals(2, f.requests.size)
            assertNotEquals(firstCommand, JSONObject(f.requests.last().body).getString("commandId"))
            assertEquals(2, f.capture.starts)
            assertEquals(2, f.capture.finishes)
            f.assertRegistered(requireNotNull(accepted.evaluation.receipt))
        }
    }

    @Test fun aPendingEvaluationRejectsDuplicateWorkWithoutQueuingOrStartingAnotherMicrophone() = runBlocking {
        Fixture().use { f ->
            f.prepare()
            val held = HeldResponse()
            f.respond = { if (f.requests.size == 1) held.await() else HttpResponse(200, evaluated()) }
            val original = async(start = CoroutineStart.UNDISPATCHED) { f.evaluate() }
            var duplicate: Deferred<PatternIndependentEvaluationResult>? = null
            try {
                assertTrue(held.isWaiting)
                val repeated = async(start = CoroutineStart.UNDISPATCHED) { f.evaluate() }
                duplicate = repeated
                assertTrue("Duplicate must reject now, not wait behind the first HTTP result", repeated.isCompleted)
                assertEquals(PatternIndependentEvaluationResult.Failed, repeated.await())
                assertEquals(1, f.requests.size)
                f.assertOneCapture()
                assertFalse("Uncancelled pending evaluation must not start a new microphone", f.repository.startCapture())
                assertFalse(f.capture.live)
                assertEquals(1, f.capture.starts)

                held.release(HttpResponse(200, evaluated()))
                val accepted = withTimeout(3_000) { original.await() } as PatternIndependentEvaluationResult.Ready
                yield()
                assertEquals(1, f.requests.size)
                f.assertRegistered(requireNotNull(accepted.evaluation.receipt))
            } finally {
                duplicate?.cancel()
                held.release(HttpResponse(503, "{}"))
                withContext(NonCancellable) { withTimeout(3_000) { original.join(); duplicate?.join() } }
            }
        }
    }

    @Test fun cancellingTheAttemptAllowsFreshBImmediatelyAndLateACannotRegisterOrReplaceItsReceipt() = runBlocking {
        Fixture().use { f ->
            f.prepare()
            val held = HeldResponse()
            f.respond = { if (f.requests.size == 1) held.await() else HttpResponse(200, evaluated()) }
            val old = async(start = CoroutineStart.UNDISPATCHED) { f.evaluate() }
            try {
                assertTrue(held.isWaiting)
                val oldAudio = f.capture.audio
                f.repository.cancelCapture() // Deliberately leave HTTP's coroutine alive.
                assertTrue("Fresh B must start without releasing A's non-cooperative callback", f.repository.startCapture())
                val freshAudio = f.capture.audio
                assertNotEquals(oldAudio, freshAudio)
                val freshBytes = freshAudio.readBytes()
                val fresh = withTimeout(3_000) { f.evaluate() } as PatternIndependentEvaluationResult.Ready
                val receiptB = requireNotNull(fresh.evaluation.receipt)
                assertTrue("A must still be pending while B is delivered", held.isWaiting)
                assertFalse(old.isCompleted)
                f.assertRegistered(receiptB)
                assertEquals(hash(freshBytes), receiptB.audioSha256)

                held.release(HttpResponse(200, evaluated()))
                assertEquals(PatternIndependentEvaluationResult.Failed, withTimeout(3_000) { old.await() })
                assertEquals("Only B may be registered", listOf(receiptB), f.receipts.registrations)
                f.assertRegistered(receiptB)
                assertEquals("Old finally must not remove B's delivered cache", fresh, f.evaluate())
                assertEquals(2, f.requests.size)
                assertEquals(2, f.capture.starts)
                assertEquals(2, f.capture.finishes)
            } finally {
                held.release(HttpResponse(503, "{}"))
                withContext(NonCancellable) { withTimeout(3_000) { old.join() } }
            }
        }
    }

    @Test fun receivedCandidateWhoseReceiptSaveFailedRetriesOnlyTheOriginalReceiptWithoutHttp() = runBlocking {
        Fixture().use { f ->
            f.prepare()
            f.receipts.rejectNext = 1
            assertEquals(PatternIndependentEvaluationResult.Failed, f.evaluate())
            val receipt = f.receipts.registrations.single()
            assertEquals(0, f.local.count("review_working_state"))
            assertEquals(1, f.requests.size)
            assertEquals(JSONObject(f.requests.single().body).getString("commandId"), receipt.commandId)
            f.respond = { error("A received candidate must retry only local receipt registration") }

            val saved = f.evaluate() as PatternIndependentEvaluationResult.Ready
            assertEquals(receipt, saved.evaluation.receipt)
            assertEquals(listOf(receipt, receipt), f.receipts.registrations)
            assertEquals(1, f.requests.size)
            f.assertOneCapture()
            f.assertRegistered(receipt)
            assertEquals(saved, f.evaluate())
            assertEquals(2, f.receipts.registrations.size)
            assertEquals(1, f.requests.size)
            f.assertOneCapture()
        }
    }

    @Test fun coroutineCancellationRejectsNonCooperativePassAndInconclusiveWithoutRegisteringWork() = runBlocking {
        for (decision in listOf("PASS", "INCONCLUSIVE")) Fixture().use { f ->
            f.prepare()
            val held = HeldResponse()
            f.respond = { held.await() }
            var returned: PatternIndependentEvaluationResult? = null
            var cancellationObserved = false
            val pending = launch(start = CoroutineStart.UNDISPATCHED) {
                try { returned = f.evaluate() }
                catch (_: CancellationException) { cancellationObserved = true }
            }
            try {
                assertTrue(held.isWaiting)
                pending.cancel() // No repo cancellation or lease revocation to mask this boundary.
                held.release(HttpResponse(200, evaluated(decision)))
                withTimeout(3_000) { pending.join() }
                assertTrue("$decision: repository must observe cancellation even when no receipt is required", cancellationObserved)
                assertNull(returned)
                assertEquals(0, f.local.count("review_working_state"))
                assertEquals(1, f.requests.size)
                f.assertOneCapture()
                f.assertFormalUnchanged()
            } finally {
                held.release(HttpResponse(503, "{}"))
                withContext(NonCancellable) { withTimeout(3_000) { pending.join() } }
            }
        }
    }

    private class Fixture : Closeable {
        val local = ReviewNoJudgmentFixture(InstrumentationRegistry.getInstrumentation().targetContext)
        val capture = SyntheticCapture(local.context.cacheDir)
        val requests = mutableListOf<HttpRequest>()
        lateinit var repository: AndroidPatternIndependentRepository
        lateinit var receipts: RecordingReceiptStore
        private lateinit var lease: ReviewPatternEntryLease
        private lateinit var formalBefore: List<List<String?>>
        var respond: suspend (HttpRequest) -> HttpResponse = { HttpResponse(200, evaluated()) }
        private var generations = 0

        suspend fun prepare() {
            local.seed()
            val (entry, identity) = local.receiptContext()
            lease = entry
            receipts = RecordingReceiptStore(AndroidPatternIndependentReceiptStore(local.context, local.runtime, entry, identity))
            repository = AndroidPatternIndependentRepository(HttpTransport { request ->
                check(request.method == "POST")
                when (request.path) {
                    GENERATE -> { check(++generations == 1); HttpResponse(200, GENERATION) }
                    EVALUATE -> { requests += request; respond(request) }
                    else -> error("Unexpected isolated endpoint")
                }
            }, local.runtime, FixedLearningChapter("chapter1", 1, listOf(
                FixedLearningSentence("sentence1", "I used to walk.", "我以前常走路。", true),
            )), capture, receipts)
            assertTrue(repository.generate(LockedSpeakingPattern("target1", "sentence1", "used to + verb", "过去习惯"))
                is PatternIndependentGenerationResult.Ready)
            assertTrue(repository.startCapture())
            formalBefore = local.formalSnapshot()
        }
        suspend fun evaluate() = repository.evaluate("target1", "example")
        fun assertOneCapture() { assertEquals(1, capture.starts); assertEquals(1, capture.finishes) }
        fun assertFormalUnchanged() {
            assertEquals(formalBefore, local.formalSnapshot())
            assertEquals(0, local.count("review_chapter_results"))
        }
        fun assertRegistered(receipt: PatternIndependentReceipt) {
            assertEquals(receipts.identity, receipt.identity)
            assertEquals(1, local.count("review_working_state"))
            val persisted = local.db.rawQuery(
                "select evidence_id from review_working_state where account_id=? and review_chapter_id=? and ability_id=?",
                arrayOf(local.account, receipt.identity.reviewChapterId, receipt.identity.abilityId),
            ).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0).also { check(!cursor.moveToNext()) } }
            assertEquals(receipt.evidenceId, persisted)
            val matching = requests.filter { JSONObject(it.body).getString("commandId") == receipt.commandId }
            assertTrue(matching.isNotEmpty())
            val body = matching.first().body
            assertTrue("Replayed command must retain its exact body", matching.all { it.body == body })
            assertEquals(hash(body.toByteArray(Charsets.UTF_8)), receipt.requestSha256)
            assertEquals(hash(Base64.getDecoder().decode(JSONObject(body).getString("audioBase64"))), receipt.audioSha256)
            assertFormalUnchanged()
        }
        override fun close() {
            if (::repository.isInitialized) repository.cancelCapture()
            capture.cancel()
            if (::lease.isInitialized) lease.revoke()
            local.close() // Own UUID database, preferences and directory only.
        }
    }

    /** The only injected storage failure is before the real transaction; successful retries use actual SQLite. */
    private class RecordingReceiptStore(private val real: PatternIndependentReceiptStore) : PatternIndependentReceiptStore by real {
        val registrations = mutableListOf<PatternIndependentReceipt>()
        var rejectNext = 0
        override suspend fun register(receipt: PatternIndependentReceipt, mayCommit: () -> Boolean): Boolean {
            registrations += receipt
            if (rejectNext > 0) { rejectNext--; return false }
            return real.register(receipt, mayCommit)
        }
    }
    private class SyntheticCapture(private val directory: File) : ReadSpeakAudioCapture {
        var starts = 0
        var finishes = 0
        private var current: File? = null
        lateinit var audio: File
            private set
        val live get() = current != null
        override fun start(): Boolean {
            starts++
            if (current != null) return true
            audio = File(directory, "pattern-attempt-${UUID.randomUUID()}.test").apply {
                writeBytes("NOT_AUDIO:${UUID.randomUUID()}".toByteArray(Charsets.UTF_8))
            }
            current = audio
            return true
        }
        override fun finish(): CapturedReadSpeakAudio? {
            finishes++
            val finished = current ?: return null
            current = null
            return CapturedReadSpeakAudio(finished)
        }
        override fun cancel() { current?.delete(); current = null }
    }
    private class HeldResponse {
        private var continuation: Continuation<HttpResponse>? = null
        val isWaiting get() = continuation != null
        suspend fun await(): HttpResponse = suspendCoroutine { check(continuation == null); continuation = it }
        fun release(value: HttpResponse) { continuation?.also { continuation = null; it.resume(value) } }
    }
    private enum class UnknownFault {
        SERVICE, LOST_RESPONSE, MALFORMED_SUCCESS, PROCESSING;
        fun response(): HttpResponse = when (this) {
            SERVICE -> HttpResponse(503, "{}")
            LOST_RESPONSE -> throw IOException("Isolated lost evaluation response")
            MALFORMED_SUCCESS -> HttpResponse(200, "{}")
            PROCESSING -> HttpResponse(409, errorBody("AI_OPERATION_PROCESSING", true))
        }
    }
    private companion object {
        const val GENERATE = "/api/v1/learning/pattern-practice-sets"
        const val EVALUATE = "/api/v1/learning/pattern-independent-evaluation"
        const val GENERATION = """{"status":"READY","patternId":"target1","examples":[{"exampleId":"example","sceneZh":"过去的习惯","aiPromptEn":"What changed?","targetResponseEn":"I used to walk.","targetResponseZh":"我以前常走路。"}]}"""
        fun evaluated(decision: String = "PASS") = JSONObject().put("decision", decision).put("feedbackZh", "隔离候选反馈")
            .put("structurePreserved", true).put("contentSuitable", true).put("pronunciationIntelligible", true).put("confidence", .9).toString()
        fun errorBody(code: String, retryable: Any) = JSONObject().put("error", JSONObject()
            .put("code", code).put("retryable", retryable).put("message", "Isolated failure")
            .put("requestId", "isolated-review").put("details", JSONObject())).toString()
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
