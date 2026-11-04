package com.reallanguage.app.feature.review.data

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.*
import com.reallanguage.app.feature.learning.data.CapturedReadSpeakAudio
import com.reallanguage.app.feature.learning.data.ReadSpeakAudioCapture
import com.reallanguage.app.feature.learning.domain.*
import com.reallanguage.app.feature.review.domain.*
import com.reallanguage.app.testing.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Synthetic bytes and HTTP doubles only; real JSON, queue binding and SQLite storage are exercised. */
class PatternIndependentReceiptWireTest {
    private val fixture = ReviewNoJudgmentFixture(InstrumentationRegistry.getInstrumentation().targetContext)
    private val pattern = LockedSpeakingPattern("target1", "sentence1", "used to + verb", "过去习惯")
    private val chapter = FixedLearningChapter("chapter1", 1, listOf(FixedLearningSentence("sentence1", "I used to walk.", "我以前常走路。", true)))
    private val generation = """{"status":"READY","patternId":"target1","examples":[{"exampleId":"example","sceneZh":"过去的习惯","aiPromptEn":"What changed?","targetResponseEn":"I used to walk.","targetResponseZh":"我以前常走路。"}]}"""
    private fun response() = JSONObject().put("decision", "PASS").put("feedbackZh", "隔离测试反馈")
        .put("structurePreserved", true).put("contentSuitable", true).put("pronunciationIntelligible", true).put("confidence", .9)
    @After fun cleanOnlyUuidFixture() { fixture.close() }

    @Test fun actualRequestCommandAndAudioAndBodyHashesAreDeliveredOnlyAfterTheWorkingRowReadsBack() = runBlocking {
        fixture.seed()
        val (lease, identity) = fixture.receiptContext()
        val store = AndroidPatternIndependentReceiptStore(fixture.context, fixture.runtime, lease, identity)
        var evaluationRequest: HttpRequest? = null
        val capture = Capture()
        val repository = AndroidPatternIndependentRepository(HttpTransport { request ->
            if (request.path.endsWith("pattern-practice-sets")) HttpResponse(200, generation)
            else { evaluationRequest = request; HttpResponse(200, response().toString()) }
        }, fixture.runtime, chapter, capture, store)
        assertTrue(repository.generate(pattern) is PatternIndependentGenerationResult.Ready)
        assertTrue(repository.startCapture())
        val result = repository.evaluate("target1", "example") as PatternIndependentEvaluationResult.Ready
        val receipt = requireNotNull(result.evaluation.receipt)
        val request = requireNotNull(evaluationRequest)
        assertEquals(identity, receipt.identity)
        assertEquals(JSONObject(request.body).getString("commandId"), receipt.commandId)
        assertEquals(hash(capture.bytes), receipt.audioSha256)
        assertEquals(hash(request.body.toByteArray(Charsets.UTF_8)), receipt.requestSha256)
        assertEquals("example", receipt.exampleId)
        assertEquals(PatternIndependentDecision.PASS, receipt.decision)
        assertEquals(receipt.evidenceId, fixture.db.rawQuery("select evidence_id from review_working_state where account_id=?",
            arrayOf(fixture.account)).use { check(it.moveToFirst()); it.getString(0) })
        assertFalse(capture.audio.exists())
    }

    @Test fun missingAndWrongTypedObservationFieldsNeverGetRegisteredAsEvidence() = runBlocking {
        fixture.seed()
        val (lease, identity) = fixture.receiptContext()
        val variants = listOf(response().apply { remove("confidence") }, response().put("structurePreserved", "true"),
            response().put("confidence", ".9"), response().put("feedbackZh", " "))
        for (invalid in variants) {
            val capture = Capture()
            val repository = AndroidPatternIndependentRepository(HttpTransport { request ->
                HttpResponse(200, if (request.path.endsWith("pattern-practice-sets")) generation else invalid.toString())
            }, fixture.runtime, chapter, capture, AndroidPatternIndependentReceiptStore(fixture.context, fixture.runtime, lease, identity))
            assertTrue(repository.generate(pattern) is PatternIndependentGenerationResult.Ready)
            assertTrue(repository.startCapture())
            assertEquals(PatternIndependentEvaluationResult.Failed, repository.evaluate("target1", "example"))
            assertEquals(0, fixture.count("review_working_state"))
            assertTrue("Malformed delivery must retain its original retry evidence", capture.audio.exists())
            assertTrue(repository.hasPendingEvaluationRetry())
            repository.cancelCapture()
            assertFalse(capture.audio.exists())
        }
    }

    @Test fun contradictoryOrLowConfidenceResponsesAreInconclusiveWithoutAFormalReceipt() = runBlocking {
        fixture.seed()
        val (lease, identity) = fixture.receiptContext()
        for (output in listOf(response().put("structurePreserved", false), response().put("confidence", .59),
            response().put("decision", "INCONCLUSIVE"))) {
            val capture = Capture()
            val repository = AndroidPatternIndependentRepository(HttpTransport { request ->
                HttpResponse(200, if (request.path.endsWith("pattern-practice-sets")) generation else output.toString())
            }, fixture.runtime, chapter, capture, AndroidPatternIndependentReceiptStore(fixture.context, fixture.runtime, lease, identity))
            repository.generate(pattern); assertTrue(repository.startCapture())
            val result = repository.evaluate("target1", "example") as PatternIndependentEvaluationResult.Ready
            assertEquals(PatternIndependentDecision.INCONCLUSIVE, result.evaluation.decision)
            assertNull(result.evaluation.receipt); assertEquals(0, fixture.count("review_working_state"))
            assertFalse(capture.audio.exists())
        }
    }

    @Test fun entryRevokedDuringHttpCannotRegisterALateReplyAndMissingStoreCannotInventReceipt() = runBlocking {
        fixture.seed()
        val (lease, identity) = fixture.receiptContext()
        val capture = Capture()
        val repository = AndroidPatternIndependentRepository(HttpTransport { request ->
            if (request.path.endsWith("pattern-practice-sets")) HttpResponse(200, generation)
            else { lease.revoke(); HttpResponse(200, response().toString()) }
        }, fixture.runtime, chapter, capture, AndroidPatternIndependentReceiptStore(fixture.context, fixture.runtime, lease, identity))
        repository.generate(pattern); assertTrue(repository.startCapture())
        assertEquals(PatternIndependentEvaluationResult.Failed, repository.evaluate("target1", "example"))
        assertEquals(0, fixture.count("review_working_state")); assertFalse(capture.audio.exists())
        var requests = 0
        val noStore = AndroidPatternIndependentRepository(HttpTransport { requests++; HttpResponse(200, response().toString()) },
            fixture.runtime, chapter, Capture())
        assertEquals(PatternIndependentEvaluationResult.Failed, noStore.evaluate("target1", "example"))
        assertEquals(0, requests)
    }

    private inner class Capture : ReadSpeakAudioCapture {
        val bytes = "synthetic-not-speech".toByteArray()
        val audio = File(fixture.context.cacheDir, "receipt-audio-${java.util.UUID.randomUUID()}.test")
        override fun start() = true
        override fun cancel() { audio.delete() }
        override fun finish(): CapturedReadSpeakAudio {
            audio.writeBytes(bytes)
            return CapturedReadSpeakAudio(audio)
        }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
