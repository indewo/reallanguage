package com.reallanguage.app.feature.learning.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.learning.domain.ReadSpeakCompletionSignal
import com.reallanguage.app.feature.learning.domain.ReadSpeakFeedbackResult
import com.reallanguage.app.feature.learning.domain.ReadSpeakFeedbackRecovery
import com.reallanguage.app.feature.learning.domain.ReadSpeakRepository
import com.reallanguage.app.feature.formation.data.FormationDatabase
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class AndroidReadSpeakRepository(
    private val transport: HttpTransport,
    private val runtime: SessionRuntime,
    private val chapterId: String,
    private val targetTextEn: String,
    private val commandId: String,
    private val capture: ReadSpeakAudioCapture,
    private val stepStore: LearningStepStore,
    private val codec: ReadSpeakWireCodec = JsonReadSpeakWireCodec(),
    private val isCaptureAllowed: () -> Boolean = { true },
    private val recoveryJournal: ReadSpeakRecoveryJournal? = null,
    private val recoverySentenceId: String? = null,
) : ReadSpeakRepository {
    init { require(recoveryJournal == null || !recoverySentenceId.isNullOrBlank()) }
    override val requiresFeedbackRecovery: Boolean get() = recoveryJournal != null
    private var recoveryLoaded = recoveryJournal == null
    private var recoveryUnavailable = false
    private var recoveryRevision: Long? = null
    private var savedRecovery: ReadSpeakRecoverySnapshot? = null
    private var unacknowledgedRecovery: ReadSpeakRecoverySnapshot? = null
    private var capturePrepared = recoveryJournal == null
    private val ownerAccountId = runtime.accountId
    private val ownerAccountEpoch = runtime.accountEpoch
    private val feedbackMutex = Mutex()
    private var generation = 0L
    private var capturing = false
    private var pending: FeedbackAttempt? = null

    private class FeedbackAttempt(
        val generation: Long,
        val sentenceId: String,
        var signal: ReadSpeakCompletionSignal,
        val audio: CapturedReadSpeakAudio?,
        val commandId: String,
        var requestBody: String? = null,
        var delivered: ReadSpeakFeedbackResult? = null,
        var completed: Boolean = false,
        var blocked: Boolean = false,
    )

    private fun ownerIsActive(): Boolean = ownerAccountId != null &&
        runtime.accountId == ownerAccountId && runtime.accountEpoch == ownerAccountEpoch && runtime.accessToken != null

    /** Durable recovery is local only. A pending result requires a separate explicit retry. */
    override suspend fun restoreFeedback(): ReadSpeakFeedbackRecovery {
        if (recoveryJournal == null) return ReadSpeakFeedbackRecovery.None
        if (!feedbackMutex.tryLock()) return ReadSpeakFeedbackRecovery.Unavailable
        return try { restoreFeedbackLocked() } finally { feedbackMutex.unlock() }
    }

    private suspend fun restoreFeedbackLocked(): ReadSpeakFeedbackRecovery {
        val journal = recoveryJournal ?: return ReadSpeakFeedbackRecovery.None
        if (!ownerIsActive()) return ReadSpeakFeedbackRecovery.Unavailable
        if (!recoveryLoaded) {
            val epoch = generation
            val loaded = journal.load()
            currentCoroutineContext().ensureActive()
            if (!ownerIsActive() || generation != epoch) return ReadSpeakFeedbackRecovery.Unavailable
            recoveryUnavailable = loaded == ReadSpeakRecoveryLoad.Unavailable
            when (loaded) {
                ReadSpeakRecoveryLoad.Unavailable -> return ReadSpeakFeedbackRecovery.Unavailable
                ReadSpeakRecoveryLoad.Missing -> { recoveryRevision = null; savedRecovery = null }
                is ReadSpeakRecoveryLoad.Loaded -> {
                    recoveryRevision = loaded.revision
                    savedRecovery = loaded.snapshot
                    val snapshot = loaded.snapshot
                    pending = if (snapshot.phase == ReadSpeakRecoveryPhase.Cleared) null else FeedbackAttempt(
                        generation, requireNotNull(recoverySentenceId), snapshot.signal, null, snapshot.commandId,
                        snapshot.requestBody, snapshot.delivered,
                        completed = snapshot.phase == ReadSpeakRecoveryPhase.Completed,
                        blocked = snapshot.phase == ReadSpeakRecoveryPhase.Blocked,
                    )
                }
            }
            unacknowledgedRecovery = null
            recoveryLoaded = true
        }
        if (recoveryUnavailable) return ReadSpeakFeedbackRecovery.Unavailable
        val attempt = pending ?: return ReadSpeakFeedbackRecovery.None
        if (attempt.blocked) return ReadSpeakFeedbackRecovery.Blocked
        if (!attempt.completed) return ReadSpeakFeedbackRecovery.Pending(attempt.signal)
        val result = attempt.delivered ?: return ReadSpeakFeedbackRecovery.Unavailable
        // Re-establish the local completion after reading the journal; the journal is not SQL authority.
        val trainingComplete = result == ReadSpeakFeedbackResult.CompletedWithoutAudio ||
            (result is ReadSpeakFeedbackResult.Ready &&
                (attempt.signal != ReadSpeakCompletionSignal.RECOGNITION_COMPLETE || result.contentComplete))
        if (trainingComplete && !saveTraining(requireNotNull(ownerAccountId), attempt.sentenceId,
                attempt.generation, attempt.commandId)) return ReadSpeakFeedbackRecovery.Unavailable
        return ReadSpeakFeedbackRecovery.Completed(attempt.signal, result)
    }

    /** Called off the rendering path before a new microphone start, never for an original retry. */
    suspend fun prepareCapture(): Boolean {
        if (recoveryJournal == null) return true
        if (!feedbackMutex.tryLock()) return false
        return try {
            if (!ownerIsActive() || !isCaptureAllowed()) return false
            when (restoreFeedbackLocked()) {
                ReadSpeakFeedbackRecovery.Unavailable, is ReadSpeakFeedbackRecovery.Pending -> false
                else -> {
                    val previous = pending
                    if (previous != null && !persist(previous, ReadSpeakRecoveryPhase.Cleared)) return false
                    if (!ownerIsActive() || !isCaptureAllowed()) return false
                    pending = null
                    capturePrepared = true
                    true
                }
            }
        } finally { feedbackMutex.unlock() }
    }

    @Synchronized
    fun startCapture(): Boolean {
        if (!ownerIsActive()) { cancelCapture(); return false }
        if (!isCaptureAllowed()) return false
        if (!recoveryLoaded || recoveryUnavailable || !capturePrepared) return false
        if (capturing) return true
        if (!capture.start()) return false
        if (!ownerIsActive() || !isCaptureAllowed()) { capture.cancel(); return false }
        generation++
        pending?.audio?.file?.delete()
        pending = null
        capturing = true
        capturePrepared = recoveryJournal == null
        return true
    }

    @Synchronized
    override fun cancelCapture() {
        generation++
        capturing = false
        capture.cancel()
        pending?.audio?.file?.delete()
        pending = null
        if (recoveryJournal != null) {
            recoveryLoaded = false
            capturePrepared = false
        }
    }

    @Synchronized
    override fun hasPendingFeedbackRetry(): Boolean = ownerIsActive() && pending?.let {
        !it.completed && !it.blocked && (it.delivered != null || it.requestBody != null || it.audio?.file?.exists() == true)
    } == true

    @Synchronized
    override fun isFeedbackRetryBlocked(): Boolean = ownerIsActive() && pending?.blocked == true

    suspend fun awaitRecognitionCandidate(): Boolean = capture.awaitSpeechEnd()

    override suspend fun feedback(
        sentenceId: String,
        completionSignal: ReadSpeakCompletionSignal,
    ): ReadSpeakFeedbackResult = feedbackRequest(sentenceId, completionSignal, retryOriginal = false)

    override suspend fun retryFeedback(sentenceId: String, completionSignal: ReadSpeakCompletionSignal): ReadSpeakFeedbackResult =
        feedbackRequest(sentenceId, completionSignal, retryOriginal = true)

    private suspend fun feedbackRequest(sentenceId: String, completionSignal: ReadSpeakCompletionSignal,
        retryOriginal: Boolean): ReadSpeakFeedbackResult {
        currentCoroutineContext().ensureActive()
        val requestedGeneration = synchronized(this) { generation }
        if (!feedbackMutex.tryLock()) return feedbackMutex.withLock {
            // Concurrent taps share the existing outcome. They do not authorize a new request
            // after the first one fails, nor may they treat an unsaved candidate as completion.
            synchronized(this) {
                pending?.takeIf { isCurrent(it) && it.generation == requestedGeneration &&
                    it.sentenceId == sentenceId && it.signal == completionSignal && it.completed }
                    ?.delivered ?: ReadSpeakFeedbackResult.Failed
            }
        }
        return try { feedbackLocked(sentenceId, completionSignal, retryOriginal) } finally { feedbackMutex.unlock() }
    }

    private suspend fun feedbackLocked(
        sentenceId: String,
        completionSignal: ReadSpeakCompletionSignal,
        retryOriginal: Boolean,
    ): ReadSpeakFeedbackResult = run locked@{
        if (!ownerIsActive()) return@locked failedAndCancel()
        val token = runtime.accessToken ?: return@locked failedAndCancel()
        val accountId = ownerAccountId ?: return@locked failedAndCancel()
        if (recoveryJournal != null && (sentenceId != recoverySentenceId ||
                (!recoveryLoaded && restoreFeedbackLocked() == ReadSpeakFeedbackRecovery.Unavailable) || recoveryUnavailable))
            return@locked ReadSpeakFeedbackResult.Failed
        val epoch = synchronized(this) { generation }
        val previous = synchronized(this) { pending }
        if (retryOriginal && previous == null) return@locked ReadSpeakFeedbackResult.Failed
        if (previous != null && completionSignal == ReadSpeakCompletionSignal.MANUAL_CONFIRMATION &&
            isCurrent(previous) && previous.sentenceId == sentenceId && previous.completed &&
            previous.delivered == ReadSpeakFeedbackResult.CompletedWithoutAudio
        ) return@locked ReadSpeakFeedbackResult.CompletedWithoutAudio
        val manualFallback = synchronized(this) {
            pending?.takeIf {
                !retryOriginal && completionSignal == ReadSpeakCompletionSignal.MANUAL_CONFIRMATION &&
                    !capturing && (!it.completed || (it.signal == ReadSpeakCompletionSignal.RECOGNITION_COMPLETE &&
                        (it.delivered as? ReadSpeakFeedbackResult.Ready)?.contentComplete == false)) && it.sentenceId == sentenceId
            }
        }
        // An explicit confirmation after failed feedback must remain a training-only exit.
        // It is not another paid evaluation of the retained recording.
        if (manualFallback != null) {
            val manualCommand = manualFallback.commandId
            manualFallback.signal = ReadSpeakCompletionSignal.MANUAL_CONFIRMATION
            manualFallback.delivered = ReadSpeakFeedbackResult.CompletedWithoutAudio
            manualFallback.completed = false
            manualFallback.blocked = false
            if (!persist(manualFallback, ReadSpeakRecoveryPhase.Delivered)) return@locked ReadSpeakFeedbackResult.Failed
            if (!saveTraining(accountId, sentenceId, epoch, manualCommand)) return@locked ReadSpeakFeedbackResult.Failed
            if (!isCurrent(manualFallback)) return@locked rejectStale(manualFallback)
            if (!persist(manualFallback, ReadSpeakRecoveryPhase.Completed)) return@locked ReadSpeakFeedbackResult.Failed
            manualFallback.audio?.file?.delete()
            manualFallback.completed = true
            return@locked ReadSpeakFeedbackResult.CompletedWithoutAudio
        }
        if (previous != null && (previous.sentenceId != sentenceId || previous.signal != completionSignal)) {
            return@locked ReadSpeakFeedbackResult.Failed
        }
        val attempt = synchronized(this) {
            pending?.takeIf { it.sentenceId == sentenceId && it.signal == completionSignal }
                ?: run {
                    val audio = if (capturing || pending == null) {
                        capturing = false
                        capture.finish()
                    } else pending?.audio?.takeIf { it.file.exists() }
                    audio?.let {
                        FeedbackAttempt(generation, sentenceId, completionSignal, it,
                            UUID.nameUUIDFromBytes("$commandId:$generation:$sentenceId:${completionSignal.name}".toByteArray()).toString(),
                        ).also { created -> pending = created }
                    }
                }
        }
        if (attempt == null) {
            capture.cancel()
            return@locked if (completionSignal == ReadSpeakCompletionSignal.MANUAL_CONFIRMATION &&
                saveTraining(accountId, sentenceId, epoch, commandId)
            ) ReadSpeakFeedbackResult.CompletedWithoutAudio else ReadSpeakFeedbackResult.Failed
        }
        if (!isCurrent(attempt)) return@locked rejectStale(attempt)
        if (attempt.completed) return@locked attempt.delivered ?: ReadSpeakFeedbackResult.Failed
        if (attempt.blocked) return@locked ReadSpeakFeedbackResult.Failed
        try {
            val result = attempt.delivered ?: run {
                val body = attempt.requestBody ?: codec.request(chapterId, sentenceId, targetTextEn,
                    completionSignal, requireNotNull(attempt.audio), attempt.commandId).also { attempt.requestBody = it }
                if (!persist(attempt, ReadSpeakRecoveryPhase.Pending)) return@locked ReadSpeakFeedbackResult.Failed
                currentCoroutineContext().ensureActive()
                if (!isCurrent(attempt)) return@locked rejectStale(attempt)
                val response = transport.execute(HttpRequest(
                    method = "POST", path = "/api/v1/learning/reading-feedback", body = body,
                    headers = mapOf("Authorization" to "Bearer $token"),
                ))
                currentCoroutineContext().ensureActive()
                if (!isCurrent(attempt)) return@locked rejectStale(attempt)
                if (response.statusCode != 200) {
                    val processing = response.statusCode == 409 && runCatching {
                        val error = JSONObject(response.body).getJSONObject("error")
                        error.opt("code") == "AI_OPERATION_PROCESSING" && error.opt("retryable") == true
                    }.getOrDefault(false)
                    if (!processing && response.statusCode !in setOf(401, 408, 429) && response.statusCode !in 500..599) {
                        synchronized(this) { if (pending === attempt) {
                            // Keep a terminal identity so another completion callback cannot
                            // finish capture again or manufacture a replacement command.
                            attempt.blocked = true
                        } }
                        if (persist(attempt, ReadSpeakRecoveryPhase.Blocked)) {
                            attempt.audio?.file?.delete()
                            attempt.requestBody = null
                        }
                    }
                    return@locked ReadSpeakFeedbackResult.Failed
                }
                val decoded = codec.result(response.body)
                currentCoroutineContext().ensureActive()
                synchronized(this) {
                    if (!isCurrent(attempt)) return@locked rejectStale(attempt)
                    decoded.also {
                        attempt.delivered = it
                    }
                }
            }
            if (!isCurrent(attempt)) return@locked rejectStale(attempt)
            if (result == ReadSpeakFeedbackResult.Failed) {
                attempt.delivered = null
                return@locked ReadSpeakFeedbackResult.Failed
            }
            if (!persist(attempt, ReadSpeakRecoveryPhase.Delivered)) return@locked ReadSpeakFeedbackResult.Failed
            attempt.audio?.file?.delete()
            val mayComplete = (result == ReadSpeakFeedbackResult.CompletedWithoutAudio &&
                completionSignal == ReadSpeakCompletionSignal.MANUAL_CONFIRMATION) ||
                (result is ReadSpeakFeedbackResult.Ready &&
                    (completionSignal != ReadSpeakCompletionSignal.RECOGNITION_COMPLETE || result.contentComplete))
            if (mayComplete && !saveTraining(accountId, sentenceId, attempt.generation, attempt.commandId)) {
                ReadSpeakFeedbackResult.Failed
            } else if (isCurrent(attempt)) {
                if (!persist(attempt, ReadSpeakRecoveryPhase.Completed)) return@locked ReadSpeakFeedbackResult.Failed
                attempt.completed = true
                result
            } else rejectStale(attempt)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (isCurrent(attempt)) ReadSpeakFeedbackResult.Failed else rejectStale(attempt) }
    }

    @Synchronized
    private fun isCurrent(attempt: FeedbackAttempt) = ownerIsActive() &&
        generation == attempt.generation && pending === attempt

    @Synchronized
    private fun rejectStale(attempt: FeedbackAttempt): ReadSpeakFeedbackResult {
        attempt.audio?.file?.delete()
        if (pending === attempt) pending = null
        return ReadSpeakFeedbackResult.Failed
    }

    private suspend fun saveTraining(accountId: String, sentenceId: String, epoch: Long, attemptCommandId: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(this@AndroidReadSpeakRepository) {
            if (!ownerIsActive() || generation != epoch) return@synchronized false
            runCatching { stepStore.completeWhileAuthorized(accountId, chapterId, sentenceId, "READ_SPEAK", attemptCommandId) {
                ownerIsActive() && generation == epoch
            } }.getOrDefault(false)
        }
    }

    private fun failedAndCancel(): ReadSpeakFeedbackResult {
        cancelCapture()
        return ReadSpeakFeedbackResult.Failed
    }

    private suspend fun persist(attempt: FeedbackAttempt, phase: ReadSpeakRecoveryPhase): Boolean {
        val journal = recoveryJournal ?: return isCurrent(attempt)
        if (!isCurrent(attempt)) return false
        unacknowledgedRecovery?.let { uncertain ->
            // A lost write acknowledgement is not permission to follow an arbitrary newer writer.
            when (val stored = journal.load()) {
                ReadSpeakRecoveryLoad.Unavailable -> return false
                ReadSpeakRecoveryLoad.Missing -> if (recoveryRevision != null) return false
                is ReadSpeakRecoveryLoad.Loaded -> when {
                    stored.revision == (recoveryRevision ?: -1L) + 1L && stored.snapshot == uncertain -> {
                        recoveryRevision = stored.revision
                        savedRecovery = uncertain
                    }
                    stored.revision == recoveryRevision && stored.snapshot == savedRecovery -> Unit
                    else -> return false
                }
            }
            if (!isCurrent(attempt)) return false
            unacknowledgedRecovery = null
        }
        val snapshot = ReadSpeakRecoverySnapshot(attempt.signal, attempt.commandId,
            requestBody = if (phase == ReadSpeakRecoveryPhase.Pending) attempt.requestBody else null,
            delivered = if (phase in setOf(ReadSpeakRecoveryPhase.Delivered, ReadSpeakRecoveryPhase.Completed)) attempt.delivered else null,
            phase = phase)
        if (savedRecovery == snapshot) {
            // Re-check even an identical retry: the chapter, key, or account may have been revoked.
            val current = journal.load()
            return current == ReadSpeakRecoveryLoad.Loaded(requireNotNull(recoveryRevision), snapshot) && isCurrent(attempt)
        }
        unacknowledgedRecovery = snapshot
        val revision = journal.save(recoveryRevision, snapshot) ?: return false
        currentCoroutineContext().ensureActive()
        if (!isCurrent(attempt)) return false
        recoveryRevision = revision
        savedRecovery = snapshot
        unacknowledgedRecovery = null
        return true
    }
}

