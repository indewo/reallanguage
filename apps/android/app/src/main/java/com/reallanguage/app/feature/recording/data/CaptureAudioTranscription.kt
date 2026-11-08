package com.reallanguage.app.feature.recording.data

import android.content.Context
import android.net.Uri
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.AndroidFormationPayloadKeys
import com.reallanguage.app.feature.formation.data.FormationPayloadCipher
import com.reallanguage.app.feature.formation.data.FormationPayloadIdentity
import com.reallanguage.app.feature.formation.data.FormationPayloadOwner
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

fun captureRecordingUri(context: Context, sessionRuntime: SessionRuntime, recordingId: String): Uri {
    require(recordingId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")))
    val account = requireNotNull(sessionRuntime.accountId) { "请先登录" }
    val file = File(accountRecordingsDirectory(context.filesDir, account), "$recordingId.m4a")
    check(file.isFile && file.length() > 0) { "录音文件未能保存" }
    return Uri.fromFile(file)
}

suspend fun transcribeCaptureAudio(
    context: Context, uri: Uri, transport: HttpTransport, sessionRuntime: SessionRuntime,
): String = withContext(Dispatchers.IO) {
    val account = requireNotNull(sessionRuntime.accountId) { "请先登录" }
    val epoch = sessionRuntime.accountEpoch
    fun credential(): String {
        check(sessionRuntime.accountId == account && sessionRuntime.accountEpoch == epoch) { "登录状态已变更，请重新打开录音" }
        return requireNotNull(sessionRuntime.accessToken) { "请先登录" }
    }
    credential()
    val accountHash = digest(account.toByteArray())
    val directory = File(context.cacheDir, "capture-transcription/$accountHash").apply { mkdirs() }
    // Successful and cancelled calls delete their PCM immediately; clean up abandoned process writes as well.
    directory.listFiles { file -> file.extension == "pcm" && System.currentTimeMillis() - file.lastModified() > 86_400_000 }?.forEach(File::delete)
    val file = File.createTempFile("audio-", ".pcm", directory)
    val cipher = FormationPayloadCipher(AndroidFormationPayloadKeys())
    try {
        decodeCaptureAudio(context, uri, file)
        credential()
        RandomAccessFile(file, "r").use { audio ->
            val frames = audio.length() / 2
            val pieces = mutableListOf<String>()
            var start = 0L
            while (start < frames) {
                currentCoroutineContext().ensureActive()
                val token = credential()
                val end = nextCaptureBoundary(audio, start, frames)
                val windowStart = maxOf(0L, start - PADDING_FRAMES)
                val windowEnd = minOf(frames, end + PADDING_FRAMES)
                val samples = ByteArray(((windowEnd - windowStart) * 2).toInt())
                audio.seek(windowStart * 2)
                audio.readFully(samples)
                val ownedFirst = (start - windowStart).toInt()
                val ownedEnd = (end - windowStart).toInt()
                if (hasAudibleCaptureSignal(samples, ownedFirst, ownedEnd)) {
                    val windowHash = digest(samples)
                    val id = UUID.nameUUIDFromBytes("capture-pcm-auto-v1:$windowHash:$start:$ownedFirst:$ownedEnd".toByteArray()).toString()
                    val identity = FormationPayloadIdentity(FormationPayloadOwner(accountHash, "capture:$id"), 0, 1)
                    val checkpoint = File(directory, "$id.enc")
                    val previous = if (checkpoint.isFile) cipher.decrypt(identity, checkpoint.readBytes())?.toString(Charsets.UTF_8) else null
                    val recognized = previous ?: run {
                        val body = JSONObject().put("commandId", id).put("recordingId", id)
                            .put("durationSeconds", ((windowEnd - windowStart + CAPTURE_SAMPLE_RATE - 1) / CAPTURE_SAMPLE_RATE).toInt())
                            .put("audioFormat", "audio/wav").put("languageHint", "auto").put("endReason", "CAPTURE_WINDOW")
                            .put("coreStartMs", ownedFirst * 1000L / CAPTURE_SAMPLE_RATE)
                            .put("coreEndMs", (ownedEnd * 1000L + CAPTURE_SAMPLE_RATE - 1) / CAPTURE_SAMPLE_RATE)
                            .put("audioBase64", Base64.getEncoder().encodeToString(waveform(samples))).toString()
                        credential()
                        val response = transport.execute(HttpRequest("POST", "/api/v1/media/recording-transcriptions",
                            body, mapOf("Authorization" to "Bearer $token")))
                        credential()
                        check(response.statusCode == 200) { "第 ${pieces.size + 1} 段音频转译失败，可重试" }
                        val value = JSONObject(response.body).getString("fullText").trim()
                        check(value.isNotBlank()) { "未识别到可用的文字" }
                        val pending = File.createTempFile("progress-", ".tmp", directory)
                        try {
                            pending.writeBytes(cipher.encrypt(identity, value.toByteArray()))
                            check(pending.renameTo(checkpoint)) { "未能保存转译进度" }
                        } finally { pending.delete() }
                        value
                    }
                    pieces += recognized
                }
                start = end
            }
            credential()
            check(pieces.isNotEmpty()) { "没有检测到清晰的声音，请检查录音或更换音频文件。" }
            pieces.joinToString(" ")
        }
    } finally { file.delete() }
}

private const val PADDING_FRAMES = CAPTURE_SAMPLE_RATE

/** Compressed silence can contain non-zero samples. Require 40 ms above the near-silence floor. */
internal fun hasAudibleCaptureSignal(pcm: ByteArray, firstFrame: Int, endFrame: Int): Boolean {
    val frameSize = CAPTURE_SAMPLE_RATE / 50
    val minimumRms = 12 // −69 dBFS on signed 16-bit PCM, below normal quiet speech.
    var audibleFrames = 0
    var offset = firstFrame
    while (offset < endFrame) {
        val end = minOf(offset + frameSize, endFrame)
        var energy = 0L
        for (frame in offset until end) {
            val low = pcm[frame * 2].toInt() and 255
            val sample = low or (pcm[frame * 2 + 1].toInt() shl 8)
            energy += sample.toLong() * sample
        }
        if (energy >= minimumRms.toLong() * minimumRms * (end - offset)) {
            audibleFrames += end - offset
            if (audibleFrames >= frameSize * 2) return true
        }
        offset = end
    }
    return false
}

/** Find a low-energy word break near one minute; recognition also sees one second on both sides. */
private fun nextCaptureBoundary(file: RandomAccessFile, start: Long, total: Long): Long {
    if (total - start <= CAPTURE_SAMPLE_RATE * 70L) return total
    val scanStart = start + CAPTURE_SAMPLE_RATE * 50L
    val scanEnd = start + CAPTURE_SAMPLE_RATE * 65L
    val blockFrames = CAPTURE_SAMPLE_RATE / 4
    val bytes = ByteArray(blockFrames * 2)
    var best = scanStart
    var bestEnergy = Long.MAX_VALUE
    var pos = scanStart
    file.seek(pos * 2)
    while (pos + blockFrames <= scanEnd) {
        file.readFully(bytes)
        var sum = 0L
        for (i in bytes.indices step 2) {
            val sample = (bytes[i].toInt() and 255) or (bytes[i+1].toInt() shl 8)
            sum += sample.toLong() * sample
        }
        if (sum < bestEnergy) { bestEnergy = sum; best = pos + blockFrames / 2 }
        pos += blockFrames
    }
    return best
}

private fun waveform(samples: ByteArray): ByteArray {
    val result = ByteBuffer.allocate(44 + samples.size).order(ByteOrder.LITTLE_ENDIAN)
    result.put("RIFF".toByteArray()).putInt(36 + samples.size).put("WAVEfmt ".toByteArray()).putInt(16)
    result.putShort(1).putShort(1).putInt(CAPTURE_SAMPLE_RATE).putInt(CAPTURE_SAMPLE_RATE * 2)
        .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples.size).put(samples)
    return result.array()
}

private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }
