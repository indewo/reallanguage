package com.reallanguage.app.feature.learning.data

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class EmbeddedVoiceSelectionTest {
    @Test fun exactAccentAndHighestInstalledOfflineQualityWin() {
        val network = voice("network-neural", Locale.US, Voice.QUALITY_VERY_HIGH, true)
        val british = voice("offline-en-gb", Locale.UK, Voice.QUALITY_VERY_HIGH, false)
        val standard = voice("offline-en-us-standard", Locale.US, Voice.QUALITY_NORMAL, false)
        val natural = voice("offline-en-us-natural", Locale.US, Voice.QUALITY_HIGH, false)

        assertEquals(natural, bestEmbeddedVoice(setOf(network, british, standard, natural), Locale.US))
    }

    @Test fun voicesNeedingDownloadAreNotTreatedAsOfflineReady() {
        val missing = Voice(
            "missing", Locale.US, Voice.QUALITY_VERY_HIGH, Voice.LATENCY_LOW, false,
            setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED),
        )
        assertNull(bestEmbeddedVoice(setOf(missing), Locale.US))
    }

    private fun voice(name: String, locale: Locale, quality: Int, network: Boolean) =
        Voice(name, locale, quality, Voice.LATENCY_LOW, network, emptySet())
}
