package com.reallanguage.app.feature.review.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.learning.data.JsonPatternSpeakingWireCodec
import com.reallanguage.app.feature.learning.data.CapturedReadSpeakAudio
import com.reallanguage.app.feature.learning.data.ReadSpeakAudioCapture
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.feature.learning.domain.PatternPracticeExample
import com.reallanguage.app.feature.learning.domain.PatternPracticeGenerationResult
import com.reallanguage.app.feature.review.domain.PatternIndependentDecision
import com.reallanguage.app.feature.review.domain.PatternIndependentEvaluation
import com.reallanguage.app.feature.review.domain.PatternIndependentEvaluationResult
import com.reallanguage.app.feature.review.domain.PatternIndependentGenerationResult
import com.reallanguage.app.feature.review.domain.PatternIndependentRepository
import com.reallanguage.app.feature.review.domain.PatternIndependentReceiptStore
import com.reallanguage.app.feature.review.domain.PatternIndependentReceipt
import com.reallanguage.app.feature.review.domain.PatternIndependentGenerationMemory
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject

class AndroidPatternIndependentRepository(
    private val transport: HttpTransport,
    private val runtime: SessionRuntime,
    private val chapter: FixedLearningChapter,
    private val capture: ReadSpeakAudioCapture,
    private val receiptStore: PatternIndependentReceiptStore? = null,
) : PatternIndependentRepository {
    private val ownerAccountId = runtime.accountId
    private val ownerEpoch = runtime.accountEpoch
    private fun ownerIsActive() = ownerAccountId != null && runtime.accountId == ownerAccountId &&
        runtime.accountEpoch == ownerEpoch && runtime.accessToken != null
    private val patterns = mutableMapOf<String, LockedSpeakingPattern>()
    private val examples = mutableMapOf<Pair<String, String>, PatternPracticeExample>()
    private val generationCodec = JsonPatternSpeakingWireCodec()
    private val generationMemory = receiptStore?.generationMemory ?: PatternIndependentGenerationMemory()

    private var generation = 0L
    private var capturing = false
    private var currentAttempt: EvaluationAttempt? = null

    private class EvaluationAttempt(val generation: Long) {
        // Explicit cancellation detaches the lock too. New audio never waits for stale HTTP.
        val mutex = Mutex()
        var pattern: LockedSpeakingPattern? = null
        var example: PatternPracticeExample? = null
        var audio: CapturedReadSpeakAudio? = null
        var body: String? = null
        var commandId: String? = null
        var audioSha256: String? = null
        var requestSha256: String? = null
        var candidate: PatternIndependentEvaluationResult.Ready? = null
        var delivered = false
        var blocked = false
    }

    @Synchronized
    override fun startCapture(): Boolean {
        if (!ownerIsActive() || receiptStore?.isActive() == false) { cancelCapture(); return false }
        val previous = currentAttempt
        if (previous?.mutex?.isLocked == true || previous?.let(::needsRetry) == true) return false
        if (capturing) return true
        previous?.let(::discardAudio)
        val attempt = EvaluationAttempt(++generation)
        currentAttempt = attempt
        capturing = true
        return try {
            (capture.start() && isCurrent(attempt)).also { if (!it) cancelCapture() }
        } catch (failure: Exception) { cancelCapture(); throw failure }
    }

    @Synchronized
    override fun cancelCapture() {
        generation++
        val detached = currentAttempt
        currentAttempt = null
        capturing = false
        try { capture.cancel() } finally { detached?.let(::discardAudio) }
    }

    @Synchronized
    override fun hasPendingEvaluationRetry(): Boolean = currentAttempt?.let {
        isCurrent(it) && !it.mutex.isLocked && needsRetry(it)
    } == true

    @Synchronized
    override fun technicalRetryBlocked(): Boolean = currentAttempt?.let { isCurrent(it) && it.blocked } == true

    private fun needsRetry(attempt: EvaluationAttempt) = !attempt.delivered && !attempt.blocked &&
        (attempt.body != null || attempt.candidate != null) && attempt.audio?.file?.isFile == true

    override suspend fun generate(pattern: LockedSpeakingPattern): PatternIndependentGenerationResult {
        if (!ownerIsActive() || receiptStore?.isActive() == false) return PatternIndependentGenerationResult.Failed
        val source = chapter.sentences.firstOrNull { it.sentenceId == pattern.sourceSentenceId }
            ?: return PatternIndependentGenerationResult.Failed
        return try {
            // Hash the actual wire facts, not merely a pattern ID. The same live
            // entry shares its nonce and deliveries across recreated repositories.
            val payload = JSONObject(generationCodec.generationRequest(chapter.chapterId, source.textEn, source.textZh, pattern, ""))
                .apply { remove("commandId") }
            val commandId = generationMemory.commandId(receiptStore?.identity, payload.toString())
                ?: return PatternIndependentGenerationResult.Failed
            generationMemory.read(commandId)?.let { delivered ->
                if (!ownerIsActive() || receiptStore?.isActive() == false) return PatternIndependentGenerationResult.Failed
                return acceptGenerated(pattern, delivered)
            }
            val response = execute(
                "/api/v1/learning/pattern-practice-sets",
                payload.put("commandId", commandId).toString(),
            ) ?: return PatternIndependentGenerationResult.Failed
            val generated = generationCodec.generationResult(response)
            if (!ownerIsActive() || receiptStore?.isActive() == false) return PatternIndependentGenerationResult.Failed
            val result = when (generated) {
                is PatternPracticeGenerationResult.Ready -> PatternIndependentGenerationResult.Ready(generated.practiceSet)
                PatternPracticeGenerationResult.NotNaturallyVerifiable -> PatternIndependentGenerationResult.NotNaturallyVerifiable
                PatternPracticeGenerationResult.Failed -> PatternIndependentGenerationResult.Failed
            }
            if (!generationMemory.remember(commandId, result)) PatternIndependentGenerationResult.Failed
            else acceptGenerated(pattern, result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PatternIndependentGenerationResult.Failed
        }
    }

    @Synchronized
    private fun acceptGenerated(pattern: LockedSpeakingPattern, result: PatternIndependentGenerationResult): PatternIndependentGenerationResult {
        examples.keys.filter { it.first == pattern.patternId }.forEach { examples.remove(it) }
        if (result is PatternIndependentGenerationResult.Ready) {
            patterns[pattern.patternId] = pattern
            result.practiceSet.examples.forEach { examples[pattern.patternId to it.exampleId] = it }
        } else patterns.remove(pattern.patternId)
        return result
    }

    override suspend fun evaluate(patternId: String, exampleId: String): PatternIndependentEvaluationResult {
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        val receipts = receiptStore ?: return PatternIndependentEvaluationResult.Failed
        val attempt = synchronized(this) {
            if (!ownerIsActive() || !receipts.isActive()) { cancelCapture(); null }
            else currentAttempt?.takeIf(::isCurrent)
        } ?: return PatternIndependentEvaluationResult.Failed
        if (!attempt.mutex.tryLock()) return PatternIndependentEvaluationResult.Failed
        try {
            val body = synchronized(this) {
                coroutine.ensureActive()
                if (!isCurrent(attempt) || receipts.identity.originalChapterId != chapter.chapterId ||
                    receipts.identity.targetId != patternId) return@synchronized null
                val pattern = patterns[patternId] ?: return@synchronized null
                val example = examples[patternId to exampleId] ?: return@synchronized null
                if (pattern.sourceSentenceId != receipts.identity.sourceSentenceId ||
                    (attempt.pattern != null && attempt.pattern != pattern) ||
                    (attempt.example != null && attempt.example != example)) return@synchronized null
                attempt.pattern = pattern
                attempt.example = example
                if (attempt.delivered) return requireNotNull(attempt.candidate)
                if (attempt.blocked) return@synchronized null
                if (attempt.audio == null) {
                    if (!capturing) return@synchronized null
                    capturing = false
                    val audio = capture.finish() ?: return@synchronized null
                    if (!isCurrent(attempt)) { audio.file.delete(); return@synchronized null }
                    attempt.audio = audio
                }
                val audio = attempt.audio?.takeIf { it.file.isFile } ?: return@synchronized null
                attempt.body ?: run {
                    val bytes = audio.file.readBytes()
                    val commandId = UUID.randomUUID().toString()
                    JSONObject()
                    .put("chapterId", chapter.chapterId)
                    .put("patternId", patternId)
                    .put("exampleId", exampleId)
                    .put("targetResponseEn", example.targetResponseEn)
                    .put("targetStructure", pattern.structure)
                    .put("targetHidden", true)
                    .put("answerAudioPlayed", false)
                    .put("helpUsed", false)
                    .put("audioMimeType", audio.mimeType)
                    .put("audioBase64", Base64.getEncoder().encodeToString(bytes))
                    .put("commandId", commandId)
                    .toString()
                    .also {
                        attempt.body = it
                        attempt.commandId = commandId
                        attempt.audioSha256 = sha256(bytes)
                        attempt.requestSha256 = sha256(it.toByteArray(Charsets.UTF_8))
                    }
                }
            } ?: return PatternIndependentEvaluationResult.Failed
            var candidate = synchronized(this) { attempt.candidate.takeIf { isCurrent(attempt) } }
            if (candidate == null) {
                val token = synchronized(this) {
                    coroutine.ensureActive()
                    runtime.accessToken.takeIf { isCurrent(attempt) }
                } ?: return PatternIndependentEvaluationResult.Failed
                val response = transport.execute(HttpRequest("POST", "/api/v1/learning/pattern-independent-evaluation",
                    body, mapOf("Authorization" to "Bearer $token")))
                coroutine.ensureActive()
                if (synchronized(this) { !isCurrent(attempt) }) return PatternIndependentEvaluationResult.Failed
                if (response.statusCode != 200) {
                    synchronized(this) {
                        if (isCurrent(attempt) && blocksTechnicalRetry(response)) {
                            attempt.blocked = true
                            discardAudio(attempt)
                            attempt.body = null
                        }
                    }
                    return PatternIndependentEvaluationResult.Failed
                }
                val json = JSONObject(response.body)
                val rawDecision = json.opt("decision") as? String ?: return PatternIndependentEvaluationResult.Failed
                val feedback = (json.opt("feedbackZh") as? String)?.takeIf(String::isNotBlank)
                    ?: return PatternIndependentEvaluationResult.Failed
                val structure = json.opt("structurePreserved") as? Boolean ?: return PatternIndependentEvaluationResult.Failed
                val suitable = json.opt("contentSuitable") as? Boolean ?: return PatternIndependentEvaluationResult.Failed
                val pronunciation = json.opt("pronunciationIntelligible") as? Boolean ?: return PatternIndependentEvaluationResult.Failed
                val confidence = (json.opt("confidence") as? Number)?.toDouble() ?: return PatternIndependentEvaluationResult.Failed
                if (!confidence.isFinite() || confidence !in 0.0..1.0) return PatternIndependentEvaluationResult.Failed
                val candidateDecision = PatternIndependentDecision.valueOf(rawDecision)
                val allSatisfied = structure && suitable && pronunciation
                val contradictory = (candidateDecision == PatternIndependentDecision.PASS && !allSatisfied) ||
                    (candidateDecision == PatternIndependentDecision.FAIL && allSatisfied)
                val decision = if (confidence < .6 || contradictory) PatternIndependentDecision.INCONCLUSIVE else candidateDecision
                val receipt = if (decision == PatternIndependentDecision.INCONCLUSIVE) null else PatternIndependentReceipt(
                    version = 1, identity = receipts.identity, exampleId = exampleId, commandId = requireNotNull(attempt.commandId),
                    audioSha256 = requireNotNull(attempt.audioSha256), requestSha256 = requireNotNull(attempt.requestSha256),
                    decision = decision, structurePreserved = structure, contentSuitable = suitable,
                    pronunciationIntelligible = pronunciation, confidence = confidence,
                )
                candidate = PatternIndependentEvaluationResult.Ready(
                    PatternIndependentEvaluation(decision, feedback, receipt),
                )
                synchronized(this) {
                    coroutine.ensureActive()
                    if (!isCurrent(attempt)) return PatternIndependentEvaluationResult.Failed
                    attempt.candidate = candidate
                }
            }
            coroutine.ensureActive()
            if (synchronized(this) { !isCurrent(attempt) }) return PatternIndependentEvaluationResult.Failed
            val result = requireNotNull(candidate)
            result.evaluation.receipt?.let {
                if (!receipts.register(it) { synchronized(this) { isCurrent(attempt) } }) {
                    return PatternIndependentEvaluationResult.Failed
                }
            }
            return synchronized(this) {
                coroutine.ensureActive()
                if (!isCurrent(attempt)) PatternIndependentEvaluationResult.Failed
                else result.also {
                    attempt.delivered = true
                    discardAudio(attempt)
                    attempt.body = null
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            coroutine.ensureActive()
            return PatternIndependentEvaluationResult.Failed
        } finally {
            try { synchronized(this) { if (!isCurrent(attempt)) discardAudio(attempt) } }
            finally { attempt.mutex.unlock() }
        }
    }

    private fun isCurrent(attempt: EvaluationAttempt): Boolean = ownerIsActive() && receiptStore?.isActive() == true &&
        currentAttempt === attempt && generation == attempt.generation &&
        (attempt.pattern?.let { patterns[it.patternId] == it } != false) &&
        (attempt.pattern?.let { pattern ->
            attempt.example?.let { examples[pattern.patternId to it.exampleId] == it }
        } != false)

    private fun discardAudio(attempt: EvaluationAttempt) {
        try { attempt.audio?.file?.delete() } finally { attempt.audio = null }
    }

    private fun blocksTechnicalRetry(response: HttpResponse): Boolean {
        if (response.statusCode == 410) return true
        if (response.statusCode != 409) return false
        val error = runCatching { JSONObject(response.body).getJSONObject("error") }.getOrNull() ?: return true
        return error.opt("code") != "AI_OPERATION_PROCESSING" || error.opt("retryable") != true
    }

    private suspend fun execute(path: String, body: String): String? {
        if (!ownerIsActive() || receiptStore?.isActive() == false) return null
        val token = runtime.accessToken ?: return null
        val response = transport.execute(HttpRequest("POST", path, body, mapOf("Authorization" to "Bearer $token")))
        return response.body.takeIf { response.statusCode == 200 && ownerIsActive() && receiptStore?.isActive() != false }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
