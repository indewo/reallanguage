package com.reallanguage.app.feature.learning.data

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal interface SentenceSpeechEngine {
    suspend fun awaitReady(): Boolean
    fun start(text: String, rate: Float, locale: Locale, finished: (Boolean) -> Unit): Boolean
    fun stop()
    fun close()
}

class AndroidSentenceSpeaker internal constructor(
    private val engine: SentenceSpeechEngine,
    private val isPlaybackAllowed: () -> Boolean = { true },
) {
    constructor(context: Context, isPlaybackAllowed: () -> Boolean = { true }) :
        this(NativeSentenceSpeechEngine(context.applicationContext), isPlaybackAllowed)
    @Volatile private var request = 0L
    private var activeCompletion: CompletableDeferred<Boolean>? = null

    fun start(text: String, speechRate: Float = 1f): Boolean {
        stop()
        if (!isPlaybackAllowed() || text.isBlank()) return false
        return engine.start(text, speechRate, Locale.US) {}
    }

    /** True means the engine reported onDone, not merely accepted a queue request. */
    suspend fun play(text: String, speechRate: Float = 1f, locale: Locale = Locale.US): Boolean =
        withContext(Dispatchers.Main.immediate) {
            stop()
            if (!isPlaybackAllowed() || text.isBlank()) return@withContext false
            val current = request
            val completion = CompletableDeferred<Boolean>()
            activeCompletion = completion
            var completed = false
            try {
                val ready = coroutineScope {
                    val initialization = async { engine.awaitReady() }
                    try {
                        withTimeoutOrNull(5_000) {
                            select { initialization.onAwait { it }; completion.onAwait { false } }
                        } == true
                    } finally { initialization.cancel() }
                }
                if (!ready || current != request || !isPlaybackAllowed()) return@withContext false
                val accepted = try {
                    engine.start(text, speechRate, locale) { success ->
                        completion.complete(success && current == request && isPlaybackAllowed())
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                if (!accepted) completion.complete(false)
                completed = withTimeoutOrNull(120_000) { completion.await() } == true &&
                    current == request && isPlaybackAllowed()
                completed
            } finally {
                if (activeCompletion === completion) {
                    activeCompletion = null
                    if (!completed) engine.stop()
                }
            }
        }

    fun stop() {
        request++
        val completion = activeCompletion
        activeCompletion = null
        completion?.complete(false)
        engine.stop()
    }
    fun close() { stop(); engine.close() }
}

private class NativeSentenceSpeechEngine(context: Context) : SentenceSpeechEngine {
    private val initialized = CompletableDeferred<Boolean>()
    private val callbacks = ConcurrentHashMap<String, (Boolean) -> Unit>()
    private val engine = TextToSpeech(context) { status -> initialized.complete(status == TextToSpeech.SUCCESS) }
    private var closed = false

    init {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { finish(utteranceId, true) }
            @Deprecated("Required abstract platform callback")
            override fun onError(utteranceId: String?) { finish(utteranceId, false) }
            override fun onError(utteranceId: String?, errorCode: Int) { finish(utteranceId, false) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { finish(utteranceId, false) }
        })
    }

    override suspend fun awaitReady() = initialized.await() && !closed

    override fun start(text: String, rate: Float, locale: Locale, finished: (Boolean) -> Unit): Boolean {
        if (closed || !initialized.isCompleted || text.isBlank() || text.length > TextToSpeech.getMaxSpeechInputLength()) return false
        val available = engine.voices.orEmpty()
        val voice = bestAvailableVoice(available, locale)
        if (com.reallanguage.app.BuildConfig.DEBUG) {
            android.util.Log.i("RlSpeechVoice", "selected=${voice?.name}, locale=${voice?.locale}, quality=${voice?.quality}, network=${voice?.isNetworkConnectionRequired}, requestedRate=$rate; available=" +
                available.filter { it.locale.language == locale.language }.joinToString { "${it.name}/${it.locale}/${it.quality}/network=${it.isNetworkConnectionRequired}" })
        }
        val languageReady = if (voice != null) engine.setVoice(voice) == TextToSpeech.SUCCESS
            else engine.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE
        if (!languageReady || engine.setSpeechRate(naturalRate(locale, rate)) != TextToSpeech.SUCCESS) return false
        val id = UUID.randomUUID().toString()
        callbacks[id] = finished
        val accepted = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS
        if (!accepted) callbacks.remove(id)
        return accepted
    }

    private fun finish(id: String?, success: Boolean) { if (id != null) callbacks.remove(id)?.invoke(success) }
    override fun stop() {
        engine.stop()
        callbacks.keys.toList().forEach { finish(it, false) }
    }
    override fun close() {
        closed = true
        initialized.complete(false)
        stop()
        engine.shutdown()
    }
}

/**
 * Prefer the requested accent and highest advertised quality among installed voices.
 * Offline wins ties, without excluding higher-quality network voices beforehand.
 */
internal fun bestAvailableVoice(voices: Set<Voice>, locale: Locale): Voice? = voices
    .asSequence()
    .filter { it.locale.language.equals(locale.language, ignoreCase = true) }
    .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty() }
    .sortedWith(
        compareByDescending<Voice> { it.locale.country.equals(locale.country, ignoreCase = true) }
            .thenByDescending { it.quality }
            .thenBy { it.isNetworkConnectionRequired }
            .thenBy { it.latency }
            .thenBy { it.name },
    )
    .firstOrNull()

/** Preserve the requested speed; slower teaching modes set their rate explicitly. */
internal fun naturalRate(locale: Locale, requested: Float): Float {
    val normalized = requested.coerceIn(.5f, 2f)
    return normalized
}
