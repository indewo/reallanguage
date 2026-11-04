package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.LearningTargetExtractionRepository
import com.reallanguage.app.feature.formation.domain.LearningTargetExtractionResult
import com.reallanguage.app.feature.formation.domain.LearningTargetGenerationResult
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import com.reallanguage.app.feature.formation.domain.QueryableExpression
import com.reallanguage.app.feature.formation.domain.wordExpressions
import com.reallanguage.app.feature.formation.domain.isValidForTarget
import com.reallanguage.app.feature.formation.domain.KnownKnowledgeCandidate
import com.reallanguage.app.feature.formation.domain.KnownKnowledgeReferenceSource
import com.reallanguage.app.feature.formation.domain.KnownKnowledgeType
import com.reallanguage.app.feature.formation.domain.isValidKnowledgeRef
import com.reallanguage.app.feature.formation.domain.hasValidStructureParts
import com.reallanguage.app.feature.formation.domain.hasValidCandidateExample
import com.reallanguage.app.feature.formation.domain.hasValidRelatedVocabularyItems
import com.reallanguage.app.feature.formation.domain.hasValidRelatedVocabularyStructure
import com.reallanguage.app.feature.formation.domain.hasConsistentVocabularyExplanations
import com.reallanguage.app.feature.formation.domain.isVocabularyBookEligible
import com.reallanguage.app.feature.formation.domain.AcceptedVocabularyContext
import com.reallanguage.app.core.vocabulary.VocabularyCatalog
import com.reallanguage.app.core.vocabulary.bestMeaningMatch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

