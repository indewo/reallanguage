package com.reallanguage.app.feature.review.data

import androidx.test.platform.app.InstrumentationRegistry
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
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Actual repository/receipt SQL and UUID-owned files. The delay is before real.register, not fake SQL.
 * Synthetic NOT_AUDIO and in-memory HTTP are not microphone, acoustic, provider or billing evidence. */
class AndroidPatternReceiptRegistrationRaceTest {
    @Test fun cancelledAWhoseReceiptRegistrationWasDelayedCannotOverwriteDeliveredB() = runBlocking {
        Fixture().use { f ->
            f.prepare()
            val old = async(start = CoroutineStart.UNDISPATCHED) { f.evaluate() }
            try {
                assertTrue(f.store.isWaiting)
                val receiptA = requireNotNull(f.store.delayedReceipt)
                assertNull(f.persistedEvidence())
                assertTrue(old.isActive)
                f.repository.cancelCapture() // Intentionally do not cancel old's coroutine or chapter lease.
                assertTrue(f.repository.startCapture())
                val fresh = withTimeout(3_000) { f.evaluate() } as PatternIndependentEvaluationResult.Ready
                val receiptB = requireNotNull(fresh.evaluation.receipt)
                assertNotEquals(receiptA.commandId, receiptB.commandId)
                assertNotEquals(receiptA.audioSha256, receiptB.audioSha256)
                assertTrue("B must be delivered before A's register is released", f.store.isWaiting)
                assertFalse(old.isCompleted)
                assertEquals(receiptB.evidenceId, f.persistedEvidence())
                assertTrue(f.lease.containsRegistered(receiptB))

                f.store.release()
                assertEquals(PatternIndependentEvaluationResult.Failed, withTimeout(3_000) { old.await() })
                assertFalse("Explicit repository cancellation must not be masked by coroutine cancellation", old.isCancelled)
                assertEquals("B's delivered cache must survive A's late save", fresh, f.evaluate())
                assertEquals(2, f.evaluations)
                assertEquals("A must not replace B's working receipt even though A returned Failed",
                    receiptB.evidenceId, f.persistedEvidence())
                assertFalse("A cannot become entry-registered evidence after its attempt was cancelled", f.lease.containsRegistered(receiptA))
                assertTrue(f.lease.containsRegistered(receiptB))
                assertEquals(1, f.local.count("review_working_state"))
                assertEquals(2, f.capture.finishes)
                f.assertFormalUnchanged()
            } finally {
                f.store.release() // Also release after any RED assertion, before closing this UUID database.
                withContext(NonCancellable) { withTimeout(3_000) { old.join() } }
            }
        }
    }

    @Test fun revokedEntryStillRejectsTheSameDelayedRealRegistrationWithoutAnyWorkingRow() = runBlocking {
        Fixture().use { f ->
            f.prepare()
            val old = async(start = CoroutineStart.UNDISPATCHED) { f.evaluate() }
            try {
                assertTrue(f.store.isWaiting)
                val receipt = requireNotNull(f.store.delayedReceipt)
                f.lease.revoke()
                f.store.release()
                assertEquals(PatternIndependentEvaluationResult.Failed, withTimeout(3_000) { old.await() })
                assertFalse(old.isCancelled)
                assertNull(f.persistedEvidence())
                assertFalse(f.lease.containsRegistered(receipt))
                assertEquals(0, f.local.count("review_working_state"))
                assertEquals(1, f.evaluations)
                f.assertFormalUnchanged()
            } finally {
                f.store.release()
                withContext(NonCancellable) { withTimeout(3_000) { old.join() } }
            }
        }
    }

    private class Fixture : Closeable {
        val local = ReviewNoJudgmentFixture(InstrumentationRegistry.getInstrumentation().targetContext)
        val capture = Capture(local.context.cacheDir)
        lateinit var lease: ReviewPatternEntryLease
        lateinit var store: DelayedFirstRegistration
        lateinit var repository: AndroidPatternIndependentRepository
        private lateinit var formalBefore: List<List<String?>>
        var evaluations = 0
            private set
        private var generations = 0

