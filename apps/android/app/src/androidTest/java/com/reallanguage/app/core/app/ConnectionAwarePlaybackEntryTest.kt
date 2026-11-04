package com.reallanguage.app.core.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.data.AndroidSentenceSpeaker
import com.reallanguage.app.feature.learning.data.SentenceSpeechEngine
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real Compose/Main and speaker lifecycle; isolated engine, no system TTS, HTTP or audio-quality claim. */
class ConnectionAwarePlaybackEntryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun interruptionStopsPendingPlaybackAndRecoveryRequiresANewExplicitPlay() {
        val fixture = Fixture()
        install(fixture)
        try {
            compose.onNodeWithTag(PLAY).performClick()
            compose.waitUntil(3_000) { fixture.engines.single().texts.size == 1 }
            val engine = fixture.engines.single()
            val stopsBefore = engine.stops.get()
            assertTrue(fixture.results.isEmpty())
            compose.runOnIdle {
                fixture.allowed.set(false)
                fixture.interrupted.value = true
            }
            compose.waitUntil(3_000) { fixture.results.size == 1 }
            assertEquals(listOf(Result("first", false)), fixture.results.toList())
            assertTrue(engine.stops.get() > stopsBefore)
            assertEquals("No platform callback supplied the stopped result", 0, engine.deliveries.get())

            compose.runOnIdle {
                fixture.allowed.set(true)
                fixture.interrupted.value = false
            }
            compose.waitForIdle()
            assertEquals("Restored connectivity does not authorize replay", listOf(TEXT), engine.texts.toList())
            assertEquals(1, fixture.engines.size)
            compose.onNodeWithTag(PLAY).performClick()
            compose.waitUntil(3_000) { engine.texts.size == 2 }
            compose.runOnIdle { engine.finish(0, true) }
            compose.waitForIdle()
            assertEquals("A stale completion cannot finish the fresh explicit play", listOf(Result("first", false)), fixture.results.toList())
            compose.runOnIdle { engine.finish(1, true) }
            compose.waitUntil(3_000) { fixture.results.size == 2 }
            assertEquals(listOf(Result("first", false), Result("first", true)), fixture.results.toList())
        } finally { remove(fixture) }
    }

    @Test fun liveEligibilityRevocationBeforeRecompositionBlocksStartAndExplicitPlay() {
        val fixture = Fixture()
        install(fixture)
        try {
            val engine = fixture.engines.single()
            compose.runOnIdle {
                // This is not Compose state: policy.interrupted remains false and no recompose is requested.
                fixture.allowed.set(false)
                assertFalse(fixture.interrupted.value)
                assertFalse(checkNotNull(fixture.currentSpeaker).start(TEXT))
                assertTrue(engine.texts.isEmpty())
            }
            compose.onNodeWithTag(PLAY).performClick()
            compose.waitUntil(3_000) { fixture.results.size == 1 }
            assertEquals(listOf(Result("first", false)), fixture.results.toList())
            assertTrue(engine.texts.isEmpty())
            assertEquals(1, fixture.engines.size)
            compose.runOnIdle { fixture.allowed.set(true) }
            compose.waitForIdle()
            assertTrue("Changing eligibility alone cannot synthesize anything", engine.texts.isEmpty())
        } finally { remove(fixture) }
    }

    @Test fun changingTheSpeakerKeyRetainsRouteOwnedCloseAndRejectsTheOldCompletion() {
        val fixture = Fixture()
        install(fixture)
        try {
            compose.onNodeWithTag(PLAY).performClick()
            compose.waitUntil(3_000) { fixture.engines.single().texts.size == 1 }
            val oldEngine = fixture.engines.single()
            compose.runOnIdle { fixture.key.value = "second" }
            compose.waitUntil(3_000) { fixture.engines.size == 2 && fixture.results.size == 1 }
            val newEngine = fixture.engines[1]
            assertEquals("The route's DisposableEffect, not the helper, owns close", 1, oldEngine.closes.get())
            assertEquals(0, newEngine.closes.get())
            assertEquals(listOf(Result("first", false)), fixture.results.toList())
            assertTrue(newEngine.texts.isEmpty())
            compose.onNodeWithTag(PLAY).performClick()
            compose.waitUntil(3_000) { newEngine.texts.size == 1 }
            compose.runOnIdle { oldEngine.finish(0, true) }
            compose.waitForIdle()
            assertEquals(listOf(Result("first", false)), fixture.results.toList())
            compose.runOnIdle { newEngine.finish(0, true) }
            compose.waitUntil(3_000) { fixture.results.size == 2 }
            assertEquals(listOf(Result("first", false), Result("second", true)), fixture.results.toList())
            remove(fixture)
            assertEquals(1, oldEngine.closes.get())
            assertEquals(1, newEngine.closes.get())
        } finally { remove(fixture) }
    }

    private fun install(fixture: Fixture) {
        compose.setContent {
            CompositionLocalProvider(
                LocalConnectionPlaybackPolicy provides ConnectionPlaybackPolicy(fixture.allowed::get, fixture.interrupted.value),
                LocalSentenceSpeakerFactory provides { _, allowed ->
                    val engine = Engine()
                    fixture.engines += engine
                    AndroidSentenceSpeaker(engine, isPlaybackAllowed = allowed)
                },
            ) {
                RlTheme { if (fixture.visible.value) PlaybackRoute(fixture, fixture.key.value) }
            }
        }
        compose.waitForIdle()
        assertEquals(1, fixture.engines.size)
    }

    @Composable
    private fun PlaybackRoute(fixture: Fixture, key: String) {
        val speaker = rememberConnectionAwareSentenceSpeaker(key)
        val scope = rememberCoroutineScope()
        // Mirror existing production routes exactly: the helper does not acquire close ownership.
        DisposableEffect(speaker) { onDispose { speaker.close() } }
        SideEffect { fixture.currentSpeaker = speaker }
        Button(onClick = { scope.launch { fixture.results += Result(key, speaker.play(TEXT)) } }, modifier = Modifier.testTag(PLAY)) {
            Text("隔离播放")
        }
    }

    private fun remove(fixture: Fixture) {
        compose.runOnIdle { fixture.visible.value = false }
        compose.waitForIdle()
    }

    private class Fixture {
        val allowed = AtomicBoolean(true)
        val interrupted: MutableState<Boolean> = mutableStateOf(false)
        val key: MutableState<String> = mutableStateOf("first")
        val visible: MutableState<Boolean> = mutableStateOf(true)
        val engines = CopyOnWriteArrayList<Engine>()
        val results = CopyOnWriteArrayList<Result>()
        @Volatile var currentSpeaker: AndroidSentenceSpeaker? = null
    }

    private data class Result(val key: String, val success: Boolean)

    private class Engine : SentenceSpeechEngine {
        val texts = CopyOnWriteArrayList<String>()
        val stops = AtomicInteger()
        val closes = AtomicInteger()
        val deliveries = AtomicInteger()
        private val callbacks = CopyOnWriteArrayList<(Boolean) -> Unit>()
        override suspend fun awaitReady() = true
        override fun start(text: String, rate: Float, locale: Locale, finished: (Boolean) -> Unit): Boolean {
            texts += text
            callbacks += finished
            return true
        }
        fun finish(index: Int, success: Boolean) { deliveries.incrementAndGet(); callbacks[index](success) }
        // Intentionally no stop callback; the real speaker must settle its own pending completion.
        override fun stop() { stops.incrementAndGet() }
        override fun close() { closes.incrementAndGet() }
    }

    private companion object {
        const val PLAY = "isolated-connection-play"
        const val TEXT = "This is an isolated playback example."
    }
}
