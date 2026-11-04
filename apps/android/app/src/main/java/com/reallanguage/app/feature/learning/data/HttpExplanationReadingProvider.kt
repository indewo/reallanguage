package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.learning.domain.ExplanationContentResult
import com.reallanguage.app.feature.learning.domain.ExplanationPreparationRepository
import com.reallanguage.app.feature.learning.domain.ExplanationPreparationResult
import com.reallanguage.app.feature.learning.domain.ExplanationReadingProvider
import com.reallanguage.app.feature.learning.domain.ExpressionExplanation
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.SelectableExpression
import com.reallanguage.app.feature.learning.domain.LockedExplanationContext
import com.reallanguage.app.feature.learning.domain.LockedExplanationTarget
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import com.reallanguage.app.feature.formation.domain.SourceEvidence
import com.reallanguage.app.feature.formation.domain.isValidFor
import com.reallanguage.app.feature.formation.data.toJson
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class HttpExplanationReadingProvider(
    private val transport: HttpTransport,
    private val runtime: SessionRuntime,
    private val chapter: FixedLearningChapter,
    private val focusSentenceId: String,
    private val commandId: String,
    private val codec: ExplanationReadingWireCodec = JsonExplanationReadingWireCodec(),
    private val cache: ExplanationCache? = null,
    private val selectedExpressions: suspend () -> List<String> = { emptyList() },
    private val lockedContext: (suspend () -> LockedExplanationContext?)? = null,
) : ExplanationPreparationRepository, ExplanationReadingProvider {
    private val ownerAccountId = runtime.accountId
    private val ownerEpoch = runtime.accountEpoch
    private val focus = chapter.sentences.singleOrNull { it.sentenceId == focusSentenceId && it.isFocus }
    private var resolvedExpressions: List<SelectableExpression>? = null
    private var resolvedContext: LockedExplanationContext? = null
    private val expressionLock = Mutex()
    private fun isCurrentOwner() = runtime.accountId == ownerAccountId && runtime.accountEpoch == ownerEpoch && runtime.accessToken != null
    override suspend fun prepare(): ExplanationPreparationResult {
        return try {
            val context = readLockedContext()
            val expressions = expressions(context)
            val result = sentenceRequest(1, null, context)
            if (result is ExplanationContentResult.Ready) ExplanationPreparationResult.Ready(result.explanationZh, expressions) else ExplanationPreparationResult.Failed
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ExplanationPreparationResult.Failed }
    }
    override suspend fun incrementalHint(sentenceId: String, round: Int, baseExplanationZh: String): ExplanationContentResult {
        if (sentenceId != focusSentenceId || round !in 2..3) return ExplanationContentResult.Failed
        return requestSafely { sentenceRequest(round, baseExplanationZh, readLockedContext()) }
    }
    override suspend fun explainExpression(sentenceId: String, expressionId: String): ExplanationContentResult {
        if (sentenceId != focusSentenceId || !isCurrentOwner() || focus == null) return ExplanationContentResult.Failed
        return requestSafely {
            val context = readLockedContext()
            val expression = expressions(context).firstOrNull { it.expressionId == expressionId }
                ?: return@requestSafely ExplanationContentResult.Failed
            val token = runtime.accessToken ?: return@requestSafely ExplanationContentResult.Failed
            val body = deliveredBody(1, null, expression, token, context) { body, cached ->
                decodeExpression(body, context)?.let { value ->
                    lockedExplanationMeaningMatches(context, expression, value, allowLegacy = cached || context == null)
                } == true
            } ?: return@requestSafely ExplanationContentResult.Failed
            val explanation = decodeExpression(body, context)
                ?.takeIf { isCurrentOwner() && it.expressionId == expressionId }
                ?: return@requestSafely ExplanationContentResult.Failed
            ExplanationContentResult.ExpressionReady(explanation)
        }
    }
    override suspend fun validateSavedExpression(sentenceId: String, explanation: ExpressionExplanation): Boolean {
        if (sentenceId != focusSentenceId || !isCurrentOwner() || focus == null) return false
        return try {
            val context = readLockedContext()
            val expression = expressions(context).singleOrNull { it.expressionId == explanation.expressionId } ?: return false
            isCurrentOwner() && lockedExplanationMeaningMatches(context, expression, explanation)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
    override suspend fun validateSavedExpressionOccurrence(sentenceId: String, expression: SelectableExpression,
        explanation: ExpressionExplanation): Boolean {
        if (sentenceId != focusSentenceId || !isCurrentOwner() || focus == null) return false
        return try {
            val context = readLockedContext()
            val authoritative = expressions(context).singleOrNull { it.expressionId == expression.expressionId } ?: return false
            expression == authoritative && isCurrentOwner() && lockedExplanationMeaningMatches(context, authoritative, explanation)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
    private suspend fun readLockedContext(): LockedExplanationContext? {
        check(isCurrentOwner() && focus != null)
        val loader = lockedContext ?: return null
        val context = requireNotNull(loader())
        currentCoroutineContext().ensureActive()
        check(isCurrentOwner())
        require(validLockedExplanationContext(chapter, focusSentenceId, context))
        return context
    }
    private fun decodeExpression(body: String, context: LockedExplanationContext?) =
        if (context == null) codec.expression(body) else codec.expressionPreservingLockedMeaning(body)

    private suspend fun sentenceRequest(round: Int, existing: String?, context: LockedExplanationContext?): ExplanationContentResult {
        if (!isCurrentOwner() || focus == null) return ExplanationContentResult.Failed
        val token = runtime.accessToken ?: return ExplanationContentResult.Failed
        return requestSafely {
            val body = deliveredBody(round, existing, null, token, context) { body, _ -> !codec.sentence(body).isNullOrBlank() }
                ?: return@requestSafely ExplanationContentResult.Failed
            codec.sentence(body)?.let(ExplanationContentResult::Ready)
                ?: ExplanationContentResult.Failed
        }
    }
    private suspend fun requestSafely(block: suspend () -> ExplanationContentResult): ExplanationContentResult =
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ExplanationContentResult.Failed }
    private suspend fun deliveredBody(round: Int, existing: String?, expression: SelectableExpression?, token: String,
        context: LockedExplanationContext?, valid: (String, Boolean) -> Boolean): String? =
        withContext(Dispatchers.IO) {
            deliveryLock.withLock {
                if (!isCurrentOwner()) return@withLock null
                val account = ownerAccountId
                if (cache != null && account == null) return@withLock null
                val key = UUID.nameUUIDFromBytes("v1:$round:${expression?.expressionId ?: "sentence"}:${existing.orEmpty()}".toByteArray(Charsets.UTF_8)).toString()
                if (cache != null && account != null) {
                    cache.read(account, chapter.chapterId, focusSentenceId, key)?.let {
                        return@withLock it.takeIf { value -> isCurrentOwner() && valid(value, true) && isCurrentOwner() }
                    }
                }
                val stableCommand = if (cache != null) UUID.nameUUIDFromBytes(
                    "$account:${chapter.chapterId}:$focusSentenceId:$key".toByteArray(Charsets.UTF_8),
                ).toString() else null
                if (!isCurrentOwner()) return@withLock null
                // Encoding/validation failures are not evidence that a request reached the service.
                val requestBody = request(round, existing, expression, stableCommand, context)
                val response = try {
                    transport.execute(HttpRequest("POST", "/api/v1/learning/explanations", requestBody, mapOf("Authorization" to "Bearer $token")))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                currentCoroutineContext().ensureActive()
                if (!isCurrentOwner()) return@withLock null
                if (response?.statusCode == 200) {
                    if (!valid(response.body, false) || !isCurrentOwner()) return@withLock null
                    if (cache != null && account != null) cache.write(account, chapter.chapterId, focusSentenceId, key, response.body)
                    return@withLock response.body.takeIf { isCurrentOwner() }
                }
                val uncertainDelivery = response == null || response.statusCode == 409 || response.statusCode in 500..599
                if (!uncertainDelivery || cache == null || account == null || stableCommand == null) return@withLock null
                val recoveryToken = runtime.accessToken ?: return@withLock null
                val kind = if (expression == null) "SENTENCE" else "EXPRESSION"
                // Same command, read only, at most once. An unavailable delivery never authorizes another POST.
                val recovered = transport.execute(HttpRequest("GET",
                    "/api/v1/learning/explanations/deliveries/$stableCommand?kind=$kind", "",
                    mapOf("Authorization" to "Bearer $recoveryToken")))
                currentCoroutineContext().ensureActive()
                if (recovered.statusCode != 200 || !isCurrentOwner()) return@withLock null
                val delivered = codec.existingDeliveryResult(recovered.body, stableCommand, kind) ?: return@withLock null
                currentCoroutineContext().ensureActive()
                // Existing delivery may predate structured details; never treat it as a new locked output.
                if (!isCurrentOwner() || !valid(delivered, true) || !isCurrentOwner()) return@withLock null
                cache.write(account, chapter.chapterId, focusSentenceId, key, delivered)
                currentCoroutineContext().ensureActive()
                if (!isCurrentOwner()) return@withLock null
                val saved = cache.read(account, chapter.chapterId, focusSentenceId, key)
                currentCoroutineContext().ensureActive()
                saved?.takeIf { it == delivered && isCurrentOwner() && valid(it, true) && isCurrentOwner() }
            }
        }
    private fun request(round: Int, existing: String?, expression: SelectableExpression?, stableCommand: String? = null,
        context: LockedExplanationContext?): String = codec.requestWithLockedTargets(
        chapter,
        focusSentenceId,
        round,
        existing,
        expression,
        stableCommand ?: "$commandId:$round:${expression?.expressionId ?: "sentence"}",
        context?.targets,
    )
    private suspend fun expressions(context: LockedExplanationContext?): List<SelectableExpression> = expressionLock.withLock {
        check(isCurrentOwner())
        resolvedExpressions?.takeIf { resolvedContext == context }?.let { return@withLock it }
        val text = requireNotNull(focus).textEn
        val words = authoritativeExplanationWords(text)
        val starts = words.map { it.startOffset }.toSet()
        val ends = words.map { it.endOffset }.toSet()
        val phrases = selectedExpressions().flatMap { display ->
            val parts = display.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.size < 2) emptyList() else {
                val pattern = parts.joinToString("\\s+") { Regex.escape(it) }
                Regex(pattern, RegexOption.IGNORE_CASE).findAll(text).filter {
                    it.range.first in starts && it.range.last + 1 in ends
                }.map { match ->
                    SelectableExpression("phrase:${match.range.first}:${match.range.last + 1}", match.value,
                        match.range.first, match.range.last + 1)
                }.toList()
            }
        }
        val lockedPhrases = context?.targets.orEmpty().flatMap { target ->
            listOfNotNull(target.sourceEvidence.takeIf { target.type == LearningTargetType.FIXED_EXPRESSION }) +
                target.relatedVocabulary.orEmpty().filter { it.unitType == LearningTargetType.FIXED_EXPRESSION }.map { it.sourceEvidence }
        }.filter { evidence -> words.count { it.startOffset >= evidence.startUtf16 && it.endOffset <= evidence.endUtf16 } >= 2 }
            .map { evidence -> SelectableExpression("phrase:${evidence.startUtf16}:${evidence.endUtf16}",
                evidence.surfaceForm, evidence.startUtf16, evidence.endUtf16) }
        // Never invent a span for a lemma or a phrase absent from the fixed sentence.
        // Keep the word tokens as well; the UI can resolve an overlapping whole phrase.
        check(isCurrentOwner())
        ((phrases + lockedPhrases).distinctBy { it.expressionId }.sortedByDescending { it.endOffset - it.startOffset } + words).also {
            resolvedExpressions = it
            resolvedContext = context
        }
    }
    private companion object { val deliveryLock = Mutex() }
}

interface ExplanationReadingWireCodec {
    fun request(
        chapter: FixedLearningChapter,
        focusSentenceId: String,
        round: Int,
        existingBaseExplanationZh: String?,
        expression: SelectableExpression?,
        commandId: String,
    ): String
    fun requestWithLockedTargets(chapter: FixedLearningChapter, focusSentenceId: String, round: Int,
        existingBaseExplanationZh: String?, expression: SelectableExpression?, commandId: String,
        lockedTargets: List<LockedExplanationTarget>?): String {
        require(lockedTargets == null) { "This codec cannot preserve locked targets" }
        return request(chapter, focusSentenceId, round, existingBaseExplanationZh, expression, commandId)
    }
    fun sentence(body: String): String?
    fun expression(body: String): ExpressionExplanation?
    fun expressionPreservingLockedMeaning(body: String): ExpressionExplanation? = expression(body)
    /** Optional read-only recovery capability. Unsupported codecs must not accept an envelope as content. */
    fun existingDeliveryResult(body: String, originalCommandId: String, expectedKind: String): String? = null
}

class JsonExplanationReadingWireCodec : ExplanationReadingWireCodec {
    override fun existingDeliveryResult(body: String, originalCommandId: String, expectedKind: String): String? {
        return try {
            require(originalCommandId.isNotBlank() && originalCommandId.length <= 128)
            require(expectedKind == "SENTENCE" || expectedKind == "EXPRESSION")
            val envelope = JSONObject(body)
            if (envelope.opt("status") != "COMPLETED") return null
            require(envelope.keys().asSequence().toSet() == setOf("status", "commandId", "provenance", "result"))
            require(envelope.opt("commandId") is String && envelope.opt("commandId") == originalCommandId)
            require(envelope.opt("provenance") == "EXISTING_DELIVERY")
            val result = envelope.get("result") as? JSONObject ?: return null
            require(result.opt("kind") == expectedKind)
            fun requiredString(json: JSONObject, key: String): String =
                (json.get(key) as? String)?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("Invalid delivery field")
            requiredString(result, "explanationZh")
            if (expectedKind == "SENTENCE") {
                require(result.keys().asSequence().toSet() == setOf("kind", "explanationZh"))
            } else {
                require(result.keys().asSequence().toSet().all { it in setOf("kind", "expressionId", "explanationZh", "phonetic",
                    "naturalExample", "contextualMeaningZh", "usageZh", "expressionHintZh") })
                require(requiredString(result, "expressionId").length <= 128)
                require(result.has("phonetic"))
                if (!result.isNull("phonetic")) requiredString(result, "phonetic")
                if (!result.isNull("naturalExample")) {
                    val example = result.get("naturalExample") as? JSONObject ?: return null
                    require(example.keys().asSequence().toSet() == setOf("textEn", "textZh"))
                    requiredString(example, "textEn"); requiredString(example, "textZh")
                }
                // Reuse the current complete-group/legacy validator, preserving the locked raw meaning.
                requireNotNull(expressionPreservingLockedMeaning(result.toString()))
            }
            result.toString()
        } catch (_: Exception) { null }
    }

    override fun requestWithLockedTargets(chapter: FixedLearningChapter, focusSentenceId: String, round: Int,
        existingBaseExplanationZh: String?, expression: SelectableExpression?, commandId: String,
        lockedTargets: List<LockedExplanationTarget>?): String {
        val body = request(chapter, focusSentenceId, round, existingBaseExplanationZh, expression, commandId)
        if (lockedTargets == null) return body
        val sentence = chapter.sentences.single { it.sentenceId == focusSentenceId && it.isFocus }
        require(validLockedExplanationContext(chapter, focusSentenceId,
            LockedExplanationContext(chapter.chapterId, focusSentenceId, sentence.textEn, lockedTargets)))
        expression?.let {
            require(SourceEvidence(1, it.startOffset, it.endOffset, it.display, it.display, listOf(it.display)).isValidFor(sentence.textEn))
        }
        return JSONObject(body).apply {
            put("lockedTargets", JSONArray(lockedTargets.map { target ->
                JSONObject().put("targetId", target.targetId).put("type", target.type.name).put("display", target.display)
                    .put("explanationZh", target.explanationZh).put("structureParts", JSONArray(target.structureParts))
                    .put("sourceEvidence", target.sourceEvidence?.toJson() ?: JSONObject.NULL)
                    .put("relatedVocabulary", target.relatedVocabulary?.let { related -> JSONArray(related.map { item ->
                        JSONObject().put("display", item.display).put("senseZh", item.senseZh).put("unitType", item.unitType.name)
                            .put("sourceEvidence", item.sourceEvidence.toJson())
                    }) } ?: JSONObject.NULL)
            }))
            expression?.let { getJSONObject("requestedExpression").put("startUtf16", it.startOffset).put("endUtf16", it.endOffset) }
        }.toString()
    }

    override fun request(
        chapter: FixedLearningChapter,
        focusSentenceId: String,
        round: Int,
        existingBaseExplanationZh: String?,
        expression: SelectableExpression?,
        commandId: String,
    ): String = JSONObject()
        .put(
            "chapter",
            JSONObject()
                .put("chapterId", chapter.chapterId)
                .put(
                    "sentences",
                    JSONArray(
                        chapter.sentences.map {
                            JSONObject()
                                .put("sentenceId", it.sentenceId)
                                .put("textEn", it.textEn)
                                .put("textZh", it.textZh)
                                .put("isFocus", it.isFocus)
                        },
                    ),
                ),
        )
        .put("focusSentenceId", focusSentenceId)
        .put("round", round)
        .put("commandId", commandId)
        .also { body ->
            existingBaseExplanationZh?.let { body.put("existingBaseExplanationZh", it) }
            expression?.let {
                body.put(
                    "requestedExpression",
                    JSONObject()
                        .put("expressionId", it.expressionId)
                        .put("display", it.display),
                )
            }
        }
        .toString()

    override fun sentence(body: String): String? = JSONObject(body)
        .takeIf { it.getString("kind") == "SENTENCE" }
        ?.getString("explanationZh")

    override fun expression(body: String): ExpressionExplanation? = decodeExpression(body, preserveRawMeaning = false)
    override fun expressionPreservingLockedMeaning(body: String): ExpressionExplanation? = decodeExpression(body, preserveRawMeaning = true)
    private fun decodeExpression(body: String, preserveRawMeaning: Boolean): ExpressionExplanation? = JSONObject(body)
        .takeIf { it.getString("kind") == "EXPRESSION" }
        ?.let {
            fun structuredString(key: String): String? = if (it.isNull(key)) null
                else (it.get(key) as? String)?.let { value -> if (preserveRawMeaning && key == "contextualMeaningZh") value else value.trim() }
                    ?: throw IllegalArgumentException("Expected a nullable string for $key")
            val meaning = structuredString("contextualMeaningZh")
            val usage = structuredString("usageZh")
            val hint = structuredString("expressionHintZh")
            if (meaning != null || usage != null || hint != null) require(it.has("expressionHintZh"))
            ExpressionExplanation(
                it.getString("expressionId"),
                it.getString("explanationZh"),
                if (it.isNull("phonetic")) null else it.getString("phonetic"),
                if (it.isNull("naturalExample")) null else it.getJSONObject("naturalExample").let { example ->
                    val english = example.getString("textEn")
                    val chinese = example.getString("textZh")
                    require(english.isNotBlank() && chinese.isNotBlank())
                    com.reallanguage.app.feature.learning.domain.NaturalExpressionExample(english, chinese)
                },
                meaning,
                usage,
                hint,
            )
        }
}
