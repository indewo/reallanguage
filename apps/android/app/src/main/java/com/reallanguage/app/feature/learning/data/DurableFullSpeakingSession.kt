package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.core.network.*
import com.reallanguage.app.feature.learning.domain.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject

/** Ordinary LR-06 request custody. Review sessions deliberately retain their existing restart rule. */
internal class DurableFullSpeakingSession(
    private val chapterId: String,
    private val targetTextEn: String,
    private val capture: ReadSpeakAudioCapture,
    private val codec: FullSentenceSpeakingWireCodec,
    private val transport: HttpTransport,
    private val currentToken: () -> String?,
    private val isCaptureAllowed: () -> Boolean,
    private val journalFactory: (String, Int) -> FullSpeakingRecoveryJournal,
) {
    private val mutex = Mutex()
    private var generation = 0L
    private var authorizedCapture = false
    private var recording = false
    private var audio: CapturedReadSpeakAudio? = null
    private var bound: Bound? = null

    private class Bound(val sentenceId: String, val round: Int, val journal: FullSpeakingRecoveryJournal) {
        var stored: FullSpeakingRecoveryLoad.Loaded? = null
        var working: FullSpeakingRecoverySnapshot? = null
        var unavailable = false
    }

    suspend fun restore(sentenceId: String, round: Int): FullSpeakingRecovery = guarded(FullSpeakingRecovery.Unavailable) {
        val scope = loadBound(sentenceId, round) ?: return@guarded FullSpeakingRecovery.Unavailable
        recovery(scope)
    }

    suspend fun prepare(sentenceId: String, round: Int): Boolean = guarded(false) {
        val stamp = synchronized(this) { generation }
        val scope = bound?.takeIf { it.sentenceId == sentenceId && it.round == round }
            ?: loadBound(sentenceId, round) ?: return@guarded false
        if (!fresh(scope)) return@guarded false
        when (scope.working?.phase) {
            FullSpeakingRecoveryPhase.TrainingPending,
            FullSpeakingRecoveryPhase.TranscriptionPending, FullSpeakingRecoveryPhase.TranscriptionDelivered,
            FullSpeakingRecoveryPhase.EvaluationPending -> return@guarded false
            FullSpeakingRecoveryPhase.TrainingDelivered ->
                if (scope.working?.training?.action != ReadingFeedbackAction.REPEAT_FOR_CLARITY) return@guarded false
            else -> Unit
        }
        scope.working = FullSpeakingRecoverySnapshot(UUID.randomUUID().toString(), FullSpeakingRecoveryPhase.Cleared)
        if (!persist(scope)) return@guarded false
        synchronized(this) {
            if (!current(scope, stamp) || !isCaptureAllowed()) false
            else { authorizedCapture = true; true }
        }
    }

    @Synchronized fun start(): Boolean {
        if (!authorizedCapture || currentToken() == null || !isCaptureAllowed() || mutex.isLocked) return false
        authorizedCapture = false
        generation++
        audio?.file?.delete(); audio = null
        if (!capture.start()) return false
        if (currentToken() == null || !isCaptureAllowed()) { capture.cancel(); return false }
        recording = true
        return true
    }

    @Synchronized fun cancel() {
        generation++
        authorizedCapture = false; recording = false
        capture.cancel()
        audio?.file?.delete(); audio = null
        // Pending bodies are encrypted and include original audio; cancellation never rewrites them.
    }

    @Synchronized fun pending(phase: FullSpeakingRecoveryPhase): Boolean {
        val scope = bound ?: return false
        if (currentToken() == null || mutex.isLocked || scope.unavailable) return false
        return scope.working?.phase == phase || (scope.working?.phase == deliveredPhase(phase) &&
            scope.working != scope.stored?.snapshot)
    }

    @Synchronized fun blocked() = currentToken() != null && bound?.working?.phase == FullSpeakingRecoveryPhase.Blocked
    @Synchronized fun recordingId(): String? = bound?.takeIf { !it.unavailable && currentToken() != null }?.working?.recordingId

    suspend fun training(sentenceId: String, mode: SpeakingTrainingMode): ReadingFeedbackResult = guarded(ReadingFeedbackResult.Failed) {
        val scope = bound?.takeIf { it.sentenceId == sentenceId && it.round in 0..3 &&
            mode == if (it.round < 2) SpeakingTrainingMode.BILINGUAL_FOLLOW else SpeakingTrainingMode.ENGLISH_READING }
            ?: return@guarded ReadingFeedbackResult.Failed
        val stamp = synchronized(this) { generation }
        if (!current(scope, stamp) || !fresh(scope)) return@guarded ReadingFeedbackResult.Failed
        if (scope.working?.phase == FullSpeakingRecoveryPhase.TrainingDelivered) {
            return@guarded if (persist(scope) && current(scope, stamp)) ReadingFeedbackResult.Ready(requireNotNull(scope.working?.training))
                else ReadingFeedbackResult.Failed
        }
        if (scope.working?.phase == FullSpeakingRecoveryPhase.Cleared) {
            val original = finish(scope, stamp) ?: return@guarded ReadingFeedbackResult.Failed
            scope.working = requireNotNull(scope.working).copy(phase = FullSpeakingRecoveryPhase.TrainingPending,
                requestBody = codec.trainingRequest(chapterId, sentenceId, targetTextEn, original))
        }
        if (scope.working?.phase != FullSpeakingRecoveryPhase.TrainingPending || !persist(scope)) return@guarded ReadingFeedbackResult.Failed
        val response = execute(scope, stamp, "/api/v1/learning/reading-feedback") ?: return@guarded ReadingFeedbackResult.Failed
        if (!acceptResponse(scope, response)) return@guarded ReadingFeedbackResult.Failed
        val result = codec.trainingResult(response.body)
        if (result !is ReadingFeedbackResult.Ready || !current(scope, stamp)) return@guarded ReadingFeedbackResult.Failed
        scope.working = requireNotNull(scope.working).copy(phase = FullSpeakingRecoveryPhase.TrainingDelivered,
            requestBody = null, training = result.feedback)
        if (!persist(scope) || !current(scope, stamp)) return@guarded ReadingFeedbackResult.Failed
        releaseAudio(); result
    }

    suspend fun transcribe(sentenceId: String): SpeakingTranscriptionResult = guarded(SpeakingTranscriptionResult.Failed) {
        val scope = bound?.takeIf { it.sentenceId == sentenceId && it.round == 4 } ?: return@guarded SpeakingTranscriptionResult.Failed
        val stamp = synchronized(this) { generation }
        if (!current(scope, stamp) || !fresh(scope)) return@guarded SpeakingTranscriptionResult.Failed
        if (scope.working?.phase == FullSpeakingRecoveryPhase.TranscriptionDelivered) {
            return@guarded if (persist(scope) && current(scope, stamp)) SpeakingTranscriptionResult.Ready(requireNotNull(scope.working?.transcription))
                else SpeakingTranscriptionResult.Failed
        }
        if (scope.working?.phase == FullSpeakingRecoveryPhase.Cleared) {
            val original = finish(scope, stamp) ?: return@guarded SpeakingTranscriptionResult.Failed
            scope.working = requireNotNull(scope.working).copy(phase = FullSpeakingRecoveryPhase.TranscriptionPending,
                requestBody = codec.transcriptionRequest(chapterId, sentenceId, original))
        }
        if (scope.working?.phase != FullSpeakingRecoveryPhase.TranscriptionPending || !persist(scope)) return@guarded SpeakingTranscriptionResult.Failed
        val response = execute(scope, stamp, "/api/v1/learning/speaking-transcriptions") ?: return@guarded SpeakingTranscriptionResult.Failed
        if (!acceptResponse(scope, response)) return@guarded SpeakingTranscriptionResult.Failed
        val result = codec.transcriptionResult(response.body)
        if (result !is SpeakingTranscriptionResult.Ready || !current(scope, stamp)) return@guarded SpeakingTranscriptionResult.Failed
        // Keep the original audio-bearing request, not only recognized text. Evaluation still needs it.
        scope.working = requireNotNull(scope.working).copy(phase = FullSpeakingRecoveryPhase.TranscriptionDelivered,
            transcription = result.value)
        if (!persist(scope) || !current(scope, stamp)) return@guarded SpeakingTranscriptionResult.Failed
        releaseAudio(); result
    }

    suspend fun evaluate(sentenceId: String, transcript: String, played: Boolean): SpeakingEvaluationResult = guarded(SpeakingEvaluationResult.Failed) {
        val scope = bound?.takeIf { it.sentenceId == sentenceId && it.round == 4 } ?: return@guarded SpeakingEvaluationResult.Failed
        val stamp = synchronized(this) { generation }
        if (!current(scope, stamp) || !fresh(scope) || transcript.isBlank()) return@guarded SpeakingEvaluationResult.Failed
        var snapshot = scope.working ?: return@guarded SpeakingEvaluationResult.Failed
        if (snapshot.phase == FullSpeakingRecoveryPhase.TranscriptionDelivered) {
            snapshot = snapshot.copy(phase = FullSpeakingRecoveryPhase.EvaluationPending,
                requestBody = codec.evaluationRequestFromTranscription(requireNotNull(snapshot.requestBody), targetTextEn, transcript, played),
                confirmedTranscript = transcript, answerAudioPlayed = played)
            scope.working = snapshot
        }
        if (snapshot.confirmedTranscript != transcript || snapshot.answerAudioPlayed != played) return@guarded SpeakingEvaluationResult.Failed
        if (snapshot.phase == FullSpeakingRecoveryPhase.EvaluationDelivered) {
            return@guarded if (persist(scope) && current(scope, stamp)) SpeakingEvaluationResult.Ready(requireNotNull(snapshot.evaluation))
                else SpeakingEvaluationResult.Failed
        }
        if (snapshot.phase != FullSpeakingRecoveryPhase.EvaluationPending || !persist(scope)) return@guarded SpeakingEvaluationResult.Failed
        val response = execute(scope, stamp, "/api/v1/learning/full-sentence-speaking-evaluations") ?: return@guarded SpeakingEvaluationResult.Failed
        if (!acceptResponse(scope, response)) return@guarded SpeakingEvaluationResult.Failed
        val result = codec.evaluationResult(response.body)
        if (result !is SpeakingEvaluationResult.Ready || !current(scope, stamp)) return@guarded SpeakingEvaluationResult.Failed
        scope.working = snapshot.copy(phase = FullSpeakingRecoveryPhase.EvaluationDelivered, requestBody = null, evaluation = result.candidate)
        if (!persist(scope) || !current(scope, stamp)) return@guarded SpeakingEvaluationResult.Failed
        releaseAudio(); result
    }

    private suspend fun loadBound(sentenceId: String, round: Int): Bound? {
        if (currentToken() == null || sentenceId.isBlank() || round !in 0..4) return null
        val scope = Bound(sentenceId, round, journalFactory(sentenceId, round))
        when (val loaded = scope.journal.load()) {
            FullSpeakingRecoveryLoad.Missing -> Unit
            FullSpeakingRecoveryLoad.Unavailable -> scope.unavailable = true
            is FullSpeakingRecoveryLoad.Loaded -> {
                scope.stored = loaded; scope.working = loaded.snapshot
                val phase = loaded.snapshot.phase
                scope.unavailable = if (round < 4) phase !in setOf(FullSpeakingRecoveryPhase.Cleared,
                    FullSpeakingRecoveryPhase.Blocked, FullSpeakingRecoveryPhase.TrainingPending, FullSpeakingRecoveryPhase.TrainingDelivered)
                else phase in setOf(FullSpeakingRecoveryPhase.TrainingPending, FullSpeakingRecoveryPhase.TrainingDelivered)
            }
        }
        bound = scope
        authorizedCapture = false
        return scope.takeIf { !it.unavailable && currentToken() != null }
    }

    private fun recovery(scope: Bound): FullSpeakingRecovery {
        val snapshot = scope.working ?: return FullSpeakingRecovery.None
        return when (snapshot.phase) {
            FullSpeakingRecoveryPhase.Cleared -> FullSpeakingRecovery.None
            FullSpeakingRecoveryPhase.Blocked -> FullSpeakingRecovery.Blocked
            FullSpeakingRecoveryPhase.TrainingPending -> FullSpeakingRecovery.TrainingPending
            FullSpeakingRecoveryPhase.TrainingDelivered -> FullSpeakingRecovery.TrainingReady(requireNotNull(snapshot.training))
            FullSpeakingRecoveryPhase.TranscriptionPending -> FullSpeakingRecovery.TranscriptionPending
            FullSpeakingRecoveryPhase.TranscriptionDelivered -> FullSpeakingRecovery.TranscriptReady(requireNotNull(snapshot.transcription), snapshot.recordingId)
            FullSpeakingRecoveryPhase.EvaluationPending -> FullSpeakingRecovery.EvaluationPending(requireNotNull(snapshot.confirmedTranscript), snapshot.answerAudioPlayed)
            FullSpeakingRecoveryPhase.EvaluationDelivered -> FullSpeakingRecovery.EvaluationReady(requireNotNull(snapshot.evaluation), requireNotNull(snapshot.confirmedTranscript), snapshot.answerAudioPlayed)
        }
    }

    private suspend fun fresh(scope: Bound): Boolean {
        if (scope.unavailable || currentToken() == null || bound !== scope) return false
        val expected: FullSpeakingRecoveryLoad = scope.stored ?: FullSpeakingRecoveryLoad.Missing
        val actual = scope.journal.load()
        if (currentToken() == null) return false
        if (actual == expected) return true
        if (actual is FullSpeakingRecoveryLoad.Loaded && actual.snapshot == scope.working &&
            actual.revision == (scope.stored?.revision ?: -1L) + 1L) {
            scope.stored = actual
            return true
        }
        // An unavailable read is not permission to overwrite or replay; explicit retry may read again.
        // A different confirmed revision, however, permanently disqualifies this stale instance.
        if (actual != FullSpeakingRecoveryLoad.Unavailable) scope.unavailable = true
        return false
    }

    private suspend fun persist(scope: Bound): Boolean {
        val snapshot = scope.working ?: return false
        if (!fresh(scope)) return false
        if (scope.stored?.snapshot == snapshot) return true
        val previousRevision = scope.stored?.revision
        val saved = scope.journal.save(previousRevision, snapshot)
        val actual = scope.journal.load()
        // A lost local acknowledgement is safe only for this exact next revision and payload.
        if (actual is FullSpeakingRecoveryLoad.Loaded && actual.snapshot == snapshot &&
            actual.revision == (previousRevision ?: -1L) + 1L && (saved == null || saved == actual.revision) && currentToken() != null) {
            scope.stored = actual
            return true
        }
        return false
    }

    @Synchronized private fun current(scope: Bound, stamp: Long) = bound === scope && !scope.unavailable &&
        generation == stamp && currentToken() != null

    @Synchronized private fun finish(scope: Bound, stamp: Long): CapturedReadSpeakAudio? {
        if (!current(scope, stamp)) return null
        audio?.let { return it }
        if (!recording) return null
        recording = false
        val original = capture.finish() ?: return null
        if (!current(scope, stamp)) { original.file.delete(); return null }
        return original.also { audio = it }
    }

    @Synchronized private fun releaseAudio() { audio?.file?.delete(); audio = null }

    private suspend fun execute(scope: Bound, stamp: Long, path: String): HttpResponse? {
        currentCoroutineContext().ensureActive()
        if (!fresh(scope) || !current(scope, stamp)) return null
        val token = currentToken() ?: return null
        val body = scope.working?.requestBody ?: return null
        val response = transport.execute(HttpRequest("POST", path, body, mapOf("Authorization" to "Bearer $token")))
        currentCoroutineContext().ensureActive()
        return response.takeIf { current(scope, stamp) }
    }

    private suspend fun acceptResponse(scope: Bound, response: HttpResponse): Boolean {
        if (response.statusCode == 200) return true
        val terminal = response.statusCode == 410 || (response.statusCode == 409 && try {
            val error = JSONObject(response.body).getJSONObject("error")
            error.opt("code") != "AI_OPERATION_PROCESSING" || error.opt("retryable") != true
        } catch (_: Exception) { true })
        if (terminal) {
            scope.working = FullSpeakingRecoverySnapshot(requireNotNull(scope.working).recordingId, FullSpeakingRecoveryPhase.Blocked)
            if (persist(scope)) releaseAudio()
        }
        return false
    }

    private suspend fun <T> guarded(failed: T, operation: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        if (!mutex.tryLock()) return failed
        return try { operation() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed }
        finally { mutex.unlock() }
    }

    private fun deliveredPhase(phase: FullSpeakingRecoveryPhase) = when (phase) {
        FullSpeakingRecoveryPhase.TrainingPending -> FullSpeakingRecoveryPhase.TrainingDelivered
        FullSpeakingRecoveryPhase.TranscriptionPending -> FullSpeakingRecoveryPhase.TranscriptionDelivered
        FullSpeakingRecoveryPhase.EvaluationPending -> FullSpeakingRecoveryPhase.EvaluationDelivered
        else -> phase
    }
}
