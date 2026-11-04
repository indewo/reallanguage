package com.reallanguage.app.feature.review.data

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.learning.data.CapturedReadSpeakAudio
import com.reallanguage.app.feature.learning.data.ReadSpeakAudioCapture
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.feature.review.domain.PatternIndependentEvaluationResult
import com.reallanguage.app.feature.review.domain.PatternIndependentGenerationResult
import com.reallanguage.app.feature.review.domain.PatternIndependentReceiptStore
import com.reallanguage.app.testing.ReviewNoJudgmentFixture
import com.reallanguage.app.testing.receiptContext
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Transport/capture doubles only. Synthetic bytes are not speech or AI-quality evidence. */
class PatternIndependentRepositoryOwnerTest {
    private val pattern = LockedSpeakingPattern("target1", "sentence1", "used to + verb", "过去习惯")
    private val chapter = FixedLearningChapter("chapter1", 1, listOf(
        FixedLearningSentence("sentence1", "I used to walk.", "我以前常走路。", true)))
    private val ready = """{"status":"READY","patternId":"target1","examples":[{"exampleId":"e1","sceneZh":"过去的习惯","aiPromptEn":"What changed?","targetResponseEn":"I used to walk.","targetResponseZh":"我以前常走路。"}]}"""
    private val evaluated = """{"decision":"PASS","feedbackZh":"隔离测试反馈","structurePreserved":true,"contentSuitable":true,"pronunciationIntelligible":true,"confidence":0.9}"""

    @Test fun aRepositoryCannotBorrowARepeatedLoginToReadOrSendOldChapterContent() = runBlocking {
        fixture { runtime, capture, receipts ->
            var requests = 0
            val repository = AndroidPatternIndependentRepository(HttpTransport { requests++; HttpResponse(200, ready) }, runtime, chapter, capture, receipts)
            relogin(runtime)
            assertFalse(repository.startCapture())
            assertEquals(PatternIndependentGenerationResult.Failed, repository.generate(pattern))
            assertEquals(PatternIndependentEvaluationResult.Failed, repository.evaluate("target1", "e1"))
            assertEquals(0, requests); assertEquals(0, capture.starts); assertEquals(0, capture.finishes)
        }
    }

    @Test fun generationReplyFromAReplacedAccountDoesNotPopulateAnOldRepository() = runBlocking {
        fixture { runtime, capture, receipts ->
            var requests = 0
            val repository = AndroidPatternIndependentRepository(HttpTransport {
                requests++; runtime.update("isolated-new", "other-owner"); HttpResponse(200, ready)
            }, runtime, chapter, capture, receipts)
            assertEquals(PatternIndependentGenerationResult.Failed, repository.generate(pattern))
            assertEquals(PatternIndependentEvaluationResult.Failed, repository.evaluate("target1", "e1"))
            assertEquals(1, requests); assertEquals(0, capture.finishes)
        }
    }

    @Test fun evaluationReplyAfterSameAccountReauthenticationCannotBecomeAFormalResult() = runBlocking {
        fixture { runtime, capture, receipts ->
            var requests = 0
            val repository = AndroidPatternIndependentRepository(HttpTransport {
                requests++
                if (requests == 1) HttpResponse(200, ready) else { relogin(runtime); HttpResponse(200, evaluated) }
            }, runtime, chapter, capture, receipts)
            assertTrue(repository.generate(pattern) is PatternIndependentGenerationResult.Ready)
            assertTrue(repository.startCapture())
            assertEquals(PatternIndependentEvaluationResult.Failed, repository.evaluate("target1", "e1"))
            assertFalse(capture.audio.exists()); assertEquals(2, requests)
        }
    }

    @Test fun tokenRefreshWithinTheSameLoginKeepsValidCaptureAndEvaluationUsable() = runBlocking {
        fixture { runtime, capture, receipts ->
            var requests = 0
            val repository = AndroidPatternIndependentRepository(HttpTransport {
                requests++; runtime.update("isolated-refresh", requireNotNull(runtime.accountId))
                HttpResponse(200, if (requests == 1) ready else evaluated)
            }, runtime, chapter, capture, receipts)
            assertTrue(repository.generate(pattern) is PatternIndependentGenerationResult.Ready)
            assertTrue(repository.startCapture())
            assertTrue(repository.evaluate("target1", "e1") is PatternIndependentEvaluationResult.Ready)
            assertFalse(capture.audio.exists()); assertEquals(2, requests)
        }
    }

    @Test fun cancelledGenerationRemainsCancellationAndDoesNotTurnIntoARetryableFailure() = runBlocking {
        fixture { runtime, capture, receipts ->
            val repository = AndroidPatternIndependentRepository(HttpTransport { throw CancellationException("isolated-cancel") }, runtime, chapter, capture, receipts)
            val result = runCatching { repository.generate(pattern) }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals(0, capture.finishes)
        }
    }

    @Test fun cancelledEvaluationPropagatesCancellationAndRetainsOnlyTheActiveAttemptUntilExplicitExit() = runBlocking {
        fixture { runtime, capture, receipts ->
            var requests = 0
            val repository = AndroidPatternIndependentRepository(HttpTransport {
                requests++; if (requests == 1) HttpResponse(200, ready) else throw CancellationException("isolated-cancel")
            }, runtime, chapter, capture, receipts)
            assertTrue(repository.generate(pattern) is PatternIndependentGenerationResult.Ready)
            assertTrue(repository.startCapture())
            val result = runCatching { repository.evaluate("target1", "e1") }
            assertTrue(result.exceptionOrNull() is CancellationException)
            // A cancelled waiter cannot prove that the server rejected the original command.
            assertTrue(capture.audio.exists()); assertEquals(2, requests)
            assertTrue(repository.hasPendingEvaluationRetry())
            repository.cancelCapture()
            assertFalse(capture.audio.exists())
            assertFalse(repository.hasPendingEvaluationRetry())
        }
    }

    private fun relogin(runtime: SessionRuntime) {
        val account = requireNotNull(runtime.accountId)
        runtime.clear(); runtime.update("isolated-again", account)
    }
    private suspend fun fixture(block: suspend (SessionRuntime, Capture, PatternIndependentReceiptStore) -> Unit) {
        val capture = Capture()
        val fixture = ReviewNoJudgmentFixture(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            fixture.seed()
            val (lease, identity) = fixture.receiptContext()
            block(fixture.runtime, capture, AndroidPatternIndependentReceiptStore(fixture.context, fixture.runtime, lease, identity))
        } finally { capture.audio.delete(); fixture.close() }
    }
    private class Capture : ReadSpeakAudioCapture {
        val audio = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "pattern-owner-${UUID.randomUUID()}.test")
        var starts = 0
        var finishes = 0
        override fun start(): Boolean { starts++; return true }
        override fun cancel() = Unit
        override fun finish(): CapturedReadSpeakAudio { finishes++; audio.writeBytes(byteArrayOf(1, 2, 3)); return CapturedReadSpeakAudio(audio) }
    }
}