        suspend fun prepare() {
            local.seed()
            val (entry, identity) = local.receiptContext()
            lease = entry
            store = DelayedFirstRegistration(AndroidPatternIndependentReceiptStore(local.context, local.runtime, entry, identity))
            repository = AndroidPatternIndependentRepository(HttpTransport { request ->
                check(request.method == "POST")
                when (request.path) {
                    "/api/v1/learning/pattern-practice-sets" -> { check(++generations == 1); HttpResponse(200, GENERATION) }
                    "/api/v1/learning/pattern-independent-evaluation" -> { evaluations++; HttpResponse(200, EVALUATION) }
                    else -> error("Unexpected isolated endpoint")
                }
            }, local.runtime, FixedLearningChapter("chapter1", 1, listOf(
                FixedLearningSentence("sentence1", "I used to walk.", "我以前常走路。", true),
            )), capture, store)
            assertTrue(repository.generate(LockedSpeakingPattern("target1", "sentence1", "used to + verb", "过去习惯"))
                is PatternIndependentGenerationResult.Ready)
            assertTrue(repository.startCapture())
            formalBefore = local.formalSnapshot()
        }
        suspend fun evaluate() = repository.evaluate("target1", "example")
        fun persistedEvidence(): String? = local.db.rawQuery(
            "select evidence_id from review_working_state where account_id=? and review_chapter_id=? and ability_id=?",
            arrayOf(local.account, store.identity.reviewChapterId, store.identity.abilityId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(0).also { assertFalse(cursor.moveToNext()) }
        }
        fun assertFormalUnchanged() {
            assertEquals(formalBefore, local.formalSnapshot())
            assertEquals(0, local.count("review_chapter_results"))
        }
        override fun close() {
            if (::repository.isInitialized) repository.cancelCapture()
            capture.cancel()
            if (::lease.isInitialized) lease.revoke()
            local.close()
        }
    }

    private class DelayedFirstRegistration(private val real: PatternIndependentReceiptStore) : PatternIndependentReceiptStore by real {
        private var first = true
        private var continuation: Continuation<Unit>? = null
        var delayedReceipt: PatternIndependentReceipt? = null
            private set
        val isWaiting get() = continuation != null

        override suspend fun register(receipt: PatternIndependentReceipt, mayCommit: () -> Boolean): Boolean {
            if (first) {
                first = false
                delayedReceipt = receipt
                suspendCoroutine<Unit> { continuation = it }
            }
            return real.register(receipt, mayCommit)
        }
        fun release() { continuation?.also { continuation = null; it.resume(Unit) } }
    }
    private class Capture(private val directory: File) : ReadSpeakAudioCapture {
        private var live: File? = null
        var finishes = 0
            private set
        override fun start(): Boolean {
            check(live == null)
            live = File(directory, "registration-${UUID.randomUUID()}.test").apply {
                writeBytes("NOT_AUDIO:${UUID.randomUUID()}".toByteArray(Charsets.UTF_8))
            }
            return true
        }
        override fun finish(): CapturedReadSpeakAudio? {
            finishes++
            return live?.also { live = null }?.let(::CapturedReadSpeakAudio)
        }
        override fun cancel() { live?.delete(); live = null }
    }
    private companion object {
        const val GENERATION = """{"status":"READY","patternId":"target1","examples":[{"exampleId":"example","sceneZh":"过去的习惯","aiPromptEn":"What changed?","targetResponseEn":"I used to walk.","targetResponseZh":"我以前常走路。"}]}"""
        const val EVALUATION = """{"decision":"PASS","feedbackZh":"隔离候选反馈","structurePreserved":true,"contentSuitable":true,"pronunciationIntelligible":true,"confidence":0.9}"""
    }
}
