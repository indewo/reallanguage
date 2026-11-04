package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.feature.learning.domain.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Request custody and scheduling only; synthetic audio, no real HTTP or AI. */
class PatternSpeakingDurableSessionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun readingAndResponseUnknownRecoverExactBodyWithoutNewCaptureOrAutomaticHttp() = runTest {
        for (reading in listOf(true, false)) {
            val f = fixture(); val first = f.session()
            assertEquals(PatternFeedbackRecovery.None, first.restore(f.pattern, f.set))
            assertTrue(first.prepare("p", "e", reading)); assertTrue(first.start())
            assertEquals(PatternPracticeFeedbackResult.Failed, first.feedback("p", "e", reading))
            val body = f.bodies.single(); first.cancel()
            val next = f.session()
            assertEquals(PatternFeedbackRecovery.Pending("e", reading), next.restore(f.pattern, f.set))
            assertEquals(1, f.starts); assertEquals(1, f.finishes); assertEquals(1, f.bodies.size)
            assertFalse(next.prepare("p", "e", reading))
            assertEquals(PatternPracticeFeedbackResult.Failed, next.feedback("p", "other", reading))
            assertEquals(PatternPracticeFeedbackResult.Failed, next.feedback("p", "e", !reading))
            f.delivery = PatternFeedbackDelivery(f.ready)
            assertEquals(f.ready, next.feedback("p", "e", reading))
            assertEquals(listOf(body, body), f.bodies)
            assertEquals(PatternFeedbackRecovery.Delivered("e", reading, f.ready), f.session().restore(f.pattern, f.set))
            assertEquals(f.ready, next.feedback("p", "e", reading)); assertEquals(2, f.bodies.size)
        }
    }

    @Test fun pendingWriteFailureHasZeroHttpAndExplicitRetryKeepsOneFinish() = runTest {
        val f = fixture(); val session = f.started()
        f.journal.writable = false
        assertEquals(PatternPracticeFeedbackResult.Failed, session.feedback("p", "e", true))
        assertTrue(session.pending()); assertEquals(0, f.bodies.size); assertEquals(1, f.finishes)
        f.journal.writable = true; f.delivery = PatternFeedbackDelivery(f.ready)
        assertEquals(f.ready, session.feedback("p", "e", true))
        assertEquals(1, f.finishes); assertEquals(1, f.bodies.size)
    }

    @Test fun deliveredWriteFailureRetriesOnlyLocalCustodyIncludingLostAck() = runTest {
        for (lostAck in listOf(false, true)) {
            val f = fixture(); val session = f.started(); f.delivery = PatternFeedbackDelivery(f.ready)
            f.afterExecute = { if (lostAck) f.journal.loseAck = true else f.journal.writable = false }
            val result = session.feedback("p", "e", true)
            if (!lostAck) assertEquals(PatternPracticeFeedbackResult.Failed, result)
            f.journal.writable = true; f.journal.loseAck = false
            assertEquals(f.ready, session.feedback("p", "e", true)); assertEquals(1, f.bodies.size)
        }
    }

    @Test fun terminalIsNotRetryableAndOnlyExplicitNewRecordingChangesAttempt() = runTest {
        val f = fixture(); val first = f.started(); f.delivery = PatternFeedbackDelivery(PatternPracticeFeedbackResult.Failed, true)
        assertEquals(PatternPracticeFeedbackResult.Failed, first.feedback("p", "e", true)); assertTrue(first.blocked())
        val body = f.bodies.single(); val next = f.session()
        assertEquals(PatternFeedbackRecovery.Blocked("e", true), next.restore(f.pattern, f.set))
        assertEquals(PatternPracticeFeedbackResult.Failed, next.feedback("p", "e", true)); assertEquals(1, f.bodies.size)
        assertTrue(next.prepare("p", "e", true)); assertTrue(next.start())
        f.delivery = PatternFeedbackDelivery(f.ready); assertEquals(f.ready, next.feedback("p", "e", true))
        assertNotEquals(body, f.bodies.last()); assertEquals(2, f.starts)
    }

    @Test fun duplicatePendingRequestsAndNonCooperativeLateResponseCannotCommitAfterCancel() = runTest {
        val f = fixture(); val session = f.started(); val response = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        f.beforeExecute = { entered.complete(Unit); withContext(NonCancellable) { response.await() } }
        f.delivery = PatternFeedbackDelivery(f.ready)
        val old = async { session.feedback("p", "e", true) }; entered.await()
        assertEquals(PatternPracticeFeedbackResult.Failed, session.feedback("p", "e", true)); session.cancel()
        response.complete(Unit); assertEquals(PatternPracticeFeedbackResult.Failed, old.await())
        assertEquals(1, f.bodies.size)
        assertEquals(PatternFeedbackRecovery.Pending("e", true), f.session().restore(f.pattern, f.set))
    }

    @Test fun ownerChangeAndCurrentEvidenceRevocationRejectCachedBodyAndCandidate() = runTest {
        val f = fixture(); val session = f.started()
        session.feedback("p", "e", true); val saved = f.journal.value
        f.owner = false
        assertEquals(PatternPracticeFeedbackResult.Failed, session.feedback("p", "e", true))
        assertFalse(session.start()); assertEquals(PatternFeedbackRecovery.Unavailable, session.restore(f.pattern, f.set))
        assertEquals(saved, f.journal.value); assertEquals(1, f.bodies.size)
        f.owner = true; f.journal.readable = false
        assertEquals(PatternPracticeFeedbackResult.Failed, session.feedback("p", "e", true)); assertEquals(1, f.bodies.size)
    }

    @Test fun cooperativeCancellationPropagatesAndRetainsConfirmedPendingRequest() = runTest {
        val f = fixture(); val session = f.started(); val entered = CompletableDeferred<Unit>()
        f.beforeExecute = { entered.complete(Unit); awaitCancellation() }
        val old = async { session.feedback("p", "e", true) }; entered.await(); old.cancelAndJoin()
        assertTrue(old.isCancelled)
        assertEquals(PatternFeedbackRecovery.Pending("e", true), f.session().restore(f.pattern, f.set))
        assertEquals(1, f.bodies.size)
    }

    @Test fun unavailableOrChangedPracticeDoesNotSilentlyCreateNewRequest() = runTest {
        val f = fixture(); val session = f.started(); session.feedback("p", "e", true)
        f.journal.readable = false
        val next = f.session(); assertEquals(PatternFeedbackRecovery.Unavailable, next.restore(f.pattern, f.set))
        assertFalse(next.prepare("p", "e", true)); assertFalse(next.start()); assertEquals(1, f.bodies.size)
    }

    @Test fun explicitSupersededReleaseRequiresConfirmedLocalWriteBeforeAnyNewCapture() = runTest {
        val f = fixture(); val session = f.started(); session.feedback("p", "e", true)
        val pending = f.journal.value
        session.cancel(); f.journal.writable = false
        assertFalse(session.releaseSuperseded()); assertEquals(pending, f.journal.value)
        assertFalse(session.prepare("p", "e", true)); assertEquals(1, f.starts)
        f.journal.writable = true
        assertTrue(session.releaseSuperseded()); assertEquals(PatternRecoveryPhase.Cleared, f.journal.value!!.snapshot.phase)
        assertTrue(session.prepare("p", "e", true)); assertTrue(session.start())
        assertEquals(2, f.starts); assertEquals(1, f.bodies.size)
    }

    @Test fun deliveredCandidateSurvivesTemporaryEvidenceReadFailureWithoutRepeatingHttp() = runTest {
        val f = fixture(); val session = f.started(); f.delivery = PatternFeedbackDelivery(f.ready)
        f.afterExecute = { f.journal.readable = false }
        assertEquals(PatternPracticeFeedbackResult.Failed, session.feedback("p", "e", true))
        f.journal.readable = true
        assertEquals(f.ready, session.feedback("p", "e", true)); assertEquals(1, f.bodies.size)
    }

    private fun fixture() = Fixture(temporary.newFolder())
    private class Fixture(private val directory: File) {
        val pattern = LockedSpeakingPattern("p", "s", "would rather", "表达偏好")
        val set = PatternPracticeSet("p", listOf(PatternPracticeExample("e", "点茶", "Tea or coffee?", "I would rather have tea.", "我更想喝茶。")))
        val ready = PatternPracticeFeedbackResult.Ready(PatternPracticeFeedback("语意完整。", false, "I would rather have tea."))
        val journal = MemoryJournal(); var owner = true; var starts = 0; var finishes = 0
        val bodies = mutableListOf<String>(); var delivery = PatternFeedbackDelivery(PatternPracticeFeedbackResult.Failed)
        var beforeExecute: suspend () -> Unit = {}; var afterExecute: () -> Unit = {}
        suspend fun started() = session().also { it.restore(pattern, set); assertTrue(it.prepare("p", "e", true)); assertTrue(it.start()) }
        fun session() = DurablePatternFeedbackSession(object : ReadSpeakAudioCapture {
            override fun start(): Boolean { starts++; return true }
            override fun finish(): CapturedReadSpeakAudio { finishes++; return CapturedReadSpeakAudio(File(directory, UUID.randomUUID().toString()).apply { writeText("NOT_AUDIO") }, "audio/mp4") }
            override fun cancel() = Unit
        }, { owner }, { true }, { _, _ -> journal }, { _, example, mode, audio ->
            "${UUID.randomUUID()}|${example.exampleId}|$mode|${audio.file.readText()}"
        }, { body -> bodies += body; beforeExecute(); afterExecute(); delivery })
    }
    private class MemoryJournal : PatternSpeakingRecoveryJournal {
        var value: PatternSpeakingRecoveryLoad.Loaded? = null; var writable = true; var readable = true; var loseAck = false
        override suspend fun load(): PatternSpeakingRecoveryLoad = if (!readable) PatternSpeakingRecoveryLoad.Unavailable else value ?: PatternSpeakingRecoveryLoad.Missing
        override suspend fun save(expectedRevision: Long?, snapshot: PatternSpeakingRecoverySnapshot): Long? {
            if (!writable || !readable) return null
            if (value?.revision != expectedRevision) return value?.takeIf { it.revision == (expectedRevision?.plus(1) ?: 0) && it.snapshot == snapshot }?.revision
            val revision = expectedRevision?.plus(1) ?: 0
            value = PatternSpeakingRecoveryLoad.Loaded(revision, snapshot)
            return if (loseAck) null else revision
        }
    }
}
