package com.reallanguage.app.feature.recording.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val CAPTURE_SAMPLE_RATE = 16_000

/** Write only the selected audio track as low-memory, mono PCM. Video frames are never read. */
internal suspend fun decodeCaptureAudio(context: Context, uri: Uri, output: File) {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) in setOf("audio/mpeg", "audio/mp4a-latm", "audio/raw")
        } ?: throw IllegalArgumentException("未找到可读取的 MP3、AAC 或 PCM 音轨")
        val format = extractor.getTrackFormat(track)
        val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
        extractor.selectTrack(track)
        output.outputStream().buffered(64 * 1024).use { pcm ->
            val sink = MonoSampleWriter(pcm)
            if (mime == "audio/raw") {
                val buffer = ByteBuffer.allocateDirect(1024 * 1024).order(ByteOrder.nativeOrder())
                while (true) {
                    currentCoroutineContext().ensureActive()
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    buffer.position(0); buffer.limit(size)
                    sink.write(buffer, format)
                    extractor.advance()
                }
            } else {
                val decoder = MediaCodec.createDecoderByType(mime)
                try {
                    decoder.configure(format, null, null, 0)
                    decoder.start()
                    decodePackets(extractor, decoder, sink)
                } finally { decoder.release() }
            }
            sink.finish()
        }
        require(output.length() > 0L) { "音轨是空的" }
    } finally { extractor.release() }
}

private suspend fun decodePackets(extractor: MediaExtractor, decoder: MediaCodec, sink: MonoSampleWriter) {
    var inputEnded = false
    val info = MediaCodec.BufferInfo()
    var format: MediaFormat? = null
    while (true) {
        currentCoroutineContext().ensureActive()
        if (!inputEnded) {
            val slot = decoder.dequeueInputBuffer(10_000)
            if (slot >= 0) {
                val input = requireNotNull(decoder.getInputBuffer(slot))
                val size = extractor.readSampleData(input, 0)
                if (size < 0) {
                    decoder.queueInputBuffer(slot, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputEnded = true
                } else {
                    require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "不能读取受保护的音轨" }
                    decoder.queueInputBuffer(slot, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }
        when (val slot = decoder.dequeueOutputBuffer(info, 10_000)) {
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> format = decoder.outputFormat
            in 0..Int.MAX_VALUE -> {
                try {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val bytes = requireNotNull(decoder.getOutputBuffer(slot))
                        bytes.position(info.offset); bytes.limit(info.offset + info.size)
                        sink.write(bytes.order(ByteOrder.nativeOrder()), format ?: decoder.outputFormat)
                    }
                } finally { decoder.releaseOutputBuffer(slot, false) }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
    }
}

private class MonoSampleWriter(private val output: OutputStream) {
    private var fill = 0
    private var weighted = 0L
    private var lastRate = 0

    fun write(buffer: ByteBuffer, format: MediaFormat) {
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        require(channels in 1..32 && rate in 1..768_000) { "音轨参数无法读取" }
        require(lastRate == 0 || lastRate == rate) { "音轨采样率在文件中发生变化" }
        lastRate = rate
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            else AudioFormat.ENCODING_PCM_16BIT
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> error("暂不支持这种 PCM 编码")
        }
        require(buffer.remaining() % (bytesPerSample * channels) == 0) { "音轨采样不完整" }
        while (buffer.hasRemaining()) {
            var mix = 0
            repeat(channels) {
                mix += when (encoding) {
                    AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 255) - 128) shl 8
                    AudioFormat.ENCODING_PCM_16BIT -> buffer.short.toInt()
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                        buffer.get()
                        (buffer.get().toInt() and 255) or (buffer.get().toInt() shl 8)
                    }
                    AudioFormat.ENCODING_PCM_32BIT -> buffer.int shr 16
                    else -> (buffer.float.coerceIn(-1f, 1f) * 32767).toInt()
                }
            }
            val sample = mix / channels
            var remaining = CAPTURE_SAMPLE_RATE
            while (remaining > 0) {
                val portion = minOf(remaining, rate - fill)
                weighted += sample.toLong() * portion
                fill += portion
                remaining -= portion
                if (fill == rate) emit()
            }
        }
    }

    fun finish() { if (fill > 0) emit() }

    private fun emit() {
        val sample = (weighted / fill).toInt()
        output.write(sample and 255)
        output.write(sample shr 8 and 255)
        weighted = 0L
        fill = 0
    }
}