data class LearningTargetPayload(
    val targets: List<LearningTarget>,
    val queryableExpressions: List<QueryableExpression>,
    val learningScopes: Set<String>,
)
interface LearningTargetWireCodec {
    fun request(chapters: List<EnglishChapterDraft>, selections: Map<String, String>, commandId: String, requested: Pair<String, String>? = null, requestedDisplay: String? = null): String
    fun payload(body: String): LearningTargetPayload
    fun requestWithKnowledge(chapters: List<EnglishChapterDraft>, selections: Map<String, String>, commandId: String, requested: Pair<String, String>?, requestedDisplay: String?, reusedKnowledge: Map<String, List<KnownKnowledgeCandidate>>): String = request(chapters, selections, commandId, requested, requestedDisplay)
    fun requestWithAcceptedVocabulary(chapters: List<EnglishChapterDraft>, selections: Map<String, String>, commandId: String,
        requested: Pair<String, String>?, requestedDisplay: String?, reusedKnowledge: Map<String, List<KnownKnowledgeCandidate>>,
        acceptedVocabulary: AcceptedVocabularyContext?): String {
        require(acceptedVocabulary == null) { "This codec cannot preserve accepted vocabulary" }
        return if (reusedKnowledge.values.all { it.isEmpty() }) request(chapters, selections, commandId, requested, requestedDisplay)
        else requestWithKnowledge(chapters, selections, commandId, requested, requestedDisplay, reusedKnowledge)
    }
}
class HttpLearningTargetRepository(
    private val transport: HttpTransport,
    private val runtime: SessionRuntime,
    private val chapters: List<EnglishChapterDraft>,
    private val selections: Map<String, String>,
    private val commandId: String,
    private val codec: LearningTargetWireCodec = JsonLearningTargetWireCodec(),
    initialQueryableExpressions: List<QueryableExpression> = emptyList(),
    private val knowledgeSource: KnownKnowledgeReferenceSource? = null,
    initialTargets: List<LearningTarget> = emptyList(),
    private val vocabularyCatalog: VocabularyCatalog? = null,
) : LearningTargetExtractionRepository {
    private val ownerAccountId = runtime.accountId
    private val ownerEpoch = runtime.accountEpoch
    private val sentences = chapters.flatMap { it.sentences }
    private val focusIds = sentences.filter { it.isFocus }.map { it.unitId }.toSet()
    private val validSource = sentences.isNotEmpty() && focusIds.isNotEmpty() &&
        sentences.map { it.unitId }.distinct().size == sentences.size &&
        sentences.all { sentence -> sentence.candidates.count { it.candidateId == selections[sentence.unitId] } == 1 }
    private val sentenceWords = chapters.flatMap { it.sentences }.filter { it.isFocus }.flatMap { it.wordExpressions(selections[it.unitId]) }
    private var knownExpressions = sentenceWords + initialQueryableExpressions.filterNot { it.expressionId.startsWith("word:") }
    private var acceptedTargets = initialTargets.toList()
    override suspend fun extract(): LearningTargetExtractionResult {
        val payload = request(null) ?: return LearningTargetExtractionResult.Failed
        if (!isCurrentOwner()) return LearningTargetExtractionResult.Failed
        knownExpressions = sentenceWords + payload.queryableExpressions.filterNot { it.expressionId.startsWith("word:") }
        acceptedTargets = payload.targets.toList()
        return LearningTargetExtractionResult.Extracted(payload.targets, payload.queryableExpressions)
    }
    override suspend fun generate(unitId: String, expressionId: String): LearningTargetGenerationResult {
        if (!isCurrentOwner()) return LearningTargetGenerationResult.Failed
        val requested = knownExpressions.firstOrNull { it.unitId == unitId && it.expressionId == expressionId }
            ?: return LearningTargetGenerationResult.Failed
        val accepted = try { acceptedVocabularyFor(requested) } catch (_: IllegalArgumentException) {
            return LearningTargetGenerationResult.Failed
        }
        if (expressionId.startsWith("word:")) {
            val start = expressionId.removePrefix("word:").toIntOrNull()
            val saved = acceptedTargets.singleOrNull { target ->
                target.unitId == unitId && target.type != LearningTargetType.PATTERN && target.targetId.isNotBlank() &&
                    target.display.trim().equals(requested.display, ignoreCase = true) && target.sourceEvidence?.let {
                        it.startUtf16 == start && it.endUtf16 == start?.plus(requested.display.length)
                    } == true
            }
            if (saved != null) {
                currentCoroutineContext().ensureActive()
                return if (isCurrentOwner()) LearningTargetGenerationResult.Added(saved) else LearningTargetGenerationResult.Failed
            }
        }
        val payload = request(unitId to expressionId, accepted) ?: return LearningTargetGenerationResult.Failed
        val target = payload.targets.singleOrNull()?.takeIf { target ->
            target.unitId == unitId && target.display.trim().equals(requested.display.trim(), ignoreCase = true) &&
                (!expressionId.startsWith("word:") || target.type != LearningTargetType.PATTERN) &&
                (target.sourceEvidence == null || !expressionId.startsWith("word:") ||
                    (target.sourceEvidence.startUtf16 == expressionId.removePrefix("word:").toIntOrNull() &&
                        target.sourceEvidence.surfaceForm.equals(requested.display, ignoreCase = true))) &&
                (accepted == null || (target.display == accepted.display && target.explanationZh == accepted.senseZh &&
                    target.type == accepted.unitType && target.sourceEvidence == accepted.sourceEvidence))
        } ?: return LearningTargetGenerationResult.Failed
        if (acceptedTargets.any { it.targetId == target.targetId && it != target }) return LearningTargetGenerationResult.Failed
        // A replay of the same target is reusable; a second target for the same occurrence is not.
        val merged = acceptedTargets.filterNot { it.targetId == target.targetId } + target
        if (!merged.hasConsistentVocabularyExplanations() || !isCurrentOwner()) return LearningTargetGenerationResult.Failed
        currentCoroutineContext().ensureActive()
        acceptedTargets = merged
        return LearningTargetGenerationResult.Added(target)
    }
    private fun isCurrentOwner() = runtime.accountId == ownerAccountId && runtime.accountEpoch == ownerEpoch && runtime.accessToken != null

    private fun acceptedVocabularyFor(expression: QueryableExpression): AcceptedVocabularyContext? {
        if (!expression.expressionId.startsWith("word:")) return null
        val source = sentences.single { it.unitId == expression.unitId }.let { sentence ->
            sentence.candidates.single { it.candidateId == selections[sentence.unitId] }.textEn
        }
        val current = acceptedTargets.filter { it.unitId == expression.unitId }
        require(current.all { it.hasValidRelatedVocabularyItems(source) &&
            (it.sourceEvidence?.isValidForTarget(source, it.display, it.type) != false) })
        require(current.hasConsistentVocabularyExplanations())
        val start = requireNotNull(expression.expressionId.removePrefix("word:").toIntOrNull())
        val end = start + expression.display.length
        val explanations = current.flatMap { target ->
            target.relatedVocabularyItems.orEmpty().map { item ->
                AcceptedVocabularyContext(item.display, item.senseZh, item.unitType, item.sourceEvidence)
            } + listOfNotNull(target.sourceEvidence?.takeIf { target.type != LearningTargetType.PATTERN }?.let { evidence ->
                AcceptedVocabularyContext(target.display, target.explanationZh, target.type, evidence)
            })
        }.filter { it.sourceEvidence.startUtf16 == start && it.sourceEvidence.endUtf16 == end }.distinct()
        // Never choose one of two different delivered snapshots or silently omit invalid context.
        require(explanations.size <= 1)
        return explanations.singleOrNull()
    }

    private suspend fun request(requested: Pair<String, String>?, acceptedVocabulary: AcceptedVocabularyContext? = null): LearningTargetPayload? {
        if (!validSource || runtime.accountId != ownerAccountId || runtime.accountEpoch != ownerEpoch) return null
        if (requested != null && requested.first !in focusIds) return null
        val token = runtime.accessToken ?: return null
        val revision = runtime.identityRevision.value
        return try {
            val refsBySentence = sentences.associate { sentence -> sentence.unitId to sentence.candidates.single { it.candidateId == selections[sentence.unitId] }.reusedKnowledgeRefs }
            check(refsBySentence.values.all { refs -> refs.size <= 16 && refs.distinct().size == refs.size && refs.all(::isValidKnowledgeRef) })
            val allRefs = refsBySentence.values.flatten().distinct()
            val known = if (allRefs.isEmpty()) emptyList() else knowledgeSource?.read(allRefs) ?: return null
            check(known.map { it.ref }.toSet() == allRefs.toSet() && known.size == allRefs.size)
            val knownByRef = known.associateBy { it.ref }
            val perSentence = refsBySentence.mapValues { (_, refs) -> refs.map { knownByRef.getValue(it) } }
            currentCoroutineContext().ensureActive()
            check(runtime.accountId == ownerAccountId && runtime.accessToken != null && runtime.identityRevision.value == revision)
            val expression = knownExpressions.firstOrNull { it.unitId == requested?.first && it.expressionId == requested?.second }
            val requestId = if (requested == null) commandId else java.util.UUID.nameUUIDFromBytes("$commandId:${requested.first}:${requested.second}".toByteArray()).toString()
            val body = if (acceptedVocabulary != null) {
                val base = codec.requestWithAcceptedVocabulary(chapters, selections, requestId, requested, expression?.display, perSentence, acceptedVocabulary)
                codec.requestWithAcceptedVocabulary(chapters, selections, "target-context-v1:${knowledgeDigest(base)}", requested,
                    expression?.display, perSentence, acceptedVocabulary)
            } else if (allRefs.isEmpty()) codec.request(chapters, selections, requestId, requested, expression?.display) else {
                val base = codec.requestWithKnowledge(chapters, selections, requestId, requested, expression?.display, perSentence)
                codec.requestWithKnowledge(chapters, selections, "target-known-v1:${knowledgeDigest(base)}", requested, expression?.display, perSentence)
            }
            val response = transport.execute(HttpRequest("POST", "/api/v1/content-formation/learning-targets", body, mapOf("Authorization" to "Bearer $token")))
            currentCoroutineContext().ensureActive()
            if (runtime.accountId != ownerAccountId || runtime.accessToken == null || runtime.identityRevision.value != revision || response.statusCode != 200) null
            else bindCatalogScopes(codec.payload(response.body)).takeIf { payload ->
                payload.targets.all { target -> target.unitId in focusIds && target.targetId.isNotBlank() && target.hasValidStructureParts() && target.hasValidCandidateExample() &&
                    sentences.single { it.unitId == target.unitId }.let { sentence ->
                        val english = sentence.candidates.single { it.candidateId == selections[sentence.unitId] }.textEn
                        target.isVocabularyBookEligible(english) && target.hasValidRelatedVocabularyItems(english)
                    } &&
                    (target.sourceEvidence == null || sentences.single { it.unitId == target.unitId }.let { sentence ->
                        target.sourceEvidence.isValidForTarget(
                            sentence.candidates.single { it.candidateId == selections[sentence.unitId] }.textEn,
                            target.display,
                            target.type,
                        )
                    }) } &&
                    payload.targets.map { it.targetId }.distinct().size == payload.targets.size &&
                    payload.targets.hasConsistentVocabularyExplanations() &&
                    payload.queryableExpressions.all { expression -> expression.unitId in focusIds && expression.expressionId.isNotBlank() &&
                        (!expression.expressionId.startsWith("word:") || sentenceWords.any { word ->
                            word.unitId == expression.unitId && word.expressionId == expression.expressionId && word.display.equals(expression.display.trim(), ignoreCase = true)
                        }) } &&
                    payload.queryableExpressions.map { it.unitId to it.expressionId }.distinct().size == payload.queryableExpressions.size &&
                    payload.targets.all { target -> target.knownKnowledgeRef?.let { ref ->
                        ref in refsBySentence.getValue(target.unitId) && target.sourceEvidence != null && knownTargetMatches(knownByRef.getValue(ref), target)
                    } ?: true } &&
                    payload.targets.mapNotNull { it.knownKnowledgeRef?.let { ref -> it.unitId to ref } }.let { actual ->
                        actual.distinct().size == actual.size && (requested != null || actual.toSet() == refsBySentence.filterKeys { it in focusIds }.flatMap { (unit, refs) -> refs.map { unit to it } }.toSet())
                    } && runtime.accountId == ownerAccountId && runtime.accountEpoch == ownerEpoch &&
                    runtime.accessToken != null && runtime.identityRevision.value == revision
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun bindCatalogScopes(payload: LearningTargetPayload): LearningTargetPayload {
        val catalog = vocabularyCatalog ?: return payload
        val targets = payload.targets.map { target ->
            if (target.type == LearningTargetType.PATTERN) return@map target
            val match = catalog.find(target.display).bestMeaningMatch(target.explanationZh) ?: return@map target
            target.copy(learningScopes = match.pools.mapTo(linkedSetOf()) { it.name })
        }
        return payload.copy(targets = targets)
    }
}

internal fun knownTargetMatches(known: KnownKnowledgeCandidate, target: LearningTarget): Boolean =
    known.meaningZh.trim() == target.explanationZh.trim() && when(target.type) {
        LearningTargetType.PATTERN -> known.type == KnownKnowledgeType.PATTERN && known.display.trim() == target.display.trim()
        LearningTargetType.WORD, LearningTargetType.FIXED_EXPRESSION -> known.type == KnownKnowledgeType.VOCABULARY &&
            listOfNotNull(target.display, target.sourceEvidence?.normalizedForm).any { form ->
                java.text.Normalizer.normalize(form.trim(), java.text.Normalizer.Form.NFC).lowercase(java.util.Locale.ROOT) ==
                    java.text.Normalizer.normalize(known.display.trim(), java.text.Normalizer.Form.NFC).lowercase(java.util.Locale.ROOT)
            }
    }
class JsonLearningTargetWireCodec : LearningTargetWireCodec {
    override fun requestWithAcceptedVocabulary(chapters: List<EnglishChapterDraft>, selections: Map<String, String>, commandId: String,
        requested: Pair<String, String>?, requestedDisplay: String?, reusedKnowledge: Map<String, List<KnownKnowledgeCandidate>>,
        acceptedVocabulary: AcceptedVocabularyContext?): String {
        val body = if (reusedKnowledge.values.all { it.isEmpty() }) request(chapters, selections, commandId, requested, requestedDisplay)
            else requestWithKnowledge(chapters, selections, commandId, requested, requestedDisplay, reusedKnowledge)
        if (acceptedVocabulary == null) return body
        require(requested != null && requested.second.startsWith("word:"))
        val sentence = chapters.flatMap { it.sentences }.single { it.unitId == requested.first && it.isFocus }
        val word = sentence.wordExpressions(selections[sentence.unitId]).single { it.expressionId == requested.second }
        val source = sentence.candidates.single { it.candidateId == selections[sentence.unitId] }.textEn
        require(acceptedVocabulary.display.isNotBlank() && acceptedVocabulary.senseZh.isNotBlank() &&
            acceptedVocabulary.unitType != LearningTargetType.PATTERN &&
            acceptedVocabulary.sourceEvidence.isValidForTarget(source, acceptedVocabulary.display, acceptedVocabulary.unitType) &&
            acceptedVocabulary.sourceEvidence.startUtf16 == requested.second.removePrefix("word:").toIntOrNull() &&
            acceptedVocabulary.sourceEvidence.surfaceForm == word.display &&
            acceptedVocabulary.display.trim().equals(word.display, ignoreCase = true))
        return JSONObject(body).apply {
            getJSONObject("requestedExpression").put("acceptedVocabulary", JSONObject()
                .put("display", acceptedVocabulary.display).put("senseZh", acceptedVocabulary.senseZh)
                .put("unitType", acceptedVocabulary.unitType.name).put("sourceEvidence", acceptedVocabulary.sourceEvidence.toJson()))
        }.toString()
    }

    override fun requestWithKnowledge(chapters: List<EnglishChapterDraft>, selections: Map<String, String>, commandId: String, requested: Pair<String, String>?, requestedDisplay: String?, reusedKnowledge: Map<String, List<KnownKnowledgeCandidate>>): String {
        val root = JSONObject(request(chapters, selections, commandId, requested, requestedDisplay))
        val chapterArray = root.getJSONArray("chapters")
        repeat(chapterArray.length()) { index ->
            val sentences = chapterArray.getJSONObject(index).getJSONArray("sentences")
            repeat(sentences.length()) { sentenceIndex ->
                val sentence = sentences.getJSONObject(sentenceIndex)
                sentence.put("reusedKnowledge", JSONArray(reusedKnowledge[sentence.getString("unitId")].orEmpty().map { item ->
                    JSONObject().put("ref", item.ref).put("type", item.type.name).put("display", item.display).put("meaningZh", item.meaningZh)
                }))
            }
        }
        return root.toString()
    }
    override fun request(chapters: List<EnglishChapterDraft>, selections: Map<String, String>, commandId: String, requested: Pair<String, String>?, requestedDisplay: String?): String {
        val body = JSONObject().put("chapters", JSONArray(chapters.map { chapter ->
            JSONObject().put("chapterId", chapter.chapterId).put("sentences", JSONArray(chapter.sentences.map { sentence ->
                val selected = sentence.candidates.first { it.candidateId == selections[sentence.unitId] }
                JSONObject().put("unitId", sentence.unitId).put("textZh", sentence.textZh).put("textEn", selected.textEn).put("isFocus", sentence.isFocus)
            }))
        })).put("commandId", commandId)
        requested?.let { body.put("requestedExpression", JSONObject().put("unitId", it.first).put("expressionId", it.second).apply {
            requestedDisplay?.let { put("display", it) }
        }) }
        return body.toString()
    }
    override fun payload(body: String): LearningTargetPayload {
        val root = JSONObject(body)
        val targets = root.getJSONArray("targets")
        val expressions = root.getJSONArray("queryableExpressions")
        val learningScopes = strings(root.getJSONArray("learningScopes")).toSet()
        return LearningTargetPayload(
            List(targets.length()) { index -> targets.getJSONObject(index).let { value ->
                val related = decodeRelatedVocabularyGroup(value)
                LearningTarget(
                    value.getString("targetId"), value.getString("unitId"), value.getString("display"), value.getString("explanationZh"),
                    LearningTargetType.valueOf(value.getString("type")), structureStrings(value.getJSONArray("structureParts")), structureStrings(value.getJSONArray("relatedVocabulary")), learningScopes,
                    sourceEvidence = decodeSourceEvidence(value.opt("sourceEvidence")),
                    knownKnowledgeRef = if (!value.has("reusedKnowledgeRef") || value.isNull("reusedKnowledgeRef")) null
                        else (value.get("reusedKnowledgeRef") as? String)?.takeIf(::isValidKnowledgeRef) ?: error("Invalid known target reference"),
                    candidateExample = decodeCandidateExample(value.opt("candidateExample")),
                    relatedVocabularyItems = related.first,
                    relatedVocabularyItemRefs = related.second,
                ).also {
                    require(it.hasValidStructureParts()) { "Invalid learning target structure" }
                    require(it.hasValidRelatedVocabularyStructure()) { "Invalid related vocabulary group" }
                }
            } },
            List(expressions.length()) { index -> expressions.getJSONObject(index).let {
                QueryableExpression(
                    it.getString("expressionId"),
                    it.getString("unitId"),
                    it.getString("display"),
                    it.optString("explanationZh").takeIf(String::isNotBlank),
                )
            } },
            learningScopes,
        ).also { require(it.targets.hasConsistentVocabularyExplanations()) { "Conflicting vocabulary explanations" } }
    }
    // JSONObject.getString coerces numbers/objects. A structure part must already
    // be a real string; keep its exact spelling and order, including meaningful spacing.
    private fun structureStrings(array: JSONArray) = List(array.length()) { index ->
        array.get(index).let { require(it is String); it }
    }
    private fun strings(array: JSONArray) = List(array.length()) { array.getString(it) }
}