fun interface LearningStepStore {
    fun complete(
        accountId: String,
        chapterId: String,
        sentenceId: String,
        step: String,
        commandId: String,
    ): Boolean

    /** Adapters retain pre/post checks; the SQLite implementation also checks inside its transaction. */
    fun completeWhileAuthorized(
        accountId: String,
        chapterId: String,
        sentenceId: String,
        step: String,
        commandId: String,
        isAuthorized: () -> Boolean,
    ): Boolean = isAuthorized() && complete(accountId, chapterId, sentenceId, step, commandId) && isAuthorized()
}

class AndroidLearningStepStore(context: Context) : LearningStepStore {
    private val context = context.applicationContext

    override fun complete(
        accountId: String,
        chapterId: String,
        sentenceId: String,
        step: String,
        commandId: String,
    ): Boolean = completeWhileAuthorized(accountId, chapterId, sentenceId, step, commandId) { true }

    override fun completeWhileAuthorized(
        accountId: String,
        chapterId: String,
        sentenceId: String,
        step: String,
        commandId: String,
        isAuthorized: () -> Boolean,
    ): Boolean = runCatching {
        check(isAuthorized())
        require(step in setOf("READ_SPEAK", "HANDWRITING_COPY", "AUDIO_DICTATION", "READ_WRITE_CORRECTION"))
        require(accountId.isNotBlank() && commandId.isNotBlank())
        FormationDatabase(context).use { helper ->
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
        check(isAuthorized())
        check(db.rawQuery(
            "select 1 from chapters c join fixed_sentences s on s.account_id=c.account_id and s.chapter_id=c.chapter_id where c.account_id=? and c.chapter_id=? and c.status!='COMPLETED' and s.unit_id=? and s.role='FOCUS'",
            arrayOf(accountId, chapterId, sentenceId),
        ).use { it.moveToFirst() })
        val values = ContentValues().apply {
            put("account_id", accountId)
            put("chapter_id", chapterId)
            put("sentence_id", sentenceId)
            put("step", step)
            put("status", "COMPLETED")
            put("command_id", commandId)
        }
        val saved = db.insertWithOnConflict(
            "learning_steps",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L || db.alreadyCompleted(accountId, chapterId, sentenceId, step)
        check(saved)
        if (step == "READ_WRITE_CORRECTION") {
            val completion = ContentValues(values).apply {
                put("step", "AUDIO_DICTATION")
                put("command_id", UUID.nameUUIDFromBytes("correction-dictation:$commandId".toByteArray()).toString())
            }
            check(db.insertWithOnConflict("learning_steps", null, completion, SQLiteDatabase.CONFLICT_IGNORE) != -1L ||
                db.alreadyCompleted(accountId, chapterId, sentenceId, "AUDIO_DICTATION"))
        }
        check(isAuthorized())
        db.setTransactionSuccessful()
        true
        } finally { db.endTransaction() }
        }
    }.getOrDefault(false)

