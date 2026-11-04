package com.reallanguage.app.feature.diary.data

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.diary.domain.DiaryDictationRepository
import com.reallanguage.app.feature.diary.domain.DiaryDictationResult
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class CapturedDiaryDictationAudio(val file: File, val mimeType: String = "audio/mp4")

interface DiaryDictationAudioCapture {
    fun start(): Boolean
    fun finish(): CapturedDiaryDictationAudio?
    fun cancel()
}

class AndroidMediaRecorderDiaryDictationCapture(context: Context) : DiaryDictationAudioCapture {
    private val applicationContext = context.applicationContext
    private var recorder: MediaRecorder? = null
    private var output: File? = null

    @Synchronized
    override fun start(): Boolean {
        if (recorder != null) return false
        val directory = File(applicationContext.cacheDir, "diary-dictation").apply { mkdirs() }
        val destination = File(directory, "${UUID.randomUUID()}.m4a")
        return try {
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(applicationContext) else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(destination.absolutePath)
                prepare()
                start()
            }
            output = destination
            true
        } catch (_: Exception) {
            release(deleteOutput = true)
            false
        }
    }

    @Synchronized
    override fun finish(): CapturedDiaryDictationAudio? {
        val destination = output ?: return null
        return try {
            recorder?.stop()
            release(deleteOutput = false)
            destination.takeIf { it.isFile && it.length() > 0 }?.let(::CapturedDiaryDictationAudio)
        } catch (_: Exception) {
            release(deleteOutput = true)
            null
        }
    }

    @Synchronized
    override fun cancel() = release(deleteOutput = true)

    private fun release(deleteOutput: Boolean) {
        runCatching { recorder?.release() }
        recorder = null
        if (deleteOutput) output?.delete()
        output = null
    }
}

class HttpDiaryDictationRepository(
    private val transport: HttpTransport,
    private val runtime: SessionRuntime,
    private val capture: DiaryDictationAudioCapture,
    private val codec: DiaryDictationWireCodec = JsonDiaryDictationWireCodec,
) : DiaryDictationRepository {
    private val ownerAccountId = runtime.accountId
    private val ownerAccountEpoch = runtime.accountEpoch

    private fun ownsSession() = ownerAccountId != null && runtime.accountId == ownerAccountId &&
        runtime.accountEpoch == ownerAccountEpoch && runtime.accessToken != null

    override fun start(): Boolean = ownsSession() && capture.start()

    override suspend fun finish(commandId: String): DiaryDictationResult = withContext(Dispatchers.IO) {
        val audio = capture.finish() ?: return@withContext DiaryDictationResult.Failed
        try {
            currentCoroutineContext().ensureActive()
            if (!ownsSession() || commandId.isBlank() || audio.file.length() !in 1..MAX_AUDIO_BYTES ||
                audio.mimeType != "audio/mp4") {
                return@withContext DiaryDictationResult.Failed
            }
            val token = runtime.accessToken ?: return@withContext DiaryDictationResult.Failed
            val body = codec.request(audio, commandId)
            currentCoroutineContext().ensureActive()
            val response = transport.execute(
                HttpRequest(
                    method = "POST",
                    path = "/api/v1/learning/diary-dictations",
                    body = body,
                    headers = mapOf("Authorization" to "Bearer $token"),
                ),
            )
            currentCoroutineContext().ensureActive()
            if (!ownsSession() || response.statusCode != 200 || response.body.length > MAX_RESPONSE_CHARS) {
                DiaryDictationResult.Failed
            } else {
                val transcript = codec.transcript(response.body).trim()
                if (transcript.isBlank()) DiaryDictationResult.Failed else DiaryDictationResult.Recognized(transcript)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DiaryDictationResult.Failed
        } finally {
            audio.file.delete()
        }
    }

    override fun cancel() = capture.cancel()

    private companion object {
        const val MAX_AUDIO_BYTES = 675_000L
        const val MAX_RESPONSE_CHARS = 32_000
    }
}

interface DiaryDictationWireCodec {
    fun request(audio: CapturedDiaryDictationAudio, commandId: String): String
    fun transcript(body: String): String
}

object JsonDiaryDictationWireCodec : DiaryDictationWireCodec {
    override fun request(audio: CapturedDiaryDictationAudio, commandId: String): String = JSONObject()
        .put("audioMimeType", audio.mimeType)
        .put("audioBase64", Base64.getEncoder().encodeToString(audio.file.readBytes()))
        .put("commandId", commandId)
        .toString()

    override fun transcript(body: String): String = JSONObject(body).getString("transcript")
}
