package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.ChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishCandidate
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishGenerationRepository
import com.reallanguage.app.feature.formation.domain.EnglishGenerationResult
import com.reallanguage.app.feature.formation.domain.EnglishSentenceDraft
import com.reallanguage.app.feature.formation.domain.SemanticUnit
import com.reallanguage.app.feature.formation.domain.KnownKnowledgeCandidate
import com.reallanguage.app.feature.formation.domain.EnglishKnowledgeSnapshotSource
import com.reallanguage.app.feature.formation.domain.KnownKnowledgeSnapshotResult
import com.reallanguage.app.feature.formation.domain.isValidKnowledgeRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

interface EnglishGenerationWireCodec {
    fun request(units: List<SemanticUnit>, selectedUnitIds: List<String>, chapters: List<ChapterDraft>, commandId: String): String
    fun chapters(body: String): List<EnglishChapterDraft>
    fun requestWithKnowledge(units: List<SemanticUnit>, selectedUnitIds: List<String>, chapters: List<ChapterDraft>, commandId: String, knownKnowledge: List<KnownKnowledgeCandidate>, sourceType: String): String = request(units, selectedUnitIds, chapters, commandId)
}

class HttpEnglishGenerationRepository(
    private val transport: HttpTransport,
    private val runtime: SessionRuntime,
    private val commandId: String,
    private val codec: EnglishGenerationWireCodec = JsonEnglishGenerationWireCodec(),
    private val knowledgeSource: EnglishKnowledgeSnapshotSource? = null,
    private val sourceType: String = "DIARY",
) : EnglishGenerationRepository {
    private val mutex = Mutex()
    private val owner = runtime.accountId
    private val ownerEpoch = runtime.accountEpoch
    private var completed: Pair<String, EnglishGenerationResult.Generated>? = null
    override suspend fun generate(units: List<SemanticUnit>, selectedUnitIds: List<String>, chapters: List<ChapterDraft>): EnglishGenerationResult = mutex.withLock {
        val token = runtime.accessToken ?: return@withLock EnglishGenerationResult.Failed
        val revision = runtime.identityRevision.value
        fun active() = runtime.accountId == owner && runtime.accessToken != null && runtime.accountEpoch == ownerEpoch && runtime.identityRevision.value == revision
        if (!active() || sourceType !in setOf("DIARY", "RECORDING", "AI_SCENE")) return@withLock EnglishGenerationResult.Failed
        try {
            val baseBody = codec.requestWithKnowledge(units, selectedUnitIds, chapters, commandId, emptyList(), sourceType)
            val knowledge = when (val frozen = knowledgeSource?.freeze(commandId, knowledgeDigest(baseBody))) {
                is KnownKnowledgeSnapshotResult.Ready -> frozen.candidates
                KnownKnowledgeSnapshotResult.Failed -> return@withLock EnglishGenerationResult.Failed
                null -> emptyList()
            }
            currentCoroutineContext().ensureActive(); check(active())
            val frozenBody = codec.requestWithKnowledge(units, selectedUnitIds, chapters, commandId, knowledge, sourceType)
            val body = if (knowledgeSource == null && sourceType == "DIARY") codec.request(units, selectedUnitIds, chapters, commandId)
                else codec.requestWithKnowledge(units, selectedUnitIds, chapters, "english-known-v1:${knowledgeDigest(frozenBody)}", knowledge, sourceType)
            completed?.takeIf { it.first == body }?.let { return@withLock it.second }
            val response = transport.execute(HttpRequest(
                "POST", "/api/v1/content-formation/english-candidates",
                body,
                mapOf("Authorization" to "Bearer $token"),
            ))
            currentCoroutineContext().ensureActive(); check(active())
            if (response.statusCode != 200) return@withLock EnglishGenerationResult.Failed
            val generated = codec.chapters(response.body)
            val allowlist = knowledge.map { it.ref }.toSet()
            check(generated.flatMap { it.sentences }.flatMap { it.candidates }.all { candidate ->
                candidate.reusedKnowledgeRefs.all { it in allowlist } && candidate.reusedKnowledgeRefs.distinct().size == candidate.reusedKnowledgeRefs.size
            })
            EnglishGenerationResult.Generated(generated).also { completed = body to it }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { EnglishGenerationResult.Failed }
    }
}

class JsonEnglishGenerationWireCodec : EnglishGenerationWireCodec {
    override fun requestWithKnowledge(units: List<SemanticUnit>, selectedUnitIds: List<String>, chapters: List<ChapterDraft>, commandId: String, knownKnowledge: List<KnownKnowledgeCandidate>, sourceType: String): String =
        JSONObject(request(units, selectedUnitIds, chapters, commandId)).put("sourceType", sourceType)
            .put("knownKnowledge", JSONArray(knownKnowledge.map { JSONObject().put("ref", it.ref).put("type", it.type.name).put("display", it.display).put("meaningZh", it.meaningZh) })).toString()
    override fun request(units: List<SemanticUnit>, selectedUnitIds: List<String>, chapters: List<ChapterDraft>, commandId: String) = JSONObject()
        .put("sourceType", "DIARY")
        .put("units", JSONArray(units.sortedBy { it.order }.map { JSONObject().put("unitId", it.unitId).put("order", it.order).put("textZh", it.textZh) }))
        .put("chapters", JSONArray(chapters.map { JSONObject().put("chapterId", it.chapterId).put("unitIds", JSONArray(it.unitIds)) }))
        .put("selectedUnitIds", JSONArray(selectedUnitIds))
        .put("commandId", commandId).toString()

    override fun chapters(body: String): List<EnglishChapterDraft> {
        val array = JSONObject(body).getJSONArray("chapters")
        return List(array.length()) { chapterIndex ->
            val chapter = array.getJSONObject(chapterIndex)
            val sentences = chapter.getJSONArray("sentences")
            EnglishChapterDraft(
                chapterId = chapter.getString("chapterId"),
                sentences = List(sentences.length()) { sentenceIndex ->
                    val sentence = sentences.getJSONObject(sentenceIndex)
                    val candidates = sentence.getJSONArray("candidates")
                    EnglishSentenceDraft(
                        unitId = sentence.getString("sourceUnitId"),
                        textZh = sentence.getString("textZh"),
                        isFocus = sentence.getBoolean("isFocus"),
                        candidates = List(candidates.length()) { candidateIndex ->
                            val candidate = candidates.getJSONObject(candidateIndex)
                            EnglishCandidate(candidate.getString("candidateId"), candidate.getString("english"),
                                if (!candidate.has("reusedKnowledgeRefs")) emptyList() else decodeKnowledgeRefs(candidate.get("reusedKnowledgeRefs")))
                        }.also { check(it.size in 1..3) },
                    )
                },
            )
        }.also { check(it.isNotEmpty() && it.all { chapter -> chapter.sentences.isNotEmpty() }) }
    }
}

internal fun decodeKnowledgeRefs(value: Any): List<String> {
    val array = value as? JSONArray ?: error("Invalid reused knowledge references")
    return List(array.length()) { index -> (array.get(index) as? String)?.takeIf(::isValidKnowledgeRef) ?: error("Invalid reused knowledge reference") }
        .also { check(it.distinct().size == it.size && it.size <= 16) }
}
