package com.reallanguage.app.feature.recording.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureAudioSignalTest {
    @Test fun compressedSilenceAndSingleClickDoNotSendContentForRecognition() {
        val samples = ShortArray(32_000) { if (it % 2 == 0) 9 else -9 }
        samples[20_000] = 28_000
        val pcm = bytes(samples)
        assertFalse(hasAudibleCaptureSignal(pcm, 0, samples.size))
    }

    @Test fun softSpeechIsRetainedOnlyInsideItsOwnedWindow() {
        val samples = ShortArray(4_000)
        for (i in 600 until 1_600) samples[i] = if (i % 20 < 10) 25 else -25
        val pcm = bytes(samples)
        assertTrue(hasAudibleCaptureSignal(pcm, 400, 2_000))
        assertFalse(hasAudibleCaptureSignal(pcm, 2_000, samples.size))
    }

    private fun bytes(samples: ShortArray): ByteArray =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            asShortBuffer().put(samples)
        }.array()
}
