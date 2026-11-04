package com.reallanguage.app.feature.learning.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.FullSentenceSpeakingRepository
import com.reallanguage.app.feature.learning.domain.FullSpeakingRecovery
import com.reallanguage.app.feature.learning.domain.ReadingFeedback
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackResult
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationCandidate
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationDecision
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationResult
import com.reallanguage.app.feature.learning.domain.SpeakingDecisionSaveResult
import com.reallanguage.app.feature.learning.domain.SpeakingTrainingMode
import com.reallanguage.app.feature.learning.domain.SpeakingTranscriptionResult
import java.util.UUID
import java.util.Base64
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

class AndroidFullSentenceSpeakingRepository(
    context: Context? = null,
    private val transport: HttpTransport,
    private val runtime: SessionRuntime,
    private val chapterId: String,
    private val targetTextEn: String,
    private val capture: ReadSpeakAudioCapture,
    private val codec: FullSentenceSpeakingWireCodec = JsonFullSentenceSpeakingWireCodec(),
    private val isCaptureAllowed: () -> Boolean = { true },
    recoveryJournalFactory: ((String, Int) -> FullSpeakingRecoveryJournal)? = null,
) : FullSentenceSpeakingRepository {
    private val applicationContext = context?.applicationContext
    private val ownerAccountId = runtime.accountId
    private val ownerAccountEpoch = runtime.accountEpoch
    private val independentMutex = Mutex()
    private var generation = 0L
    private var independentAttempt: IndependentAttempt? = null
    private var trainingAttempt: TrainingAttempt? = null
    private val durable = recoveryJournalFactory?.let { factory ->
        DurableFullSpeakingSession(chapterId, targetTextEn, capture, codec, transport,
            { runtime.accessToken.takeIf { hasOwnerSession() } }, isCaptureAllowed, factory)
    }
    override val requiresAttemptRecovery: Boolean get() = durable != null
    override val automaticallyResumePendingRequests: Boolean get() = durable != null
    override suspend fun restoreAttempt(sentenceId: String, attemptIndex: Int): FullSpeakingRecovery =
        durable?.restore(sentenceId, attemptIndex) ?: FullSpeakingRecovery.None
    override suspend fun prepareCapture(sentenceId: String, attemptIndex: Int): Boolean =
        durable?.prepare(sentenceId, attemptIndex) ?: true
    override fun currentRecordingId(): String? = durable?.recordingId()

    private class TrainingAttempt(
        val generation: Long,
        val sentenceId: String,
        val mode: SpeakingTrainingMode,
        val audio: CapturedReadSpeakAudio,
        var body: String? = null,
        var delivered: ReadingFeedbackResult.Ready? = null,
        var blocked: Boolean = false,
    )

    private class IndependentAttempt(
        val generation: Long,
        val sentenceId: String,
        val audio: CapturedReadSpeakAudio,
        var transcriptionBody: String? = null,
        var transcription: SpeakingTranscriptionResult.Ready? = null,
        var transcriptionBlocked: Boolean = false,
        var evaluation: EvaluationAttempt? = null,
    )

    private class EvaluationAttempt(
        val confirmedTranscript: String,
        val answerAudioPlayed: Boolean,
        var body: String? = null,
        var delivered: SpeakingEvaluationResult.Ready? = null,
        var blocked: Boolean = false,
    )

    @Synchronized
    override fun startCapture(): Boolean {
        durable?.let { return it.start() }
        if (!hasOwnerSession() || !isCaptureAllowed() || independentMutex.isLocked) return false
        generation++
        clearIndependentAttempt()
        clearTrainingAttempt()
        if (!capture.start()) return false
        if (!hasOwnerSession() || !isCaptureAllowed()) { capture.cancel(); return false }
        return true
    }

    @Synchronized
    override fun cancelCapture() {
        durable?.let { it.cancel(); return }
        generation++
        capture.cancel()
        clearIndependentAttempt()
        clearTrainingAttempt()
    }

    @Synchronized
    override fun hasPendingTranscriptionRetry(): Boolean = durable?.pending(FullSpeakingRecoveryPhase.TranscriptionPending) ?: (hasOwnerSession() &&
        !independentMutex.isLocked && independentAttempt?.let {
            it.generation == generation && it.transcription == null && !it.transcriptionBlocked &&
                it.transcriptionBody != null && it.audio.file.exists()
        } == true)

    @Synchronized
    override fun hasPendingEvaluationRetry(): Boolean = durable?.pending(FullSpeakingRecoveryPhase.EvaluationPending) ?: (hasOwnerSession() &&
        !independentMutex.isLocked && independentAttempt?.let { attempt ->
            attempt.generation == generation && attempt.audio.file.exists() &&
                attempt.evaluation?.let { it.delivered == null && !it.blocked && it.body != null } == true
        } == true)

    @Synchronized
    override fun hasPendingTrainingRetry(): Boolean = durable?.pending(FullSpeakingRecoveryPhase.TrainingPending) ?: (hasOwnerSession() && !independentMutex.isLocked &&
        trainingAttempt?.let {
            isCurrent(it) && !it.blocked && it.delivered == null && it.body != null && it.audio.file.exists()
        } == true)

    @Synchronized
    override fun technicalRetryBlocked(): Boolean = durable?.blocked() ?: (hasOwnerSession() && (independentAttempt?.let {
        it.generation == generation && (it.transcriptionBlocked || it.evaluation?.blocked == true)
    } == true || trainingAttempt?.let { isCurrent(it) && it.blocked } == true))

    override suspend fun trainingFeedback(
        sentenceId: String,
        mode: SpeakingTrainingMode,
    ): ReadingFeedbackResult {
        durable?.let { return it.training(sentenceId, mode) }
        currentCoroutineContext().ensureActive()
        val requestedGeneration = synchronized(this) { generation }
        if (!independentMutex.tryLock()) return ReadingFeedbackResult.Failed
        var attempt: TrainingAttempt? = null
        try {
            val current = synchronized(this) {
                if (!hasOwnerSession() || generation != requestedGeneration || independentAttempt != null) return@synchronized null
                trainingAttempt?.let { return@synchronized it.takeIf { saved -> saved.sentenceId == sentenceId && saved.mode == mode } }
                val audio = capture.finish() ?: return@synchronized null
                if (!hasOwnerSession() || generation != requestedGeneration) {
                    audio.file.delete()
                    return@synchronized null
                }
                TrainingAttempt(generation, sentenceId, mode, audio).also { trainingAttempt = it }
            } ?: return ReadingFeedbackResult.Failed
            attempt = current
            val body = synchronized(this) {
                if (!isCurrent(current)) return@synchronized null
                current.delivered?.let { return it }
                if (current.blocked || !current.audio.file.exists()) return@synchronized null
                current.body ?: codec.trainingRequest(chapterId, sentenceId, targetTextEn, current.audio)
                    .also { current.body = it }
            } ?: return ReadingFeedbackResult.Failed
            currentCoroutineContext().ensureActive()
            val request = synchronized(this) {
                if (!isCurrent(current)) return@synchronized null
                val token = runtime.accessToken ?: return@synchronized null
                HttpRequest("POST", "/api/v1/learning/reading-feedback", body, mapOf("Authorization" to "Bearer $token"))
            } ?: return ReadingFeedbackResult.Failed
            val response = transport.execute(request)
            currentCoroutineContext().ensureActive()
            if (!isCurrent(current)) return ReadingFeedbackResult.Failed
            if (response.statusCode != 200) {
                synchronized(this) { if (isCurrent(current)) current.blocked = blocksTechnicalRetry(response) }
                return ReadingFeedbackResult.Failed
            }
            val result = codec.trainingResult(response.body)
            currentCoroutineContext().ensureActive()
            return synchronized(this) {
                if (!isCurrent(current)) ReadingFeedbackResult.Failed
                else result.also {
                    if (it is ReadingFeedbackResult.Ready) {
                        current.delivered = it
                        current.audio.file.delete()
                        current.body = null
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ReadingFeedbackResult.Failed
        } finally {
            try { synchronized(this) {
                if (attempt != null && !isCurrent(attempt)) {
                    attempt.audio.file.delete()
                    if (trainingAttempt === attempt) clearTrainingAttempt()
                }
            } } finally { independentMutex.unlock() }
        }
    }

    override suspend fun transcribeIndependentRetelling(
        sentenceId: String,
    ): SpeakingTranscriptionResult {
        durable?.let { return it.transcribe(sentenceId) }
        currentCoroutineContext().ensureActive()
        val requestedGeneration = synchronized(this) { generation }
        // A concurrent tap must not queue an automatic second HTTP attempt after a failure.
        if (!independentMutex.tryLock()) return SpeakingTranscriptionResult.Failed
        var attempt: IndependentAttempt? = null
        try {
            val current = synchronized(this) {
                if (!hasOwnerSession() || generation != requestedGeneration || trainingAttempt != null) return@synchronized null
                independentAttempt?.let { return@synchronized it.takeIf { saved -> saved.sentenceId == sentenceId } }
                val audio = capture.finish() ?: return@synchronized null
                if (!hasOwnerSession() || generation != requestedGeneration) {
                    audio.file.delete()
                    return@synchronized null
                }
                IndependentAttempt(generation, sentenceId, audio).also { independentAttempt = it }
            } ?: return SpeakingTranscriptionResult.Failed
            attempt = current
            val body = synchronized(this) {
                if (!isCurrent(current)) return@synchronized null
                current.transcription?.let { return it }
                if (current.transcriptionBlocked || !current.audio.file.exists()) return@synchronized null
                current.transcriptionBody ?: codec.transcriptionRequest(chapterId, sentenceId, current.audio)
                    .also { current.transcriptionBody = it }
            } ?: return SpeakingTranscriptionResult.Failed
            val response = executeIndependent("/api/v1/learning/speaking-transcriptions", body, current)
                ?: return SpeakingTranscriptionResult.Failed
            if (response.statusCode != 200) {
                synchronized(this) {
                    if (isCurrent(current)) current.transcriptionBlocked = blocksTechnicalRetry(response)
                }
                return SpeakingTranscriptionResult.Failed
            }
            val result = codec.transcriptionResult(response.body)
            currentCoroutineContext().ensureActive()
            return synchronized(this) {
                if (!isCurrent(current)) SpeakingTranscriptionResult.Failed
                else result.also { if (it is SpeakingTranscriptionResult.Ready) current.transcription = it }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SpeakingTranscriptionResult.Failed
        } finally {
            try { discardStaleAttempt(attempt) } finally { independentMutex.unlock() }
        }
    }

    override suspend fun evaluateIndependentRetelling(
        sentenceId: String,
        confirmedTranscript: String,
        answerAudioPlayed: Boolean,
    ): SpeakingEvaluationResult {
        durable?.let { return it.evaluate(sentenceId, confirmedTranscript, answerAudioPlayed) }
        currentCoroutineContext().ensureActive()
        val requestedGeneration = synchronized(this) { generation }
        if (!independentMutex.tryLock()) return SpeakingEvaluationResult.Failed
        var attempt: IndependentAttempt? = null
        try {
            val current = synchronized(this) {
                independentAttempt?.takeIf {
                    isCurrent(it) && generation == requestedGeneration &&
                        it.sentenceId == sentenceId && it.transcription != null
                }
            } ?: return SpeakingEvaluationResult.Failed
            attempt = current
            val evaluation = synchronized(this) {
                if (!isCurrent(current)) return@synchronized null
                current.evaluation?.let {
                    return@synchronized it.takeIf { saved ->
                        saved.confirmedTranscript == confirmedTranscript && saved.answerAudioPlayed == answerAudioPlayed
                    }
                }
                EvaluationAttempt(confirmedTranscript, answerAudioPlayed).also { current.evaluation = it }
            } ?: return SpeakingEvaluationResult.Failed
            val body = synchronized(this) {
                if (!isCurrent(current)) return@synchronized null
                evaluation.delivered?.let { return it }
                if (evaluation.blocked || !current.audio.file.exists()) return@synchronized null
                evaluation.body ?: codec.evaluationRequest(
                    chapterId, sentenceId, targetTextEn, evaluation.confirmedTranscript,
                    evaluation.answerAudioPlayed, current.audio,
                ).also { evaluation.body = it }
            } ?: return SpeakingEvaluationResult.Failed
            val response = executeIndependent("/api/v1/learning/full-sentence-speaking-evaluations", body, current)
                ?: return SpeakingEvaluationResult.Failed
            if (response.statusCode != 200) {
                synchronized(this) {
                    if (isCurrent(current)) evaluation.blocked = blocksTechnicalRetry(response)
                }
                return SpeakingEvaluationResult.Failed
            }
            val result = codec.evaluationResult(response.body)
            currentCoroutineContext().ensureActive()
            return synchronized(this) {
                if (!isCurrent(current)) SpeakingEvaluationResult.Failed
                else result.also {
                    if (it is SpeakingEvaluationResult.Ready) {
                        evaluation.delivered = it
                        current.audio.file.delete()
                        current.transcriptionBody = null
                        evaluation.body = null
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SpeakingEvaluationResult.Failed
        } finally {
            try { discardStaleAttempt(attempt) } finally { independentMutex.unlock() }
        }
    }

    override suspend fun saveIndependentDecision(
        sentenceId: String,
        decision: SpeakingEvaluationDecision,
    ): SpeakingDecisionSaveResult = withContext(Dispatchers.IO) {
        if (!hasOwnerSession() || decision == SpeakingEvaluationDecision.INCONCLUSIVE)
            return@withContext SpeakingDecisionSaveResult.Failed
        val context = applicationContext ?: return@withContext SpeakingDecisionSaveResult.Failed
        try {
          FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
              check(hasOwnerSession())
              check(db.rawQuery(
                "select 1 from chapters c join fixed_sentences s on s.account_id=c.account_id and s.chapter_id=c.chapter_id where c.account_id=? and c.chapter_id=? and c.status!='COMPLETED' and s.unit_id=? and s.role='FOCUS' and s.text_en=?",
                arrayOf(ownerAccountId, chapterId, sentenceId, targetTextEn),
              ).use { it.moveToFirst() })
              check(db.insertWithOnConflict(
                "full_speaking_result_drafts",
                null,
                ContentValues().apply {
                    put("account_id", ownerAccountId)
                    put("chapter_id", chapterId)
                    put("sentence_id", sentenceId)
                    put("passed", if (decision == SpeakingEvaluationDecision.PASS) 1 else 0)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
              ) != -1L)
              check(hasOwnerSession())
              db.setTransactionSuccessful()
            } finally { db.endTransaction() }
          }
          SpeakingDecisionSaveResult.Saved
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { SpeakingDecisionSaveResult.Failed }
    }

    private fun hasOwnerSession() = ownerAccountId != null && runtime.accountId == ownerAccountId &&
        runtime.accountEpoch == ownerAccountEpoch && runtime.accessToken != null

    private suspend fun executeIndependent(path: String, body: String, attempt: IndependentAttempt): HttpResponse? {
        currentCoroutineContext().ensureActive()
        val request = synchronized(this) {
            if (!isCurrent(attempt)) return@synchronized null
            val token = runtime.accessToken ?: return@synchronized null
            HttpRequest("POST", path, body, mapOf("Authorization" to "Bearer $token"))
        } ?: return null
        if (!isCurrent(attempt)) return null
        val response = transport.execute(request)
        currentCoroutineContext().ensureActive()
        return response.takeIf { isCurrent(attempt) }
    }

    @Synchronized
    private fun isCurrent(attempt: IndependentAttempt): Boolean = hasOwnerSession() &&
        generation == attempt.generation && independentAttempt === attempt

    @Synchronized
    private fun discardStaleAttempt(attempt: IndependentAttempt?) {
        // start/cancel already owns cleanup of superseded attempts; never touch the new capture.
        if (!hasOwnerSession() || (attempt != null && independentAttempt === attempt && !isCurrent(attempt))) {
            clearIndependentAttempt()
        }
    }

    private fun clearIndependentAttempt() {
        independentAttempt?.audio?.file?.delete()
        independentAttempt = null
    }

    @Synchronized
    private fun isCurrent(attempt: TrainingAttempt): Boolean = hasOwnerSession() &&
        generation == attempt.generation && trainingAttempt === attempt

    private fun clearTrainingAttempt() {
        trainingAttempt?.audio?.file?.delete()
        trainingAttempt = null
    }

    private fun blocksTechnicalRetry(response: HttpResponse): Boolean {
        if (response.statusCode == 410) return true
        if (response.statusCode != 409) return false
        // The production error envelope distinguishes processing from terminal/reconciliation errors.
        // An unrecognized 409 stays blocked, with its original command retained rather than replaced.
        return try {
            val error = JSONObject(response.body).getJSONObject("error")
            error.opt("code") != "AI_OPERATION_PROCESSING" || error.opt("retryable") != true
        } catch (_: Exception) {
            true
        }
    }
}

interface FullSentenceSpeakingWireCodec {
    fun trainingRequest(chapterId: String, sentenceId: String, targetTextEn: String, audio: CapturedReadSpeakAudio): String
    fun trainingResult(body: String): ReadingFeedbackResult
    fun transcriptionRequest(chapterId: String, sentenceId: String, audio: CapturedReadSpeakAudio): String
    fun transcriptionResult(body: String): SpeakingTranscriptionResult
    fun evaluationRequest(chapterId: String, sentenceId: String, targetTextEn: String, confirmedTranscript: String, answerAudioPlayed: Boolean, audio: CapturedReadSpeakAudio): String
    fun evaluationResult(body: String): SpeakingEvaluationResult
    fun evaluationRequestFromTranscription(originalRequest: String, targetTextEn: String,
        confirmedTranscript: String, answerAudioPlayed: Boolean): String =
        error("This codec does not support durable original-audio custody")
}

class JsonFullSentenceSpeakingWireCodec : FullSentenceSpeakingWireCodec {
    override fun evaluationRequestFromTranscription(originalRequest: String, targetTextEn: String,
        confirmedTranscript: String, answerAudioPlayed: Boolean): String = JSONObject(originalRequest).let { original ->
        JSONObject().put("chapterId", original.speechString("chapterId"))
            .put("sentenceId", original.speechString("sentenceId"))
            .put("audioMimeType", original.speechString("audioMimeType"))
            .put("audioBase64", original.speechString("audioBase64"))
            .put("commandId", UUID.randomUUID().toString()).put("targetTextEn", targetTextEn)
            .put("confirmedTranscript", confirmedTranscript).put("targetHidden", true)
            .put("answerAudioPlayed", answerAudioPlayed).put("userTranscriptConfirmed", true).toString()
    }
    override fun trainingRequest(chapterId: String, sentenceId: String, targetTextEn: String, audio: CapturedReadSpeakAudio) =
        audioBody(chapterId, sentenceId, audio)
            .put("targetTextEn", targetTextEn).put("completionSignal", "MANUAL_CONFIRMATION").toString()
    override fun trainingResult(body: String) = JSONObject(body).readingFeedbackWire().let {
        ReadingFeedbackResult.Ready(ReadingFeedback(
            it.feedbackZh,
            it.action,
            it.feedbackConfidence,
            it.feedbackItems,
        ))
    }
    override fun transcriptionRequest(chapterId: String, sentenceId: String, audio: CapturedReadSpeakAudio) =
        audioBody(chapterId, sentenceId, audio).toString()
    override fun transcriptionResult(body: String) =
        SpeakingTranscriptionResult.Ready(JSONObject(body).speakingTranscriptionWire())
    override fun evaluationRequest(chapterId: String, sentenceId: String, targetTextEn: String, confirmedTranscript: String, answerAudioPlayed: Boolean, audio: CapturedReadSpeakAudio) =
        audioBody(chapterId, sentenceId, audio).put("targetTextEn", targetTextEn)
            .put("confirmedTranscript", confirmedTranscript).put("targetHidden", true)
            .put("answerAudioPlayed", answerAudioPlayed).put("userTranscriptConfirmed", true).toString()
    override fun evaluationResult(body: String) = JSONObject(body).let { json ->
        json.speechConfidence("pronunciationConfidence")
        SpeakingEvaluationResult.Ready(SpeakingEvaluationCandidate(
            SpeakingEvaluationDecision.valueOf(json.speechString("decision")),
            json.speechCompletenessScore(), json.speechBoolean("coreMeaningRecognizable"),
            json.speechBoolean("pronunciationIntelligible"), json.speechString("feedbackZh"),
        ))
    }
    private fun audioBody(chapterId: String, sentenceId: String, audio: CapturedReadSpeakAudio) = JSONObject()
        .put("chapterId", chapterId).put("sentenceId", sentenceId).put("audioMimeType", audio.mimeType)
        .put("audioBase64", Base64.getEncoder().encodeToString(audio.file.readBytes()))
        .put("commandId", UUID.randomUUID().toString())
}
