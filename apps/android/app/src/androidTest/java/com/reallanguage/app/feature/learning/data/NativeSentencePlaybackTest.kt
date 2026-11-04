package com.reallanguage.app.feature.learning.data

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Locale

/** Explicit real platform speech probe, not an AI-quality or physical speaker assessment. */
class NativeSentencePlaybackTest {
    @Test fun chineseExplanationCompletesThroughInstalledPlatformEngine() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realNativeChineseSpeech") == "true")
        val diagnosis = withContext(Dispatchers.Main) {
            val ready = CompletableDeferred<Int>()
            val engine = TextToSpeech(ApplicationProvider.getApplicationContext<Context>()) { ready.complete(it) }
            try {
                val status = withTimeoutOrNull(5_000) { ready.await() }
                "init=$status, language=${engine.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE)}, ChineseVoices=${engine.voices.orEmpty().count { it.locale.language == "zh" }}"
            } finally { engine.shutdown() }
        }
        val speaker = withContext(Dispatchers.Main) {
            AndroidSentenceSpeaker(ApplicationProvider.getApplicationContext<Context>())
        }
        try { assertTrue("Chinese voice unavailable: $diagnosis", speaker.play("这里表示我感到很平静。", 1f, Locale.SIMPLIFIED_CHINESE)) }
        finally { withContext(Dispatchers.Main) { speaker.close() } }
    }
    @Test fun englishAudioCompletesThroughInstalledPlatformEngine() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realNativeSpeech") == "true")
        val speaker = withContext(Dispatchers.Main) {
            AndroidSentenceSpeaker(ApplicationProvider.getApplicationContext<Context>())
        }
        try {
            assertTrue("The installed engine must report actual playback completion", speaker.play("I felt calm.", .72f))
        } finally { withContext(Dispatchers.Main) { speaker.close() } }
    }
}
