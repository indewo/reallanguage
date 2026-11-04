package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.MainDispatcherRule
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Isolated engine callbacks only; these tests do not assess actual platform audio or AI quality. */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSentenceSpeakerConnectionTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun deniedStartCannotReachTheEngine() {
        val engine = Engine()
        val speaker = AndroidSentenceSpeaker(engine, isPlaybackAllowed = { false })
        try {
            assertFalse(speaker.start(TEXT))
            assertTrue(engine.startedTexts.isEmpty())
        } finally { speaker.close() }
    }

    @Test fun deniedPlayCannotReachTheEngineOrReportSuccess() = runTest {
        val engine = Engine()
        val speaker = AndroidSentenceSpeaker(engine, isPlaybackAllowed = { false })
        val result = async { speaker.play(TEXT) }
        try {
            runCurrent()
            assertTrue("An unavailable connection must not queue synthesis", engine.startedTexts.isEmpty())
            assertTrue(result.isCompleted)
            assertFalse(result.await())
        } finally {
            result.cancel()
            speaker.close()
            runCurrent()
        }
    }

    @Test fun losingEligibilityWhileTheEngineInitializesPreventsStartingAfterReady() = runTest {
        var allowed = true
        val engine = Engine(readyInitially = false)
        val speaker = AndroidSentenceSpeaker(engine, isPlaybackAllowed = { allowed })
        val result = async { speaker.play(TEXT) }
        try {
            runCurrent()
            assertEquals(1, engine.readinessChecks)
            assertTrue(engine.startedTexts.isEmpty())
            assertFalse(result.isCompleted)
            allowed = false
            engine.ready.complete(true)
            runCurrent()
            assertTrue("The start boundary must recheck the current account/connection", engine.startedTexts.isEmpty())
            assertTrue(result.isCompleted)
            assertFalse(result.await())
        } finally {
            result.cancel()
            speaker.close()
            runCurrent()
        }
    }

    @Test fun stoppingWithoutAnyEngineCallbackCompletesFalseWithoutWaitingForPlaybackTimeout() = runTest {
        val engine = Engine()
        val speaker = AndroidSentenceSpeaker(engine, isPlaybackAllowed = { true })
        val result = async { speaker.play(TEXT) }
        try {
            runCurrent()
            assertEquals(listOf(TEXT), engine.startedTexts)
            assertFalse(result.isCompleted)
            val stopsBefore = engine.stops
            speaker.stop()
            runCurrent()
            assertTrue(engine.stops > stopsBefore)
            assertTrue("Stop must settle the suspended play even if the platform never calls onStop", result.isCompleted)
            assertFalse(result.await())
            engine.finish(0, true)
            runCurrent()
            assertFalse(result.await())
            assertEquals(listOf(TEXT), engine.startedTexts)
        } finally {
            result.cancel()
            speaker.close()
            runCurrent()
        }
    }

    @Test fun successArrivingImmediatelyAfterStopCannotTurnTheStoppedRequestIntoSuccess() = runTest {
        val engine = Engine()
        val speaker = AndroidSentenceSpeaker(engine, isPlaybackAllowed = { true })
        val result = async { speaker.play(TEXT) }
        try {
            runCurrent()
            assertEquals(listOf(TEXT), engine.startedTexts)
            speaker.stop()
            // Deliberately deliver a queued platform success before the coroutine can redispatch.
            engine.finish(0, true)
            runCurrent()
            assertTrue(result.isCompleted)
            assertFalse("The stopped request no longer owns the completion callback", result.await())
        } finally {
            result.cancel()
            speaker.close()
            runCurrent()
        }
    }

    @Test fun changedAccountEligibilityAtTheCompletionCallbackCannotReportSuccessfulPlayback() = runTest {
        var allowed = true
        val engine = Engine()
        val speaker = AndroidSentenceSpeaker(engine, isPlaybackAllowed = { allowed })
        val result = async { speaker.play(TEXT) }
        try {
            runCurrent()
            assertEquals(listOf(TEXT), engine.startedTexts)
            allowed = false
            engine.finish(0, true)
            runCurrent()
            assertTrue(result.isCompleted)
            assertFalse("A successful engine callback does not restore lost account eligibility", result.await())
        } finally {
            result.cancel()
            speaker.close()
            runCurrent()
        }
    }

    @Test fun restoredEligibilityNeverReplaysAutomaticallyAndOnlyAFreshExplicitPlayCanSucceed() = runTest {
        var allowed = true
        val engine = Engine()
        val speaker = AndroidSentenceSpeaker(engine, isPlaybackAllowed = { allowed })
        val first = async { speaker.play(TEXT) }
        var second: kotlinx.coroutines.Deferred<Boolean>? = null
        try {
            runCurrent()
            assertEquals(listOf(TEXT), engine.startedTexts)
            allowed = false
            speaker.stop()
            runCurrent()
            assertTrue(first.isCompleted)
            assertFalse(first.await())
            allowed = true
            runCurrent()
            assertEquals("Restoring eligibility is not user authorization to replay", listOf(TEXT), engine.startedTexts)
            val requestedAgain = async { speaker.play(TEXT) }
            second = requestedAgain
            runCurrent()
            assertEquals(listOf(TEXT, TEXT), engine.startedTexts)
            assertFalse(requestedAgain.isCompleted)
            engine.finish(0, true)
            runCurrent()
            assertFalse("An old completion cannot finish a fresh explicit request", requestedAgain.isCompleted)
            engine.finish(1, true)
            runCurrent()
            assertTrue(requestedAgain.await())
            assertFalse(first.await())
        } finally {
            first.cancel()
            second?.cancel()
            speaker.close()
            runCurrent()
        }
    }

    private class Engine(readyInitially: Boolean = true) : SentenceSpeechEngine {
        val ready = CompletableDeferred<Boolean>().apply { if (readyInitially) complete(true) }
        val startedTexts = mutableListOf<String>()
        private val completions = mutableListOf<(Boolean) -> Unit>()
        var readinessChecks = 0
        var stops = 0
        override suspend fun awaitReady(): Boolean { readinessChecks++; return ready.await() }
        override fun start(text: String, rate: Float, locale: Locale, finished: (Boolean) -> Unit): Boolean {
            startedTexts += text
            completions += finished
            return true
        }
        fun finish(index: Int, success: Boolean) { completions[index](success) }
        // An OEM/platform stop callback may be delayed or absent. Keep callbacks to deliver real late outcomes.
        override fun stop() { stops++ }
        override fun close() = stop()
    }

    private companion object { const val TEXT = "This is an isolated playback example." }
}
