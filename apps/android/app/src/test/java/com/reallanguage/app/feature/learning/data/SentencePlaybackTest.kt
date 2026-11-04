package com.reallanguage.app.feature.learning.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class SentencePlaybackTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()

    @Test fun waitsForEngineAndActualCompletionInsteadOfEstimatingDuration() = runTest(dispatcher) {
        val engine = Engine()
        val speaker = AndroidSentenceSpeaker(engine)
        val result = async { speaker.play("A long sentence.", .72f) }
        runCurrent()
        assertEquals(0, engine.starts)
        engine.ready.complete(true)
        runCurrent()
        assertEquals(1, engine.starts)
        advanceTimeBy(20_000)
        assertFalse(result.isCompleted)
        assertEquals(1, engine.starts)
        engine.finish?.invoke(true)
        assertTrue(result.await())
    }

    @Test fun failureAndUnavailableEngineNeverReportPlaybackCompleted() = runTest(dispatcher) {
        val unavailable = Engine().apply { ready.complete(false) }
        assertFalse(AndroidSentenceSpeaker(unavailable).play("Text"))
        assertEquals(0, unavailable.starts)
        val engine = Engine().apply { ready.complete(true) }
        val result = async { AndroidSentenceSpeaker(engine).play("Text") }
        runCurrent()
        engine.finish?.invoke(false)
        assertFalse(result.await())
    }

    @Test fun cancellationStopsAudioAndMissingCallbackHasBoundedTimeout() = runTest(dispatcher) {
        val engine = Engine().apply { ready.complete(true) }
        val speaker = AndroidSentenceSpeaker(engine)
        val result = async { speaker.play("Text") }
        runCurrent()
        result.cancel(); runCurrent()
        assertTrue(engine.stops > 0)
        val before = engine.stops
        val timeout = async { speaker.play("Text") }
        advanceUntilIdle()
        assertFalse(timeout.await())
        assertTrue(engine.stops > before)
    }

    @Test fun requestedLanguageAndRateReachRealEngineWithoutChangingText() = runTest(dispatcher) {
        val engine = Engine().apply { ready.complete(true) }
        val result = async { AndroidSentenceSpeaker(engine).play("原意说明。", 1f, Locale.SIMPLIFIED_CHINESE) }
        runCurrent()
        assertEquals("原意说明。", engine.text)
        assertEquals(Locale.SIMPLIFIED_CHINESE, engine.locale)
        assertEquals(1f, engine.rate)
        engine.finish?.invoke(true)
        assertTrue(result.await())
    }

    @Test fun engineExceptionBecomesAnHonestPlaybackFailure() = runTest(dispatcher) {
        val engine = Engine().apply { ready.complete(true); throwOnStart = true }
        assertFalse(AndroidSentenceSpeaker(engine).play("Text"))
    }

    private class Engine : SentenceSpeechEngine {
        val ready = CompletableDeferred<Boolean>()
        var starts = 0
        var stops = 0
        var finish: ((Boolean) -> Unit)? = null
        var text = ""
        var locale: Locale? = null
        var rate = 0f
        var throwOnStart = false
        override suspend fun awaitReady() = ready.await()
        override fun start(text: String, rate: Float, locale: Locale, finished: (Boolean) -> Unit): Boolean {
            if (throwOnStart) throw IllegalStateException("engine disconnected")
            starts++; this.text = text; this.rate = rate; this.locale = locale; finish = finished
            return true
        }
        override fun stop() { stops++; finish?.invoke(false); finish = null }
        override fun close() = stop()
    }
}
