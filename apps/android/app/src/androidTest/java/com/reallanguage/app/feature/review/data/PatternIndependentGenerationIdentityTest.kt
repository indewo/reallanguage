package com.reallanguage.app.feature.review.data

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.*
import com.reallanguage.app.feature.learning.data.ReadSpeakAudioCapture
import com.reallanguage.app.feature.learning.domain.*
import com.reallanguage.app.feature.review.domain.*
import com.reallanguage.app.testing.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real request JSON and private queue identity; HTTP is a double, never AI/audio. */
class PatternIndependentGenerationIdentityTest {
    private val fixture = ReviewNoJudgmentFixture(InstrumentationRegistry.getInstrumentation().targetContext)
    private val pattern = LockedSpeakingPattern("target1", "sentence1", "used to + verb", "过去习惯")
    private val chapter = FixedLearningChapter("chapter1", 1,
        listOf(FixedLearningSentence("sentence1", "I used to walk.", "我以前常走路。", true)))
    private val generation = """{"status":"READY","patternId":"target1","examples":[{"exampleId":"example","sceneZh":"过去的习惯","aiPromptEn":"What changed?","targetResponseEn":"I used to walk.","targetResponseZh":"我以前常走路。"}]}"""
    private val noCapture = object : ReadSpeakAudioCapture {
        override fun start(): Boolean = error("No recording authorized")
        override fun finish(): com.reallanguage.app.feature.learning.data.CapturedReadSpeakAudio? = error("No recording authorized")
        override fun cancel() = Unit
    }
    @After fun cleanOnlyUuidFixture() { fixture.close() }

    @Test fun responseLossRetryAndRecreatedRepositoryInTheSameEntryUseTheSameCommandAndBody() = runBlocking {
        fixture.seed()
        val (lease, identity) = fixture.receiptContext()
        val store = AndroidPatternIndependentReceiptStore(fixture.context, fixture.runtime, lease, identity)
        val requests = mutableListOf<HttpRequest>()
        val transport = HttpTransport { request -> requests += request; throw IOException("Isolated lost delivery; no real provider") }
        val first = AndroidPatternIndependentRepository(transport, fixture.runtime, chapter, noCapture, store)
        assertEquals(PatternIndependentGenerationResult.Failed, first.generate(pattern))
        assertEquals(PatternIndependentGenerationResult.Failed, first.generate(pattern))
        val reopened = AndroidPatternIndependentRepository(transport, fixture.runtime, chapter, noCapture,
            AndroidPatternIndependentReceiptStore(fixture.context, fixture.runtime, lease, identity))
        assertEquals(PatternIndependentGenerationResult.Failed, reopened.generate(pattern))
        assertEquals(3, requests.size)
        assertTrue(requests.all { it.path == "/api/v1/learning/pattern-practice-sets" })
        assertEquals("Same entry retries must not create fresh billable commands", 1,
            requests.map { JSONObject(it.body).getString("commandId") }.distinct().size)
        assertEquals(1, requests.map { it.body }.distinct().size)
        assertEquals(0, fixture.count("review_working_state"))
    }

    @Test fun deliveredReadyAndNaturalUnavailableAreReusedWithoutAnotherGenerationRequest() = runBlocking {
        fixture.seed()
        val (_, baseIdentity) = fixture.receiptContext()
        for (body in listOf(generation, """{"status":"NOT_NATURALLY_VERIFIABLE"}""")) {
            val lease = ReviewPatternEntryLease(fixture.account, fixture.runtime.accountEpoch, baseIdentity.reviewChapterId, UUID.randomUUID().toString())
            val identity = baseIdentity.copy(entryId = lease.entryId)
            var calls = 0
            val transport = HttpTransport { calls++; HttpResponse(200, body) }
            val repository = AndroidPatternIndependentRepository(transport,
                fixture.runtime, chapter, noCapture, AndroidPatternIndependentReceiptStore(fixture.context, fixture.runtime, lease, identity))
            val delivered = repository.generate(pattern)
            assertTrue(delivered is PatternIndependentGenerationResult.Ready || delivered == PatternIndependentGenerationResult.NotNaturallyVerifiable)
            val sameRepository = repository.generate(pattern)
            val recreatedRepository = AndroidPatternIndependentRepository(transport, fixture.runtime, chapter, noCapture,
                AndroidPatternIndependentReceiptStore(fixture.context, fixture.runtime, lease, identity)).generate(pattern)
            assertEquals(delivered, sameRepository)
            assertEquals(delivered, recreatedRepository)
            assertEquals("Reopening already delivered entry content must be zero HTTP", 1, calls)
            lease.revoke()
            assertEquals(PatternIndependentGenerationResult.Failed, repository.generate(pattern))
            assertEquals(1, calls)
        }
    }

    @Test fun anotherEntryOrChangedActualGenerationFactsCannotBorrowTheOriginalCommand() = runBlocking {
        fixture.seed()
        val (lease, identity) = fixture.receiptContext()
        val requests = mutableListOf<HttpRequest>()
        val transport = HttpTransport { requests += it; HttpResponse(200, generation) }
        val repository = AndroidPatternIndependentRepository(transport, fixture.runtime, chapter, noCapture,
            AndroidPatternIndependentReceiptStore(fixture.context, fixture.runtime, lease, identity))
        assertTrue(repository.generate(pattern) is PatternIndependentGenerationResult.Ready)
        assertTrue(repository.generate(pattern.copy(explanationZh = "曾经的习惯")) is PatternIndependentGenerationResult.Ready)
        val changedChapter = chapter.copy(sentences = chapter.sentences.map { it.copy(textZh = "我过去常散步。") })
        assertTrue(AndroidPatternIndependentRepository(transport, fixture.runtime, changedChapter, noCapture,
            AndroidPatternIndependentReceiptStore(fixture.context, fixture.runtime, lease, identity)).generate(pattern) is PatternIndependentGenerationResult.Ready)
        val newLease = ReviewPatternEntryLease(fixture.account, fixture.runtime.accountEpoch, identity.reviewChapterId, UUID.randomUUID().toString())
        assertTrue(AndroidPatternIndependentRepository(transport, fixture.runtime, chapter, noCapture,
            AndroidPatternIndependentReceiptStore(fixture.context, fixture.runtime, newLease, identity.copy(entryId = newLease.entryId)))
            .generate(pattern) is PatternIndependentGenerationResult.Ready)
        lease.revoke()
        val sameFieldsButNewActivity = ReviewPatternEntryLease(fixture.account, fixture.runtime.accountEpoch, identity.reviewChapterId, identity.entryId)
        assertTrue(AndroidPatternIndependentRepository(transport, fixture.runtime, chapter, noCapture,
            AndroidPatternIndependentReceiptStore(fixture.context, fixture.runtime, sameFieldsButNewActivity, identity))
            .generate(pattern) is PatternIndependentGenerationResult.Ready)
        assertEquals(5, requests.size)
        assertEquals(5, requests.map { JSONObject(it.body).getString("commandId") }.distinct().size)
        assertEquals("我过去常散步。", JSONObject(requests[2].body).getString("sourceTextZh"))
        assertEquals(0, fixture.count("review_working_state"))
    }
}