    private fun SQLiteDatabase.alreadyCompleted(
        accountId: String,
        chapterId: String,
        sentenceId: String,
        step: String,
    ): Boolean = rawQuery(
        "select 1 from learning_steps where account_id=? and chapter_id=? and sentence_id=? and step=?",
        arrayOf(accountId, chapterId, sentenceId, step),
    ).use { it.moveToFirst() }
}

data class CapturedReadSpeakAudio(val file: File, val mimeType: String = "audio/mp4")

interface ReadSpeakAudioCapture {
    fun start(): Boolean
    fun finish(): CapturedReadSpeakAudio?
    fun cancel()
    suspend fun awaitSpeechEnd(): Boolean = false
}

internal fun accountLearningAttemptsDirectory(filesDir: File, accountId: String): File {
    val key = MessageDigest.getInstance("SHA-256")
        .digest("reallanguage:learning-attempts:$accountId".toByteArray())
        .joinToString("") { "%02x".format(it) }
    return File(File(filesDir, "learning-attempts"), key)
}

class AndroidMediaRecorderReadSpeakCapture(
    context: Context,
    private val runtime: SessionRuntime,
) : ReadSpeakAudioCapture {
    private val applicationContext = context.applicationContext
    private var recorder: MediaRecorder? = null
    private var output: File? = null

    @Synchronized
    override fun start(): Boolean {
        if (recorder != null) return true
        val accountId = runtime.accountId ?: return false
        val directory = accountLearningAttemptsDirectory(applicationContext.filesDir, accountId).apply { mkdirs() }
        val destination = File(directory, "${UUID.randomUUID()}.m4a")
        return try {
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(applicationContext)
            } else {
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
    override fun finish(): CapturedReadSpeakAudio? {
        val destination = output ?: return null
        return try {
            recorder?.stop()
            release(deleteOutput = false)
            destination.takeIf { it.isFile && it.length() > 0 }?.let(::CapturedReadSpeakAudio)
        } catch (_: Exception) {
            release(deleteOutput = true)
            null
        }
    }

    @Synchronized
    override fun cancel() = release(deleteOutput = true)

    override suspend fun awaitSpeechEnd(): Boolean {
        var heardSpeech = false
        var quietSamples = 0
        repeat(MAX_LISTEN_SAMPLES) {
            delay(AMPLITUDE_SAMPLE_MILLISECONDS)
            val amplitude = synchronized(this) {
                runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
            }
            if (amplitude >= SPEECH_AMPLITUDE_THRESHOLD) {
                heardSpeech = true
                quietSamples = 0
            } else if (heardSpeech) {
                quietSamples += 1
                if (quietSamples >= REQUIRED_QUIET_SAMPLES) return true
            }
        }
        return heardSpeech
    }

    private fun release(deleteOutput: Boolean) {
        runCatching { recorder?.release() }
        recorder = null
        if (deleteOutput) output?.delete()
        output = null
    }

    private companion object {
        const val AMPLITUDE_SAMPLE_MILLISECONDS = 200L
        const val MAX_LISTEN_SAMPLES = 100
        const val REQUIRED_QUIET_SAMPLES = 6
        const val SPEECH_AMPLITUDE_THRESHOLD = 1_500
    }
}

interface ReadSpeakWireCodec {
    fun request(
        chapterId: String,
        sentenceId: String,
        targetTextEn: String,
        completionSignal: ReadSpeakCompletionSignal,
        audio: CapturedReadSpeakAudio,
        commandId: String,
    ): String

    fun result(body: String): ReadSpeakFeedbackResult
}

class JsonReadSpeakWireCodec : ReadSpeakWireCodec {
    override fun request(
        chapterId: String,
        sentenceId: String,
        targetTextEn: String,
        completionSignal: ReadSpeakCompletionSignal,
        audio: CapturedReadSpeakAudio,
        commandId: String,
    ): String = JSONObject()
        .put("chapterId", chapterId)
        .put("sentenceId", sentenceId)
        .put("targetTextEn", targetTextEn)
        .put("completionSignal", completionSignal.name)
        .put("audioMimeType", audio.mimeType)
        .put("audioBase64", Base64.encodeToString(audio.file.readBytes(), Base64.NO_WRAP))
        .put("commandId", commandId)
        .toString()

    override fun result(body: String): ReadSpeakFeedbackResult = JSONObject(body).readingFeedbackWire().let {
        ReadSpeakFeedbackResult.Ready(
            feedbackZh = it.feedbackZh,
            contentComplete = it.contentComplete,
        )
    }
}
