package com.reallanguage.app.feature.recording.data

import android.media.MediaExtractor
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureAudioImportNativeTest {
    @Test fun androidDecodesSpeechFromEverySupportedFormatAndIgnoresVideo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (extension in listOf("mp3", "m4a", "aac", "mp4", "wav")) {
            val source = File.createTempFile("capture-source", ".$extension", context.cacheDir)
            val pcm = File.createTempFile("capture-result", ".pcm", context.cacheDir)
            try {
                instrumentation.context.assets.open("capture-audio/friday.$extension").use { input ->
                    source.outputStream().use(input::copyTo)
                }
                if (extension == "mp4") {
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(source.absolutePath)
                        assertTrue("Fixture must contain a video track too", extractor.trackCount == 2)
                    } finally { extractor.release() }
                }
                runBlocking { decodeCaptureAudio(context, Uri.fromFile(source), pcm) }
                val data = pcm.readBytes()
                val duration = data.size.toDouble() / (CAPTURE_SAMPLE_RATE * 2)
                assertTrue("$extension decoded $duration seconds", duration in 1.75..2.1)
                assertTrue("$extension decoded silence", data.count { it != 0.toByte() } > data.size / 4)
            } finally { source.delete(); pcm.delete() }
        }
    }
}
