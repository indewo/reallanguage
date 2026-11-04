package com.reallanguage.app.feature.learning.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedSceneResponseTask
import com.reallanguage.app.feature.learning.domain.SceneDialogueLine
import com.reallanguage.app.feature.learning.domain.SceneEvaluationCandidate
import com.reallanguage.app.feature.learning.domain.SceneEvaluationDecision
import com.reallanguage.app.feature.learning.domain.SceneEvaluationResult
import com.reallanguage.app.feature.learning.domain.SceneResponseRepository
import com.reallanguage.app.feature.learning.domain.SceneResponseRecovery
import com.reallanguage.app.feature.learning.domain.SceneResponseSaveResult
import com.reallanguage.app.feature.learning.domain.SceneTaskGenerationResult
import com.reallanguage.app.feature.learning.domain.SceneTrainingFeedbackResult
import com.reallanguage.app.feature.learning.domain.SpeakingTranscriptionResult
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidSceneResponseRepository(
    context: Context,
    private val transport: HttpTransport,
    private val runtime: SessionRuntime,
    private val capture: ReadSpeakAudioCapture,
    private val codec: SceneResponseWireCodec = JsonSceneResponseWireCodec(),
    private val isCaptureAllowed: () -> Boolean = { true },
    private val recoveryJournalFactory: ((FixedLearningChapter, FixedSceneResponseTask, Int) -> SceneResponseRecoveryJournal)? = null,
) : SceneResponseRepository {
    private val applicationContext = context.applicationContext
    private val ownerAccountId = runtime.accountId
    private val ownerAccountEpoch = runtime.accountEpoch
    private val savedTasks = AndroidSavedSceneTaskReader(applicationContext, runtime)
    private fun ownerIsActive() = ownerAccountId != null && runtime.accountId == ownerAccountId &&
        runtime.accountEpoch == ownerAccountEpoch && runtime.accessToken != null
    private var generation = 0L
    private var capturing = false
    private var currentAttempt: ResponseAttempt? = null
    private var tasks = emptyMap<String, FixedSceneResponseTask>()
    private var activeChapterId: String? = null
    private var recoveryBinding: RecoveryBinding? = null
    private var recoveryUnavailable = false
    private var capturePrepared = false
    override val requiresAttemptRecovery get() = recoveryJournalFactory != null

    private class RecoveryBinding(
        val chapter: FixedLearningChapter,
        val task: FixedSceneResponseTask,
        val round: Int,
        val journal: SceneResponseRecoveryJournal,
        var revision: Long? = null,
        var snapshot: SceneResponseRecoverySnapshot? = null,
    )

    private data class ResponseTarget(val chapterId: String, val task: FixedSceneResponseTask)

    private class ResponseAttempt(val generation: Long, val recordingId: String = UUID.randomUUID().toString()) {
        // Cancellation detaches this lock as well: a fresh recording never queues behind old HTTP.
        val mutex = Mutex()
        var target: ResponseTarget? = null
        var audio: CapturedReadSpeakAudio? = null
        var transcriptionBody: String? = null
        var transcription: SpeakingTranscriptionResult.Ready? = null
        var transcriptionResultBody: String? = null
        var transcriptionBlocked = false
        var feedback: FeedbackAttempt? = null
    }

    private enum class FeedbackKind { TRAINING, EVALUATION }

    private class FeedbackAttempt(
        val kind: FeedbackKind,
        val roundIndex: Int,
        val confirmedTranscript: String,
    ) {
        var body: String? = null
        var delivered: FeedbackDelivery? = null
        var blocked = false
    }

    private sealed interface FeedbackDelivery {
        data class Training(val result: SceneTrainingFeedbackResult) : FeedbackDelivery
        data class Evaluation(val result: SceneEvaluationResult.Ready) : FeedbackDelivery
    }

    @Synchronized
    override fun currentRecordingId(): String? = currentAttempt?.takeIf(::isCurrent)?.recordingId

    override suspend fun restoreAttempt(
        chapter: FixedLearningChapter,
        task: FixedSceneResponseTask,
        roundIndex: Int,
    ): SceneResponseRecovery {
        val factory = recoveryJournalFactory ?: return SceneResponseRecovery.None
        val restoreGeneration = synchronized(this) {
            cancelCapture()
            recoveryUnavailable = true
            recoveryBinding = null
            generation
        }
        if (!ownerIsActive() || activeChapterId != chapter.chapterId || tasks[task.taskId] != task || roundIndex !in 0..2) {
            return SceneResponseRecovery.Unavailable
        }
        var attempt: ResponseAttempt? = null
        return try {
            val binding = RecoveryBinding(chapter, task, roundIndex, factory(chapter, task, roundIndex))
            val loaded = binding.journal.load()
            check(ownerIsActive() && generation == restoreGeneration && activeChapterId == chapter.chapterId && tasks[task.taskId] == task)
            val result = when (loaded) {
                SceneResponseRecoveryLoad.Unavailable -> error("Recovery unavailable")
                SceneResponseRecoveryLoad.Missing -> SceneResponseRecovery.None
                is SceneResponseRecoveryLoad.Loaded -> {
                    val snapshot = loaded.snapshot
                    check(snapshot.taskId == task.taskId && snapshot.roundIndex == roundIndex)
                    UUID.fromString(snapshot.recordingId)
                    binding.revision = loaded.revision
                    binding.snapshot = snapshot
                    val restored = ResponseAttempt(restoreGeneration, snapshot.recordingId).also {
                        it.target = ResponseTarget(chapter.chapterId, task)
                        attempt = it
                    }
                    when (snapshot.phase) {
                        SceneResponseRecoveryPhase.Cleared -> SceneResponseRecovery.None
                        SceneResponseRecoveryPhase.Blocked -> {
                            restored.transcriptionBlocked = true
                            SceneResponseRecovery.Blocked
                        }
                        SceneResponseRecoveryPhase.TranscriptionPending,
                        SceneResponseRecoveryPhase.TranscriptionDelivered -> {
                            val body = requireNotNull(snapshot.transcriptionBody)
                            validateAudio(body, binding, feedback = false)
                            restored.transcriptionBody = body
                            if (snapshot.phase == SceneResponseRecoveryPhase.TranscriptionPending) {
                                SceneResponseRecovery.TranscriptionPending
                            } else {
                                val resultBody = requireNotNull(snapshot.transcriptionResultBody)
                                val ready = codec.transcriptionResult(resultBody) as SpeakingTranscriptionResult.Ready
                                restored.transcription = ready
                                restored.transcriptionResultBody = resultBody
                                SceneResponseRecovery.TranscriptReady(ready.value, restored.recordingId)
                            }
                        }
                        SceneResponseRecoveryPhase.FeedbackPending -> {
                            val body = requireNotNull(snapshot.feedbackBody)
                            val confirmed = requireNotNull(snapshot.confirmedTranscript).also { check(it.isNotBlank()) }
                            validateAudio(body, binding, feedback = true, confirmed = confirmed)
                            val resultBody = requireNotNull(snapshot.transcriptionResultBody)
                            restored.transcription = codec.transcriptionResult(resultBody) as SpeakingTranscriptionResult.Ready
                            restored.transcriptionResultBody = resultBody
                            restored.feedback = FeedbackAttempt(kindFor(roundIndex), roundIndex, confirmed).also { it.body = body }
                            SceneResponseRecovery.FeedbackPending(confirmed)
                        }
                        SceneResponseRecoveryPhase.FeedbackDelivered -> {
                            val confirmed = requireNotNull(snapshot.confirmedTranscript).also { check(it.isNotBlank()) }
                            val delivery = requireNotNull(decodeFeedback(kindFor(roundIndex), requireNotNull(snapshot.feedbackResultBody)))
                            restored.feedback = FeedbackAttempt(kindFor(roundIndex), roundIndex, confirmed).also { it.delivered = delivery }
                            when (delivery) {
                                is FeedbackDelivery.Training -> SceneResponseRecovery.TrainingReady(delivery.result, confirmed)
                                is FeedbackDelivery.Evaluation -> SceneResponseRecovery.EvaluationReady(delivery.result.candidate, confirmed)
                            }
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            synchronized(this) {
                check(ownerIsActive() && generation == restoreGeneration && activeChapterId == chapter.chapterId && tasks[task.taskId] == task)
                recoveryBinding = binding
                currentAttempt = attempt
                recoveryUnavailable = false
                result
            }
        } catch (cancelled: CancellationException) {
            attempt?.let(::discardAudio)
            throw cancelled
        } catch (_: Exception) {
            attempt?.let(::discardAudio)
            SceneResponseRecovery.Unavailable
        }
    }

    override suspend fun prepareCapture(chapter: FixedLearningChapter, task: FixedSceneResponseTask, roundIndex: Int): Boolean {
        if (!requiresAttemptRecovery) return true
        capturePrepared = false
        val preparedGeneration = generation
        val binding = recoveryBinding ?: return false
        if (recoveryUnavailable || !ownerIsActive() || binding.chapter != chapter || binding.task != task || binding.round != roundIndex ||
            capturing || currentAttempt?.mutex?.isLocked == true || binding.snapshot?.phase in listOf(
                SceneResponseRecoveryPhase.TranscriptionPending, SceneResponseRecoveryPhase.FeedbackPending,
            )) return false
        return try {
            val snapshot = SceneResponseRecoverySnapshot(UUID.randomUUID().toString(), task.taskId, roundIndex, SceneResponseRecoveryPhase.Cleared)
            val revision = binding.journal.save(binding.revision, snapshot) ?: return false
            synchronized(this) {
                if (!ownerIsActive() || generation != preparedGeneration || recoveryBinding !== binding) return@synchronized false
                binding.revision = revision
                binding.snapshot = snapshot
                capturePrepared = true
                true
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    private fun kindFor(round: Int) = if (round == 2) FeedbackKind.EVALUATION else FeedbackKind.TRAINING

    private fun validateAudio(body: String, binding: RecoveryBinding, feedback: Boolean, confirmed: String? = null) {
        val json = JSONObject(body)
        check(json.getString("chapterId") == binding.chapter.chapterId && json.getString("sentenceId") == binding.task.sentenceId)
        UUID.fromString(json.getString("commandId"))
        val mime = json.getString("audioMimeType")
        check(mime in setOf("audio/mp4", "audio/m4a", "audio/wav", "audio/x-wav", "audio/mpeg", "audio/webm", "audio/aac"))
        if (feedback) {
            check(json.getString("taskId") == binding.task.taskId && json.getInt("roundIndex") == binding.round)
            check(json.getString("targetTextEn") == binding.task.targetSentenceEn && json.getString("confirmedTranscript") == confirmed)
            check(json.getBoolean("userTranscriptConfirmed"))
            if (binding.round == 2) check(json.getBoolean("targetHidden"))
            val lines = json.getJSONArray("contextLines")
            check(lines.length() == binding.task.contextLines.size)
            binding.task.contextLines.forEachIndexed { index, line ->
                check(lines.getJSONObject(index).getString("speaker") == line.speaker && lines.getJSONObject(index).getString("textEn") == line.textEn)
            }
        }
        val encoded = json.getString("audioBase64")
        check(encoded.length in 1..12_000_000)
        val bytes = Base64.getDecoder().decode(encoded)
        try { check(bytes.isNotEmpty()) } finally { bytes.fill(0) }
        // Keep the authenticated original JSON in memory, not an unowned decoded
        // audio file that could remain in shared cache after a process kill.
    }

    private suspend fun persistAttempt(attempt: ResponseAttempt, snapshot: SceneResponseRecoverySnapshot): Boolean {
        if (!requiresAttemptRecovery) return true
        val binding = recoveryBinding ?: return false
        if (recoveryUnavailable || !isCurrent(attempt) || snapshot.taskId != binding.task.taskId || snapshot.roundIndex != binding.round) return false
        if (binding.snapshot == snapshot) {
            // A retained body is not ongoing authorization: the chapter/checkpoint
            // may have changed since it was first acknowledged on disk.
            val current = binding.journal.load()
            currentCoroutineContext().ensureActive()
            return isCurrent(attempt) && recoveryBinding === binding &&
                current is SceneResponseRecoveryLoad.Loaded && current.revision == binding.revision && current.snapshot == snapshot
        }
        val revision = binding.journal.save(binding.revision, snapshot) ?: return false
        currentCoroutineContext().ensureActive()
        if (!isCurrent(attempt) || recoveryBinding !== binding) return false
        binding.revision = revision
        binding.snapshot = snapshot
        return true
    }

    private fun recoverySnapshot(attempt: ResponseAttempt, phase: SceneResponseRecoveryPhase) = SceneResponseRecoverySnapshot(
        attempt.recordingId, requireNotNull(attempt.target).task.taskId, requireNotNull(recoveryBinding).round, phase,
    )

    override suspend fun restoreTasks(chapter: FixedLearningChapter): SceneTaskGenerationResult {
        val account = ownerAccountId ?: return SceneTaskGenerationResult.Failed
        if (!ownerIsActive()) return SceneTaskGenerationResult.Failed
        return try {
            val fixed = withContext(Dispatchers.IO) { readTasks(account, chapter) } ?: return SceneTaskGenerationResult.Failed
            if (!ownerIsActive()) return SceneTaskGenerationResult.Failed
            activeChapterId = chapter.chapterId
            tasks = fixed.associateBy { it.taskId }
            SceneTaskGenerationResult.Ready(fixed)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { SceneTaskGenerationResult.Failed }
    }

    override suspend fun generateTasks(chapter: FixedLearningChapter): SceneTaskGenerationResult {
        if (!ownerIsActive()) return SceneTaskGenerationResult.Failed
        val scope = applicationContext.filesDir.absolutePath + ":" + ownerAccountId + ":" + chapter.chapterId
        return try { generationLocks[(scope.hashCode() and Int.MAX_VALUE) % generationLocks.size].withLock { generateForOwner(chapter) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { SceneTaskGenerationResult.Failed }
    }

    private suspend fun generateForOwner(chapter: FixedLearningChapter): SceneTaskGenerationResult {
        val accountId = ownerAccountId ?: return SceneTaskGenerationResult.Failed
        activeChapterId = chapter.chapterId
        val accepted = withContext(Dispatchers.IO) { savedTasks.readAvailable(chapter) }.associateBy { it.sentenceId }
        val generated = mutableListOf<FixedSceneResponseTask>()
        for ((index, sentence) in chapter.sentences.filter { it.isFocus }.withIndex()) {
            if (!ownerIsActive()) return SceneTaskGenerationResult.Failed
            val retained = accepted[sentence.sentenceId]
            if (retained != null) {
                generated += retained
                continue
            }
            val commandId = UUID.nameUUIDFromBytes(
                "scene-task:$accountId:${chapter.chapterId}:${sentence.sentenceId}".toByteArray(),
            ).toString()
            val response = execute(
                "/api/v1/learning/scene-response-tasks",
                codec.generationRequest(chapter, sentence.sentenceId, commandId),
            ) ?: return SceneTaskGenerationResult.Failed
            val task = runCatching { codec.generationResult(response) }.getOrNull()
                ?: return SceneTaskGenerationResult.Failed
            if (task.sentenceId != sentence.sentenceId || task.targetSentenceEn != sentence.textEn) {
                return SceneTaskGenerationResult.Failed
            }
            if (!withContext(Dispatchers.IO) { storeTasks(accountId, chapter.chapterId, listOf(task), index) } || !ownerIsActive()) {
                return SceneTaskGenerationResult.Failed
            }
            generated += task
        }
        if (withContext(Dispatchers.IO) { readTasks(accountId, chapter) } != generated || !ownerIsActive()) {
            return SceneTaskGenerationResult.Failed
        }
        tasks = generated.associateBy { it.taskId }
        return SceneTaskGenerationResult.Ready(generated)
    }

    @Synchronized
    override fun startCapture(): Boolean {
        if (!ownerIsActive()) { cancelCapture(); return false }
        if (!isCaptureAllowed() || capturing || currentAttempt?.mutex?.isLocked == true) return false
        if (requiresAttemptRecovery && (!capturePrepared || recoveryUnavailable || recoveryBinding == null)) return false
        capturePrepared = false
        currentAttempt?.let(::discardAudio)
        val attempt = ResponseAttempt(++generation, recoveryBinding?.snapshot?.recordingId ?: UUID.randomUUID().toString())
        recoveryBinding?.let { attempt.target = ResponseTarget(it.chapter.chapterId, it.task) }
        currentAttempt = attempt
        capturing = true
        val started = try { capture.start() } catch (failure: Exception) {
            cancelCapture()
            throw failure
        }
        if (!started || !isCurrent(attempt) || !isCaptureAllowed()) {
            if (currentAttempt === attempt) cancelCapture()
            return false
        }
        return true
    }

    @Synchronized
    override fun cancelCapture() {
        generation++
        capturePrepared = false
        val detached = currentAttempt
        currentAttempt = null
        capturing = false
        try { capture.cancel() } finally { detached?.let(::discardAudio) }
    }

    @Synchronized
    override fun hasPendingTranscriptionRetry(): Boolean = currentAttempt?.let {
        isCurrent(it) && !it.mutex.isLocked && it.transcription == null && !it.transcriptionBlocked &&
            it.transcriptionBody != null
    } == true

    @Synchronized
    override fun hasPendingEvaluationRetry(): Boolean = currentAttempt?.let { attempt ->
        isCurrent(attempt) && !attempt.mutex.isLocked &&
            attempt.feedback?.let { it.delivered == null && !it.blocked && it.body != null } == true
    } == true

    @Synchronized
    override fun technicalRetryBlocked(): Boolean = currentAttempt?.let {
        isCurrent(it) && (it.transcriptionBlocked || it.feedback?.blocked == true)
    } == true

    override suspend fun transcribeResponse(taskId: String): SpeakingTranscriptionResult {
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        val attempt = activeAttempt() ?: return SpeakingTranscriptionResult.Failed
        if (!attempt.mutex.tryLock()) return SpeakingTranscriptionResult.Failed
        try {
            val body = synchronized(this) {
                coroutine.ensureActive()
                val target = bindTarget(attempt, taskId) ?: return@synchronized null
                attempt.transcription?.let { return it }
                if (attempt.transcriptionBlocked) return@synchronized null
                attempt.transcriptionBody?.let { return@synchronized it }
                if (attempt.audio == null) {
                    if (!capturing) return@synchronized null
                    // Finishing is one-shot, even if the recorder cannot provide an audio file.
                    capturing = false
                    val audio = capture.finish() ?: return@synchronized null
                    if (!isCurrent(attempt)) {
                        audio.file.delete()
                        return@synchronized null
                    }
                    attempt.audio = audio
                }
                val audio = attempt.audio?.takeIf { it.file.exists() } ?: return@synchronized null
                attempt.transcriptionBody ?: codec.transcriptionRequest(target.chapterId, target.task, audio)
                    .also { attempt.transcriptionBody = it }
            } ?: return SpeakingTranscriptionResult.Failed
            if (requiresAttemptRecovery && !persistAttempt(attempt, recoverySnapshot(attempt, SceneResponseRecoveryPhase.TranscriptionPending)
                .copy(transcriptionBody = body))) return SpeakingTranscriptionResult.Failed
            val response = executeAttempt("/api/v1/learning/speaking-transcriptions", body, attempt)
                ?: return SpeakingTranscriptionResult.Failed
            if (response.statusCode != 200) {
                val blocked = blocksTechnicalRetry(response)
                if (blocked && requiresAttemptRecovery && !persistAttempt(attempt, recoverySnapshot(attempt, SceneResponseRecoveryPhase.Blocked))) {
                    return SpeakingTranscriptionResult.Failed
                }
                synchronized(this) {
                    coroutine.ensureActive()
                    if (isCurrent(attempt)) {
                        attempt.transcriptionBlocked = blocked
                        if (blocked) discardAudio(attempt)
                    }
                }
                return SpeakingTranscriptionResult.Failed
            }
            val result = codec.transcriptionResult(response.body)
            if (result !is SpeakingTranscriptionResult.Ready) return SpeakingTranscriptionResult.Failed
            if (requiresAttemptRecovery && !persistAttempt(attempt, recoverySnapshot(attempt, SceneResponseRecoveryPhase.TranscriptionDelivered)
                .copy(transcriptionBody = body, transcriptionResultBody = response.body))) return SpeakingTranscriptionResult.Failed
            return synchronized(this) {
                coroutine.ensureActive()
                if (!isCurrent(attempt)) SpeakingTranscriptionResult.Failed
                else result.also { attempt.transcription = it; attempt.transcriptionResultBody = response.body }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            coroutine.ensureActive()
            return SpeakingTranscriptionResult.Failed
        }
        finally {
            try { discardStaleAttempt(attempt) } finally { attempt.mutex.unlock() }
        }
    }

    override suspend fun trainingFeedback(
        taskId: String,
        roundIndex: Int,
        confirmedTranscript: String,
    ): SceneTrainingFeedbackResult = (feedback(taskId, roundIndex, confirmedTranscript, FeedbackKind.TRAINING)
        as? FeedbackDelivery.Training)?.result ?: SceneTrainingFeedbackResult.Failed

    override suspend fun evaluateFinalRound(
        taskId: String,
        confirmedTranscript: String,
    ): SceneEvaluationResult = (feedback(taskId, 2, confirmedTranscript, FeedbackKind.EVALUATION)
        as? FeedbackDelivery.Evaluation)?.result ?: SceneEvaluationResult.Failed

    private suspend fun feedback(
        taskId: String,
        roundIndex: Int,
        confirmedTranscript: String,
        kind: FeedbackKind,
    ): FeedbackDelivery? {
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        val attempt = activeAttempt() ?: return null
        if (!attempt.mutex.tryLock()) return null
        try {
            if (confirmedTranscript.isBlank() || roundIndex !in 0..2 || kind != kindFor(roundIndex)) return null
            if (requiresAttemptRecovery && recoveryBinding?.round != roundIndex) return null
            attempt.feedback?.takeIf {
                it.kind == kind && it.roundIndex == roundIndex && it.confirmedTranscript == confirmedTranscript
            }?.delivered?.let { return it.takeIf { isCurrent(attempt) } }
            val target = synchronized(this) {
                coroutine.ensureActive()
                if (attempt.transcription == null) return@synchronized null
                bindTarget(attempt, taskId)
            } ?: return null
            val feedback = synchronized(this) {
                if (!isCurrent(attempt)) return@synchronized null
                attempt.feedback?.let {
                    return@synchronized it.takeIf { saved ->
                        saved.kind == kind && saved.roundIndex == roundIndex &&
                            saved.confirmedTranscript == confirmedTranscript
                    }
                }
                FeedbackAttempt(kind, roundIndex, confirmedTranscript).also { attempt.feedback = it }
            } ?: return null
            val body = synchronized(this) {
                coroutine.ensureActive()
                if (!isCurrent(attempt)) return@synchronized null
                feedback.delivered?.let { return it }
                if (feedback.blocked) return@synchronized null
                feedback.body?.let { return@synchronized it }
                val audio = attempt.audio?.takeIf { it.file.exists() }
                val nextBody = if (audio == null) {
                    val original = attempt.transcriptionBody ?: return@synchronized null
                    codec.feedbackRequestFromTranscription(target.chapterId, target.task, feedback.roundIndex, feedback.confirmedTranscript, original)
                } else when (kind) {
                    FeedbackKind.TRAINING -> codec.feedbackRequest(
                        target.chapterId, target.task, feedback.roundIndex, feedback.confirmedTranscript, audio,
                    )
                    FeedbackKind.EVALUATION -> codec.evaluationRequest(
                        target.chapterId, target.task, feedback.confirmedTranscript, audio,
                    )
                }
                nextBody.also { feedback.body = it }
            } ?: return null
            val path = when (kind) {
                FeedbackKind.TRAINING -> "/api/v1/learning/scene-response-training-feedback"
                FeedbackKind.EVALUATION -> "/api/v1/learning/scene-response-evaluations"
            }
            if (requiresAttemptRecovery && !persistAttempt(attempt, recoverySnapshot(attempt, SceneResponseRecoveryPhase.FeedbackPending).copy(
                feedbackBody = body, transcriptionResultBody = requireNotNull(attempt.transcriptionResultBody), confirmedTranscript = confirmedTranscript,
            ))) return null
            val response = executeAttempt(path, body, attempt) ?: return null
            if (response.statusCode != 200) {
                val blocked = blocksTechnicalRetry(response)
                if (blocked && requiresAttemptRecovery && !persistAttempt(attempt, recoverySnapshot(attempt, SceneResponseRecoveryPhase.Blocked))) return null
                synchronized(this) {
                    coroutine.ensureActive()
                    if (isCurrent(attempt)) {
                        feedback.blocked = blocked
                        if (blocked) discardAudio(attempt)
                    }
                }
                return null
            }
            val delivered = decodeFeedback(kind, response.body) ?: return null
            if (requiresAttemptRecovery && !persistAttempt(attempt, recoverySnapshot(attempt, SceneResponseRecoveryPhase.FeedbackDelivered).copy(
                feedbackResultBody = response.body, confirmedTranscript = confirmedTranscript,
            ))) return null
            return synchronized(this) {
                coroutine.ensureActive()
                if (!isCurrent(attempt)) null
                else delivered.also {
                    feedback.delivered = it
                    // Delivered feedback/candidates survive local-save retry without another paid request.
                    discardAudio(attempt)
                    attempt.transcriptionBody = null
                    attempt.transcriptionResultBody = null
                    feedback.body = null
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            coroutine.ensureActive()
            return null
        }
        finally {
            try { discardStaleAttempt(attempt) } finally { attempt.mutex.unlock() }
        }
    }

    private fun decodeFeedback(kind: FeedbackKind, body: String): FeedbackDelivery? = when (kind) {
        FeedbackKind.TRAINING -> when (val result = codec.feedbackResult(body)) {
            SceneTrainingFeedbackResult.Failed -> null
            else -> FeedbackDelivery.Training(result)
        }
        FeedbackKind.EVALUATION -> (codec.evaluationResult(body) as? SceneEvaluationResult.Ready)?.let { FeedbackDelivery.Evaluation(it) }
    }

    override suspend fun saveFinalDecision(
        chapterId: String,
        sentenceId: String,
        decision: SceneEvaluationDecision,
    ): SceneResponseSaveResult = withContext(Dispatchers.IO) {
        val accountId = ownerAccountId ?: return@withContext SceneResponseSaveResult.Failed
        if (!ownerIsActive() || chapterId != activeChapterId || decision == SceneEvaluationDecision.INCONCLUSIVE) return@withContext SceneResponseSaveResult.Failed
        val task = tasks.values.firstOrNull { it.sentenceId == sentenceId } ?: return@withContext SceneResponseSaveResult.Failed
        runCatching {
            FormationDatabase(applicationContext).use { helper ->
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
            check(db.rawQuery(
                "select 1 from chapters c join fixed_sentences s on s.account_id=c.account_id and s.chapter_id=c.chapter_id where c.account_id=? and c.chapter_id=? and c.status!='COMPLETED' and s.unit_id=? and s.role='FOCUS' and s.text_en=?",
                arrayOf(accountId, chapterId, sentenceId, task.targetSentenceEn),
            ).use { it.moveToFirst() })
            check(db.insertWithOnConflict(
                "scene_response_result_drafts",
                null,
                ContentValues().apply {
                    put("account_id", accountId)
                    put("chapter_id", chapterId)
                    put("sentence_id", sentenceId)
                    put("passed", if (decision == SceneEvaluationDecision.PASS) 1 else 0)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            ) != -1L)
            check(ownerIsActive())
            db.setTransactionSuccessful()
            true
            } finally { db.endTransaction() }
            }
        }.fold(
            onSuccess = { if (it) SceneResponseSaveResult.Saved else SceneResponseSaveResult.Failed },
            onFailure = { SceneResponseSaveResult.Failed },
        )
    }

    private suspend fun execute(path: String, body: String): String? {
        if (!ownerIsActive()) return null
        val token = runtime.accessToken ?: return null
        return try {
        val response = transport.execute(
            HttpRequest("POST", path, body, mapOf("Authorization" to "Bearer $token")),
        )
        response.body.takeIf { response.statusCode == 200 && ownerIsActive() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    @Synchronized
    private fun activeAttempt(): ResponseAttempt? {
        if (!ownerIsActive()) { cancelCapture(); return null }
        return currentAttempt?.takeIf(::isCurrent)
    }

    // Caller holds this repository's monitor; task/round/evidence cannot mutate on technical retry.
    private fun bindTarget(attempt: ResponseAttempt, taskId: String): ResponseTarget? {
        if (!isCurrent(attempt)) return null
        val task = tasks[taskId] ?: return null
        val chapterId = activeChapterId ?: return null
        val target = ResponseTarget(chapterId, task.copy(contextLines = task.contextLines.toList()))
        attempt.target?.let { return it.takeIf { saved -> saved == target } }
        return target.also { attempt.target = it }
    }

    private fun isCurrent(attempt: ResponseAttempt): Boolean = ownerIsActive() &&
        currentAttempt === attempt && generation == attempt.generation && (attempt.target?.let {
            it.chapterId == activeChapterId && tasks[it.task.taskId] == it.task
        } != false)

    private suspend fun executeAttempt(path: String, body: String, attempt: ResponseAttempt): HttpResponse? {
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        val token = synchronized(this) {
            if (isCurrent(attempt)) runtime.accessToken else null
        } ?: return null
        val response = transport.execute(HttpRequest("POST", path, body, mapOf("Authorization" to "Bearer $token")))
        coroutine.ensureActive()
        return synchronized(this) { response.takeIf { isCurrent(attempt) } }
    }

    private fun blocksTechnicalRetry(response: HttpResponse): Boolean {
        if (response.statusCode == 410) return true
        if (response.statusCode != 409) return false
        val error = runCatching { JSONObject(response.body).getJSONObject("error") }.getOrNull()
        return error?.opt("code") != "AI_OPERATION_PROCESSING" || error?.opt("retryable") != true
    }

    @Synchronized
    private fun discardStaleAttempt(attempt: ResponseAttempt) {
        if (!isCurrent(attempt)) discardAudio(attempt)
    }

    private fun discardAudio(attempt: ResponseAttempt) {
        try { attempt.audio?.file?.delete() } finally { attempt.audio = null }
    }

    private fun readTasks(
        accountId: String,
        chapter: FixedLearningChapter,
    ): List<FixedSceneResponseTask>? {
        check(ownerIsActive())
        check(accountId == ownerAccountId)
        return savedTasks.read(chapter).also { check(ownerIsActive()) }
    }

    private fun storeTasks(
        accountId: String,
        chapterId: String,
        values: List<FixedSceneResponseTask>,
        firstOrder: Int = 0,
    ): Boolean = runCatching {
        FormationDatabase(applicationContext).use { helper ->
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            check(ownerIsActive())
            values.forEachIndexed { index, task ->
                check(db.rawQuery("select 1 from fixed_sentences where account_id=? and chapter_id=? and unit_id=? and role='FOCUS' and text_en=?", arrayOf(accountId, chapterId, task.sentenceId, task.targetSentenceEn)).use { it.moveToFirst() })
                db.insertOrThrow(
                    "scene_response_tasks",
                    null,
                    ContentValues().apply {
                        put("account_id", accountId)
                        put("chapter_id", chapterId)
                        put("task_id", task.taskId)
                        put("sentence_id", task.sentenceId)
                        put("sentence_order", firstOrder + index)
                        put("background_en", task.backgroundEn.orEmpty())
                        put("context_lines", encodeLines(task.contextLines))
                        put("target_sentence_en", task.targetSentenceEn)
                    },
                )
            }
            check(ownerIsActive())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        true
        }
    }.getOrDefault(false)

    private fun encodeLines(lines: List<SceneDialogueLine>) = lines.joinToString("\u001e") {
        "${it.speaker}\u001f${it.textEn}"
    }

    private companion object { val generationLocks = Array(64) { Mutex() } }

}

interface SceneResponseWireCodec {
    fun generationRequest(chapter: FixedLearningChapter, focusSentenceId: String, commandId: String): String
    fun generationResult(body: String): FixedSceneResponseTask
    fun transcriptionRequest(chapterId: String, task: FixedSceneResponseTask, audio: CapturedReadSpeakAudio): String
    fun transcriptionResult(body: String): SpeakingTranscriptionResult
    fun feedbackRequest(chapterId: String, task: FixedSceneResponseTask, roundIndex: Int, transcript: String, audio: CapturedReadSpeakAudio): String
    fun feedbackResult(body: String): SceneTrainingFeedbackResult
    fun evaluationRequest(chapterId: String, task: FixedSceneResponseTask, transcript: String, audio: CapturedReadSpeakAudio): String
    fun evaluationResult(body: String): SceneEvaluationResult
    /** The original authenticated request already contains the exact captured audio. */
    fun feedbackRequestFromTranscription(chapterId: String, task: FixedSceneResponseTask, roundIndex: Int, transcript: String, transcriptionBody: String): String =
        error("This codec does not support durable audio evidence")
}

class JsonSceneResponseWireCodec : SceneResponseWireCodec {
    override fun feedbackRequestFromTranscription(chapterId: String, task: FixedSceneResponseTask, roundIndex: Int, transcript: String, transcriptionBody: String): String {
        val original = JSONObject(transcriptionBody)
        require(original.getString("chapterId") == chapterId && original.getString("sentenceId") == task.sentenceId)
        require(roundIndex in 0..2)
        return evidenceFields(original.put("commandId", UUID.randomUUID().toString()), task, transcript)
            .put("roundIndex", roundIndex).apply { if (roundIndex == 2) put("targetHidden", true) }.toString()
    }
    override fun generationRequest(chapter: FixedLearningChapter, focusSentenceId: String, commandId: String) = JSONObject()
        .put("chapterId", chapter.chapterId)
        .put("focusSentenceId", focusSentenceId)
        .put("chapterSentences", JSONArray(chapter.sentences.map { sentence ->
            JSONObject().put("sentenceId", sentence.sentenceId).put("textEn", sentence.textEn)
                .put("textZh", sentence.textZh).put("isFocus", sentence.isFocus)
        }))
        .put("commandId", commandId).toString()

    override fun generationResult(body: String) = JSONObject(body).let { json ->
        val lines = json.getJSONArray("contextLines")
        FixedSceneResponseTask(
            json.getString("taskId"),
            json.getString("sentenceId"),
            if (json.isNull("backgroundEn")) null else json.getString("backgroundEn"),
            List(lines.length()) { index -> lines.getJSONObject(index).let {
                SceneDialogueLine(it.getString("speaker"), it.getString("textEn"))
            } },
            json.getString("targetSentenceEn"),
        )
    }

    override fun transcriptionRequest(chapterId: String, task: FixedSceneResponseTask, audio: CapturedReadSpeakAudio) =
        audioBody(chapterId, task, audio).toString()

    override fun transcriptionResult(body: String) =
        SpeakingTranscriptionResult.Ready(JSONObject(body).speakingTranscriptionWire())

    override fun feedbackRequest(chapterId: String, task: FixedSceneResponseTask, roundIndex: Int, transcript: String, audio: CapturedReadSpeakAudio) =
        evidenceBody(chapterId, task, transcript, audio).put("roundIndex", roundIndex).toString()

    override fun feedbackResult(body: String) = JSONObject(body).let { json ->
        when (json.speechString("status")) {
            "INCONCLUSIVE" -> SceneTrainingFeedbackResult.Inconclusive
            "READY" -> {
                json.speechConfidence("pronunciationConfidence")
                SceneTrainingFeedbackResult.Ready(json.speechString("feedbackZh"))
            }
            else -> error("Invalid scene feedback status")
        }
    }

    override fun evaluationRequest(chapterId: String, task: FixedSceneResponseTask, transcript: String, audio: CapturedReadSpeakAudio) =
        evidenceBody(chapterId, task, transcript, audio).put("roundIndex", 2).put("targetHidden", true).toString()

    override fun evaluationResult(body: String) = JSONObject(body).let { json ->
        json.speechConfidence("pronunciationConfidence")
        SceneEvaluationResult.Ready(
            SceneEvaluationCandidate(
                SceneEvaluationDecision.valueOf(json.speechString("decision")),
                json.speechBoolean("correctTurn"),
                json.speechBoolean("targetSentenceComplete"),
                json.speechBoolean("pronunciationIntelligible"),
                json.speechString("feedbackZh"),
            ),
        )
    }

    private fun evidenceBody(chapterId: String, task: FixedSceneResponseTask, transcript: String, audio: CapturedReadSpeakAudio) =
        evidenceFields(audioBody(chapterId, task, audio), task, transcript)

    private fun evidenceFields(body: JSONObject, task: FixedSceneResponseTask, transcript: String) = body
            .put("taskId", task.taskId)
            .put("targetTextEn", task.targetSentenceEn)
            .put("contextLines", JSONArray(task.contextLines.map { line ->
                JSONObject().put("speaker", line.speaker).put("textEn", line.textEn)
            }))
            .put("confirmedTranscript", transcript)
            .put("userTranscriptConfirmed", true)

    private fun audioBody(chapterId: String, task: FixedSceneResponseTask, audio: CapturedReadSpeakAudio) = JSONObject()
        .put("chapterId", chapterId)
        .put("sentenceId", task.sentenceId)
        .put("audioMimeType", audio.mimeType)
        .put("audioBase64", Base64.getEncoder().encodeToString(audio.file.readBytes()))
        .put("commandId", UUID.randomUUID().toString())
}
