package com.reallanguage.app.feature.learning.data

import android.content.Context
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.formation.data.decodeSourceEvidence
import com.reallanguage.app.feature.formation.domain.isValidFor
import com.reallanguage.app.feature.formation.domain.uniqueLiteralSourceEvidence
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.feature.learning.domain.PatternPracticeExample
import com.reallanguage.app.feature.learning.domain.PatternPracticeFeedback
import com.reallanguage.app.feature.learning.domain.PatternPracticeFeedbackResult
import com.reallanguage.app.feature.learning.domain.PatternPracticeGenerationResult
import com.reallanguage.app.feature.learning.domain.PatternPracticeSet
import com.reallanguage.app.feature.learning.domain.PatternSpeakingRepository
import com.reallanguage.app.feature.learning.domain.PatternFeedbackRecovery
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AndroidLockedPatternReader(
    context: Context,
    private val runtime: SessionRuntime,
) {
    private val applicationContext = context.applicationContext
    private val ownerAccountId = runtime.accountId
    private val ownerAccountEpoch = runtime.accountEpoch
    private fun ownerIsActive() = ownerAccountId != null && runtime.accountId == ownerAccountId &&
        runtime.accountEpoch == ownerAccountEpoch && runtime.accessToken != null

    fun read(chapterId: String, sentenceId: String): List<LockedSpeakingPattern>? = readPlan(chapterId, sentenceId, removed = false)

    /** Full immutable identities, authorized by exact owner receipts, for safe old-progress projection. */
    fun readRemoved(chapterId: String, sentenceId: String): List<LockedSpeakingPattern>? = readPlan(chapterId, sentenceId, removed = true)

    private fun readPlan(chapterId: String, sentenceId: String, removed: Boolean): List<LockedSpeakingPattern>? {
        if (!ownerIsActive()) return null
        return try { FormationDatabase(applicationContext).use { helper ->
            val db = helper.readableDatabase
            check(ownerIsActive())
            db.beginTransactionNonExclusive()
            try {
            val sourceText = db.rawQuery("select s.text_en from chapters c join fixed_sentences s on s.account_id=c.account_id and s.chapter_id=c.chapter_id where c.account_id=? and c.chapter_id=? and s.unit_id=? and s.role='FOCUS'", arrayOf(ownerAccountId, chapterId, sentenceId)).use {
                check(it.moveToFirst())
                it.getString(0).also { text -> check(text.isNotBlank() && !it.moveToNext()) }
            }
            val targets = db.rawQuery(
                "select t.target_id,t.unit_id,t.display,t.explanation_zh,t.source_evidence from target_selections t where t.account_id=? and t.chapter_id=? and t.unit_id=? and t.type='PATTERN' and " +
                    (if (removed) "not " else "") + "($ACTIVE_FIXED_TARGET_PREDICATE) order by t.target_id",
                arrayOf(ownerAccountId, chapterId, sentenceId),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        check(cursor.getString(2).isNotBlank() && cursor.getString(3).isNotBlank())
                        val evidence = if (cursor.isNull(4))
                            requireNotNull(uniqueLiteralSourceEvidence(sourceText, cursor.getString(2))) else
                            requireNotNull(decodeSourceEvidence(JSONObject(cursor.getString(4))))
                                .also { check(it.isValidFor(sourceText)) }
                        add(LockedSpeakingPattern(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3)) to evidence.startUtf16)
                    }
                }
            }
            check(ownerIsActive())
            db.setTransactionSuccessful()
            // Legacy compatibility may locate only a unique complete literal in memory.
            // Never invent abstract spans, rewrite locked content or drop an unlocatable target.
            targets.sortedBy { it.second }.map { it.first }
            } finally { db.endTransaction() }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
}

