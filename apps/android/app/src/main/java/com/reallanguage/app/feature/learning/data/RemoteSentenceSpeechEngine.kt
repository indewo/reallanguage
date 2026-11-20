package com.reallanguage.app.feature.learning.data

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.Locale

/** Whole-utterance audio, in memory only. The loader owns account/transport authorization. */
internal class RemoteSentenceSpeechEngine(
    private val load: suspend (String, Boolean) -> ByteArray,
    private val allowed: () -> Boolean,
) : SentenceSpeechEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pending: Job? = null
    private var player: MediaPlayer? = null
    private var source: MemorySpeechSource? = null
    private var completion: ((Boolean) -> Unit)? = null
    private var generation = 0L
    private var closed = false
    private val regeneration = mutableSetOf<String>()
    override fun requestRegeneration(text: String): Boolean {
        if (closed || !allowed() || text.isBlank()) return false
        regeneration.add(text)
        return true
    }

    override suspend fun awaitReady() = !closed

    override fun start(text: String, rate: Float, locale: Locale, finished: (Boolean) -> Unit): Boolean {
        stop()
        if (com.reallanguage.app.BuildConfig.DEBUG) android.util.Log.i("RlRemoteSpeech", "start closed=$closed allowed=${allowed()} locale=$locale rate=$rate")
        if (closed || !allowed() || text.isBlank() || locale.language != "en" || !rate.isFinite() || rate !in .5f..2f) return false
        completion = finished
        val lease = generation
        pending = scope.launch {
            var downloaded: ByteArray? = null
            try {
                val retry = regeneration.remove(text)
                if (com.reallanguage.app.BuildConfig.DEBUG) android.util.Log.i("RlRemoteSpeech", "load retry=$retry")
                downloaded = load(text, retry)
                ensureActive()
                if (lease != generation) return@launch
                if (!allowed()) { finish(false); return@launch }
                require(downloaded.isNotEmpty() && downloaded.size <= 16 * 1024 * 1024)
                val audio = MemorySpeechSource(downloaded)
                source = audio
                downloaded = null // Ownership transferred; release() wipes the audio.
                val active = MediaPlayer()
                player = active
                active.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                active.setDataSource(audio)
                active.setOnPreparedListener {
                    if (lease != generation) return@setOnPreparedListener
                    if (!allowed()) finish(false)
                    else try {
                        if (rate != 1f) it.playbackParams = it.playbackParams.setSpeed(rate)
                        it.start()
                    } catch (_: Exception) { finish(false) }
                }
                active.setOnCompletionListener { if (lease == generation) finish(allowed()) }
                active.setOnErrorListener { _, _, _ -> if (lease == generation) finish(false); true }
                active.prepareAsync()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (com.reallanguage.app.BuildConfig.DEBUG) android.util.Log.w("RlRemoteSpeech", "failed ${failure.javaClass.simpleName}")
                if (lease == generation) finish(false)
            }
            finally { downloaded?.fill(0) }
        }
        return true
    }

    private fun release() {
        player?.let { active ->
            active.setOnPreparedListener(null); active.setOnCompletionListener(null); active.setOnErrorListener(null)
            active.release()
        }
        player = null
        source?.close(); source = null
    }
    private fun finish(success: Boolean) {
        val callback = completion
        completion = null
        release()
        callback?.invoke(success)
    }
    override fun stop() {
        generation++
        pending?.cancel(); pending = null
        finish(false)
    }
    override fun close() { closed = true; stop(); scope.cancel() }
}

private class MemorySpeechSource(private val bytes: ByteArray) : MediaDataSource() {
    private var closed = false
    @Synchronized override fun getSize(): Long = if (closed) 0 else bytes.size.toLong()
    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed || position < 0 || position >= bytes.size) return -1
        val count = minOf(size, bytes.size - position.toInt())
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }
    @Synchronized override fun close() { if (!closed) { closed = true; bytes.fill(0) } }
}