class AndroidPatternSpeakingRepository(
    private val transport: HttpTransport,
    private val runtime: SessionRuntime,
    private val chapter: FixedLearningChapter,
    private val capture: ReadSpeakAudioCapture,
    private val codec: PatternSpeakingWireCodec = JsonPatternSpeakingWireCodec(),
    private val isCaptureAllowed: () -> Boolean = { true },
    recoveryJournalFactory: ((LockedSpeakingPattern, PatternPracticeSet) -> PatternSpeakingRecoveryJournal)? = null,
) : PatternSpeakingRepository {
    private val examples = mutableMapOf<Pair<String, String>, PatternPracticeExample>()
    private val ownerAccountId = runtime.accountId
    private val ownerAccountEpoch = runtime.accountEpoch
    private val practices = mutableMapOf<String, PatternPracticeSet>()
    private val durable = recoveryJournalFactory?.let { factory -> DurablePatternFeedbackSession(
        capture, ::ownerIsActive, isCaptureAllowed, factory,
        { pattern, example, mode, audio -> codec.feedbackRequest(chapter.chapterId, pattern, example,
            if (mode == PatternFeedbackMode.Reading) "READING" else "RESPONSE", audio) },
        { body ->
            currentCoroutineContext().ensureActive()
            check(ownerIsActive())
            val response = transport.execute(HttpRequest("POST", "/api/v1/learning/pattern-practice-feedback", body,
                mapOf("Authorization" to "Bearer ${runtime.accessToken}")))
            currentCoroutineContext().ensureActive()
            check(ownerIsActive())
            if (response.statusCode == 200) PatternFeedbackDelivery(codec.feedbackResult(response.body))
            else {
                val processing = response.statusCode == 409 && runCatching {
                    JSONObject(response.body).optJSONObject("error")?.let { error ->
                        error.optString("code") == "AI_OPERATION_PROCESSING" && error.opt("retryable") == true
                    } == true
                }.getOrDefault(false)
                PatternFeedbackDelivery(PatternPracticeFeedbackResult.Failed,
                    !processing && response.statusCode !in setOf(401, 408, 429) && response.statusCode !in 500..599)
            }
        },
    ) }
    override val requiresFeedbackRecovery: Boolean get() = durable != null
    override suspend fun restoreFeedback(pattern: LockedSpeakingPattern, practiceSet: PatternPracticeSet): PatternFeedbackRecovery {
        if (!ownerIsActive()) return PatternFeedbackRecovery.Unavailable
        return durable?.restore(pattern, practiceSet) ?: PatternFeedbackRecovery.None
    }
    override suspend fun prepareFeedbackCapture(patternId: String, exampleId: String, reading: Boolean): Boolean {
        val session = durable ?: return true
        if (!ownerIsActive()) return false
        val pattern = patternCache[patternId] ?: return false
        val set = practices[patternId] ?: return false
        if (session.restore(pattern, set) == PatternFeedbackRecovery.Unavailable) return false
        return session.prepare(patternId, exampleId, reading)
    }
    override suspend fun releaseSupersededFeedback(): Boolean = durable?.releaseSuperseded() ?: true
    private val generationMutex = Mutex()
    private val deliveredPractices = mutableMapOf<String, PatternPracticeGenerationResult>()
    private data class GenerationIdentity(val commandId: String, val epoch: Long, val attempt: Long)
    private val activeGenerations = mutableMapOf<String, GenerationIdentity>()
    private var cancellationEpoch = 0L
    private var nextGenerationAttempt = 0L
    private val feedbackMutex = Mutex()
    private var captureGeneration = 0L
    private data class FeedbackAttempt(
        val generation: Long,
        val patternId: String,
        val exampleId: String,
        val mode: String,
        val audio: CapturedReadSpeakAudio,
        var body: String? = null,
        var delivered: PatternPracticeFeedbackResult? = null,
        var blocked: Boolean = false,
    )
    private var feedbackAttempt: FeedbackAttempt? = null
    @Synchronized
    override fun hasPendingFeedbackRetry() = durable?.pending() ?: (feedbackAttempt?.let {
        feedbackIsCurrent(it) && !it.blocked && it.delivered == null && it.body != null && it.audio.file.isFile
    } == true)
    @Synchronized
    override fun technicalRetryBlocked() = durable?.blocked() ?: (feedbackAttempt?.let { feedbackIsCurrent(it) && it.blocked } == true)
    private fun ownerIsActive() = ownerAccountId != null && runtime.accountId == ownerAccountId &&
        runtime.accountEpoch == ownerAccountEpoch && runtime.accessToken != null

    @Synchronized
    override fun startCapture(): Boolean {
        durable?.let { return it.start() }
        if (!ownerIsActive()) { capture.cancel(); return false }
        if (!isCaptureAllowed() || feedbackMutex.isLocked) return false
        captureGeneration++
        feedbackAttempt?.audio?.file?.delete()
        feedbackAttempt = null
        if (!capture.start()) return false
        if (!ownerIsActive() || !isCaptureAllowed()) { capture.cancel(); return false }
        return true
    }
    @Synchronized
    override fun cancelCapture() {
        cancellationEpoch++
        captureGeneration++
        activeGenerations.clear()
        feedbackAttempt?.audio?.file?.delete()
        feedbackAttempt = null
        if (durable != null) durable.cancel() else capture.cancel()
    }

    @Synchronized
    override fun restorePractice(pattern: LockedSpeakingPattern, practiceSet: PatternPracticeSet) {
        check(ownerIsActive())
        val commandId = generationCommandId(pattern)
        require(validPractice(pattern, practiceSet))
        // Persisted, already-confirmed examples take precedence over a late generation response.
        activeGenerations.remove(pattern.patternId)
        acceptPractice(pattern, commandId, PatternPracticeGenerationResult.Ready(practiceSet))
    }

    override suspend fun generate(pattern: LockedSpeakingPattern): PatternPracticeGenerationResult {
        if (!ownerIsActive()) return PatternPracticeGenerationResult.Failed
        return try {
            val commandId = generationCommandId(pattern)
            val source = chapter.sentences.single { it.sentenceId == pattern.sourceSentenceId && it.isFocus }
            val identity = synchronized(this) {
                activeGenerations[pattern.patternId]?.takeIf { it.commandId == commandId && it.epoch == cancellationEpoch }
                    ?: GenerationIdentity(commandId, cancellationEpoch, ++nextGenerationAttempt)
                        .also { activeGenerations[pattern.patternId] = it }
            }
            generationMutex.withLock {
                val cached = synchronized(this) {
                    if (!generationIsCurrent(pattern, identity)) PatternPracticeGenerationResult.Failed
                    else deliveredPractices[commandId]?.also { acceptPractice(pattern, commandId, it) }
                }
                if (cached != null) return@withLock cached
                val response = execute(
                    "/api/v1/learning/pattern-practice-sets",
                    codec.generationRequest(chapter.chapterId, source.textEn, source.textZh, pattern, commandId),
                ) ?: return@withLock PatternPracticeGenerationResult.Failed
                if (!generationIsCurrent(pattern, identity)) return@withLock PatternPracticeGenerationResult.Failed
                val result = codec.generationResult(response)
                if (result is PatternPracticeGenerationResult.Ready && !validPractice(pattern, result.practiceSet)) {
                    return@withLock PatternPracticeGenerationResult.Failed
                }
                synchronized(this) {
                    if (!generationIsCurrent(pattern, identity)) PatternPracticeGenerationResult.Failed
                    else result.also { if (it != PatternPracticeGenerationResult.Failed) acceptPractice(pattern, commandId, it) }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            PatternPracticeGenerationResult.Failed
        }
    }

    private fun generationCommandId(pattern: LockedSpeakingPattern): String {
        check(ownerIsActive())
        val source = chapter.sentences.single { it.sentenceId == pattern.sourceSentenceId && it.isFocus }
        val facts = listOf("ordinary-pattern-practice-v1", requireNotNull(ownerAccountId), chapter.chapterId,
            source.sentenceId, source.textEn, source.textZh, pattern.patternId, pattern.structure, pattern.explanationZh)
        require(facts.all(String::isNotBlank))
        // Length-delimited exact values preserve all provider-input distinctions, including whitespace.
        val fingerprint = facts.joinToString("") { "${it.length}:$it" }
        return "lr07-practice-" + MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    private fun generationIsCurrent(pattern: LockedSpeakingPattern, identity: GenerationIdentity) =
        ownerIsActive() && cancellationEpoch == identity.epoch && activeGenerations[pattern.patternId] == identity

    private fun validPractice(pattern: LockedSpeakingPattern, set: PatternPracticeSet) =
        set.patternId == pattern.patternId && set.examples.size in 1..5 &&
            set.examples.map { it.exampleId }.distinct().size == set.examples.size &&
            set.examples.all { listOf(it.exampleId, it.sceneZh, it.aiPromptEn, it.targetResponseEn, it.targetResponseZh).all(String::isNotBlank) }

    /** Called only while holding this repository's state lock. */
    private fun acceptPractice(pattern: LockedSpeakingPattern, commandId: String, result: PatternPracticeGenerationResult) {
        deliveredPractices[commandId] = result
        patternCache[pattern.patternId] = pattern
        examples.keys.filter { it.first == pattern.patternId }.forEach(examples::remove)
        if (result is PatternPracticeGenerationResult.Ready) {
            practices[pattern.patternId] = result.practiceSet
            result.practiceSet.examples.forEach { examples[pattern.patternId to it.exampleId] = it }
        }
    }

    override suspend fun readingFeedback(patternId: String, exampleId: String) =
        feedback(patternId, exampleId, "READING")

    override suspend fun responseFeedback(patternId: String, exampleId: String) =
        feedback(patternId, exampleId, "RESPONSE")

    private suspend fun feedback(
        patternId: String,
        exampleId: String,
        mode: String,
    ): PatternPracticeFeedbackResult {
        durable?.let { return it.feedback(patternId, exampleId, mode == "READING") }
        if (!ownerIsActive()) { cancelCapture(); return PatternPracticeFeedbackResult.Failed }
        if (!feedbackMutex.tryLock()) return PatternPracticeFeedbackResult.Failed
        var pending: FeedbackAttempt? = null
        return try {
            currentCoroutineContext().ensureActive()
            val pattern = findPattern(patternId) ?: return PatternPracticeFeedbackResult.Failed
            val example = examples[patternId to exampleId] ?: return PatternPracticeFeedbackResult.Failed
            val attempt = synchronized(this) {
                feedbackAttempt ?: capture.finish()?.let { audio ->
                    FeedbackAttempt(captureGeneration, patternId, exampleId, mode, audio)
                        .also { feedbackAttempt = it }
                }
            } ?: return PatternPracticeFeedbackResult.Failed
            pending = attempt
            if (!feedbackIsCurrent(attempt) || attempt.patternId != patternId ||
                attempt.exampleId != exampleId || attempt.mode != mode || attempt.blocked
            ) return PatternPracticeFeedbackResult.Failed
            attempt.delivered?.let { return it }
            val body = attempt.body ?: codec.feedbackRequest(chapter.chapterId, pattern, example, mode, attempt.audio)
                .also { attempt.body = it }
            currentCoroutineContext().ensureActive()
            if (!feedbackIsCurrent(attempt)) return PatternPracticeFeedbackResult.Failed
            val token = runtime.accessToken ?: return PatternPracticeFeedbackResult.Failed
            val response = transport.execute(HttpRequest("POST", "/api/v1/learning/pattern-practice-feedback",
                body, mapOf("Authorization" to "Bearer $token")))
            currentCoroutineContext().ensureActive()
            if (!feedbackIsCurrent(attempt)) return PatternPracticeFeedbackResult.Failed
            if (response.statusCode != 200) {
                val processing = response.statusCode == 409 && runCatching {
                    JSONObject(response.body).getJSONObject("error").let {
                        it.getString("code") == "AI_OPERATION_PROCESSING" && it.opt("retryable") == true
                    }
                }.getOrDefault(false)
                if (!processing && response.statusCode !in listOf(401, 408, 429) && response.statusCode !in 500..599) {
                    attempt.blocked = true
                    attempt.body = null
                    attempt.audio.file.delete()
                }
                return PatternPracticeFeedbackResult.Failed
            }
            val result = codec.feedbackResult(response.body)
            currentCoroutineContext().ensureActive()
            synchronized(this) {
                if (!feedbackIsCurrent(attempt)) PatternPracticeFeedbackResult.Failed
                else result.also {
                    if (it != PatternPracticeFeedbackResult.Failed) {
                        attempt.delivered = it
                        attempt.body = null
                        attempt.audio.file.delete()
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            synchronized(this) {
                if (feedbackAttempt === pending) {
                    feedbackAttempt = null
                    pending?.audio?.file?.delete()
                    captureGeneration++
                }
            }
            throw cancelled
        }
        catch (_: Exception) {
            PatternPracticeFeedbackResult.Failed
        } finally {
            pending?.takeUnless(::feedbackIsCurrent)?.audio?.file?.delete()
            feedbackMutex.unlock()
        }
    }

    @Synchronized
    private fun feedbackIsCurrent(attempt: FeedbackAttempt) = ownerIsActive() &&
        attempt.generation == captureGeneration && feedbackAttempt === attempt

    private fun findPattern(patternId: String): LockedSpeakingPattern? = patternCache[patternId]

    private val patternCache = mutableMapOf<String, LockedSpeakingPattern>()

    private suspend fun execute(path: String, body: String): String? {
        if (!ownerIsActive()) return null
        val token = runtime.accessToken ?: return null
        val response = transport.execute(HttpRequest("POST", path, body, mapOf("Authorization" to "Bearer $token")))
        return response.body.takeIf { response.statusCode == 200 && ownerIsActive() }
    }

}

interface PatternSpeakingWireCodec {
    fun generationRequest(chapterId: String, sourceTextEn: String, sourceTextZh: String, pattern: LockedSpeakingPattern, commandId: String? = null): String
    fun generationResult(body: String): PatternPracticeGenerationResult
    fun feedbackRequest(chapterId: String, pattern: LockedSpeakingPattern, example: PatternPracticeExample, mode: String, audio: CapturedReadSpeakAudio): String
    fun feedbackResult(body: String): PatternPracticeFeedbackResult
}

class JsonPatternSpeakingWireCodec : PatternSpeakingWireCodec {
    override fun generationRequest(chapterId: String, sourceTextEn: String, sourceTextZh: String, pattern: LockedSpeakingPattern, commandId: String?) = JSONObject()
        .put("chapterId", chapterId).put("sourceSentenceId", pattern.sourceSentenceId)
        .put("sourceTextEn", sourceTextEn).put("sourceTextZh", sourceTextZh)
        .put("patternId", pattern.patternId).put("structure", pattern.structure)
        .put("explanationZh", pattern.explanationZh).put("commandId", commandId ?: UUID.randomUUID().toString()).toString()

    override fun generationResult(body: String): PatternPracticeGenerationResult = JSONObject(body).let { json ->
        if (json.getString("status") == "NOT_NATURALLY_VERIFIABLE") return PatternPracticeGenerationResult.NotNaturallyVerifiable
        require(json.getString("status") == "READY")
        val array = json.getJSONArray("examples")
        PatternPracticeGenerationResult.Ready(PatternPracticeSet(json.getString("patternId"), List(array.length()) { index ->
            array.getJSONObject(index).let { item -> PatternPracticeExample(item.getString("exampleId"), item.getString("sceneZh"), item.getString("aiPromptEn"), item.getString("targetResponseEn"), item.getString("targetResponseZh")) }
        }))
    }

    override fun feedbackRequest(chapterId: String, pattern: LockedSpeakingPattern, example: PatternPracticeExample, mode: String, audio: CapturedReadSpeakAudio) = JSONObject()
        .put("chapterId", chapterId).put("patternId", pattern.patternId).put("exampleId", example.exampleId)
        .put("mode", mode).put("targetTextEn", example.targetResponseEn).put("targetStructure", pattern.structure)
        .put("targetVisible", true).put("audioMimeType", audio.mimeType)
        .put("audioBase64", Base64.getEncoder().encodeToString(audio.file.readBytes()))
        .put("commandId", UUID.randomUUID().toString()).toString()

    override fun feedbackResult(body: String): PatternPracticeFeedbackResult = JSONObject(body).let { json ->
        when (json.speechString("status")) {
            "INCONCLUSIVE" -> PatternPracticeFeedbackResult.Inconclusive
            "READY" -> {
                json.speechConfidence("confidence")
                PatternPracticeFeedbackResult.Ready(PatternPracticeFeedback(
                    json.speechString("feedbackZh"), json.speechBoolean("repeatRecommended"),
                    json.speechString("transcript", allowEmpty = true),
                ))
            }
            else -> error("Invalid pattern feedback status")
        }
    }
}
