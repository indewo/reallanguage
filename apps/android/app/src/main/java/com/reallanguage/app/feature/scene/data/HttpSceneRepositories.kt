package com.reallanguage.app.feature.scene.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.scene.domain.RecentSceneRecommendation
import com.reallanguage.app.feature.scene.domain.SceneAbandonRepository
import com.reallanguage.app.feature.scene.domain.SceneAbandonResult
import com.reallanguage.app.feature.scene.domain.SceneAnswer
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneDraftUnit
import com.reallanguage.app.feature.scene.domain.SceneEditIssue
import com.reallanguage.app.feature.scene.domain.SceneEditIssueReason
import com.reallanguage.app.feature.scene.domain.SceneEditReviewRepository
import com.reallanguage.app.feature.scene.domain.SceneEditReviewRequest
import com.reallanguage.app.feature.scene.domain.SceneEditReviewResult
import com.reallanguage.app.feature.scene.domain.SceneGeneratedDraft
import com.reallanguage.app.feature.scene.domain.SceneGenerationRepository
import com.reallanguage.app.feature.scene.domain.SceneGenerationRequest
import com.reallanguage.app.feature.scene.domain.SceneGenerationResult
import com.reallanguage.app.feature.scene.domain.SceneHomeRepository
import com.reallanguage.app.feature.scene.domain.SceneNextQuestionRepository
import com.reallanguage.app.feature.scene.domain.SceneQuestion
import com.reallanguage.app.feature.scene.domain.SceneQuestionRequest
import com.reallanguage.app.feature.scene.domain.SceneQuestionResult
import com.reallanguage.app.feature.scene.domain.SceneQuestionSession
import com.reallanguage.app.feature.scene.domain.SceneRecommendationResult
import com.reallanguage.app.feature.scene.domain.SceneSpeaker
import com.reallanguage.app.feature.scene.domain.SceneStart
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class HttpSceneHomeRepository(
    context: Context,
    private val transport: HttpTransport,
    private val runtime: SessionRuntime,
) : SceneHomeRepository {
    private val database = FormationDatabase(context.applicationContext)

    override suspend fun loadRecommendations(): SceneRecommendationResult = withContext(Dispatchers.IO) {
        val token = runtime.accessToken ?: return@withContext SceneRecommendationResult.Unavailable
        val accountId = runtime.accountId ?: return@withContext SceneRecommendationResult.Unavailable
        val epoch = runtime.accountEpoch
        try {
            val cached = readCached(accountId) ?: SceneRecommendationResult.Unavailable
            if (ownerIsActive(accountId, token, epoch)) cached else SceneRecommendationResult.Unavailable
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { SceneRecommendationResult.Unavailable }
    }

    override suspend fun refreshRecommendationsIfNeeded(): SceneRecommendationResult? = withContext(Dispatchers.IO) {
        val token = runtime.accessToken ?: return@withContext null
        val accountId = runtime.accountId ?: return@withContext null
        val epoch = runtime.accountEpoch
        try { refresh(accountId, token, epoch) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    private suspend fun refresh(
        accountId: String,
        token: String,
        epoch: Long,
    ): SceneRecommendationResult? {
        val revision = readSourceRevision(accountId)
        val cachedSignature = database.readableDatabase.rawQuery(
            "select source_signature from scene_recommendation_cache_state where account_id=?",
            arrayOf(accountId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (cachedSignature == revision.signature) return null
        if (revision.sourceCount == 0L) {
            if (!ownerIsActive(accountId, token, epoch)) return SceneRecommendationResult.Unavailable
            writeCache(accountId, revision.signature, emptyList(), emptyMap())
            return SceneRecommendationResult.Ready(emptyList())
        }
        // The normal page-open path stops above. Full fixed text is read only after the
        // append/delete revision changed, never merely because the page opened again.
        val snapshot = readSources(accountId)
        val body = JSONObject()
            .put("sources", JSONArray(snapshot.sources))
            .put("commandId", UUID.randomUUID().toString())
            .toString()
        val response = transport.execute(authorized("POST", "/api/v1/sources/scenes/recommendations", body, token))
        currentCoroutineContext().ensureActive()
        if (!ownerIsActive(accountId, token, epoch) || response.statusCode != 200) {
            return if (cachedSignature == null) SceneRecommendationResult.Unavailable else null
        }
        val values = JSONObject(response.body).getJSONArray("recommendations")
        val recommendations = List(values.length()) { index -> values.getJSONObject(index).let {
            RecentSceneRecommendation(
                it.getString("recommendationId"), it.getString("shortTopicZh"),
                it.getString("whyRelevantZh"), it.getJSONArray("sourceRefs").strings(),
            )
        } }.withIndex().sortedWith(
            compareByDescending<IndexedValue<RecentSceneRecommendation>> { indexed ->
                indexed.value.sourceRefs.mapNotNull(snapshot.completedDates::get).maxOrNull().orEmpty()
            }.thenBy { it.index },
        ).map { it.value }.take(4)
        if (!ownerIsActive(accountId, token, epoch)) return SceneRecommendationResult.Unavailable
        writeCache(accountId, revision.signature, recommendations, snapshot.completedDates)
        return SceneRecommendationResult.Ready(recommendations)
    }

    private fun readSourceRevision(accountId: String): RecommendationSourceRevision {
        val material = database.readableDatabase.rawQuery(
            """select count(distinct f.flow_id),coalesce(max(a.rowid),0),
               coalesce(max(a.completed_local_date),'') from formation_flows f
               join chapters c on c.account_id=f.account_id and c.flow_id=f.flow_id
               join chapter_archives a on a.account_id=c.account_id and a.chapter_id=c.chapter_id
               where f.account_id=? and a.completed_local_date>=date('now','-90 day')""",
            arrayOf(accountId),
        ).use { cursor ->
            check(cursor.moveToFirst())
            Triple(cursor.getLong(0), cursor.getLong(1), cursor.getString(2))
        }
        val signature = MessageDigest.getInstance("SHA-256")
            .digest("${material.first}\u001f${material.second}\u001f${material.third}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return RecommendationSourceRevision(material.first, signature)
    }

    private fun readSources(accountId: String): RecommendationSources {
        val sourceRows = database.readableDatabase.rawQuery(
                """select f.flow_id,f.source_type,f.source_title,a.completed_local_date,
                   group_concat(s.text_zh,'\n') from formation_flows f
                   join chapters c on c.account_id=f.account_id and c.flow_id=f.flow_id
                   join chapter_archives a on a.account_id=c.account_id and a.chapter_id=c.chapter_id
                   join fixed_sentences s on s.account_id=c.account_id and s.chapter_id=c.chapter_id
                   where f.account_id=? and a.completed_local_date>=date('now','-90 day')
                   group by f.flow_id,f.source_type,f.source_title,a.completed_local_date
                   order by a.completed_local_date desc,f.flow_id desc limit 12""",
                arrayOf(accountId),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(RecommendationSource(
                        id = cursor.getString(0),
                        completedLocalDate = cursor.getString(3),
                        json = JSONObject()
                            .put("sourceId", cursor.getString(0))
                            .put("sourceType", cursor.getString(1))
                            .put("completedLocalDate", cursor.getString(3))
                            .put("topicSummaryZh", cursor.getString(2))
                            .put("confirmedTextZh", cursor.getString(4))
                            .put("sceneContextZh", cursor.getString(2)),
                    ))
                }
            }
        return RecommendationSources(
            sources = sourceRows.map(RecommendationSource::json),
            completedDates = sourceRows.associate { it.id to it.completedLocalDate },
        )
    }

    private fun readCached(accountId: String): SceneRecommendationResult.Ready? {
        val initialized = database.readableDatabase.rawQuery(
            "select 1 from scene_recommendation_cache_state where account_id=?",
            arrayOf(accountId),
        ).use { it.moveToFirst() }
        if (!initialized) return null
        val recommendations = database.readableDatabase.rawQuery(
            """select recommendation_id,short_topic_zh,why_relevant_zh,source_refs_json
               from scene_recommendation_cache_items where account_id=? order by display_order""",
            arrayOf(accountId),
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) add(RecentSceneRecommendation(
                cursor.getString(0), cursor.getString(1), cursor.getString(2),
                JSONArray(cursor.getString(3)).strings(),
            ))
        } }
        return SceneRecommendationResult.Ready(recommendations)
    }

    private fun writeCache(
        accountId: String,
        signature: String,
        recommendations: List<RecentSceneRecommendation>,
        completedDates: Map<String, String>,
    ) {
        val writable = database.writableDatabase
        writable.beginTransaction()
        try {
            writable.delete("scene_recommendation_cache_items", "account_id=?", arrayOf(accountId))
            recommendations.take(4).forEachIndexed { index, recommendation ->
                writable.insertOrThrow("scene_recommendation_cache_items", null, ContentValues().apply {
                    put("account_id", accountId)
                    put("display_order", index)
                    put("recommendation_id", recommendation.recommendationId)
                    put("short_topic_zh", recommendation.shortTopicZh)
                    put("why_relevant_zh", recommendation.whyRelevantZh)
                    put("source_refs_json", JSONArray(recommendation.sourceRefs).toString())
                    put("source_completed_local_date", recommendation.sourceRefs.mapNotNull(completedDates::get).maxOrNull().orEmpty())
                })
            }
            writable.insertWithOnConflict("scene_recommendation_cache_state", null, ContentValues().apply {
                put("account_id", accountId)
                put("source_signature", signature)
                put("refreshed_at_epoch_ms", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            writable.setTransactionSuccessful()
        } finally { writable.endTransaction() }
    }

    private fun ownerIsActive(accountId: String, token: String, epoch: Long) =
        runtime.accountId == accountId && runtime.accessToken == token && runtime.accountEpoch == epoch

    private data class RecommendationSource(val id: String, val completedLocalDate: String, val json: JSONObject)
    private data class RecommendationSourceRevision(val sourceCount: Long, val signature: String)
    private data class RecommendationSources(
        val sources: List<JSONObject>,
        val completedDates: Map<String, String>,
    )
}

class HttpSceneFormationRepositories(
    private val transport: HttpTransport,
    private val runtime: SessionRuntime,
) : SceneNextQuestionRepository, SceneGenerationRepository, SceneEditReviewRepository {
    private val questionOwnerAccountId = runtime.accountId
    private val questionOwnerEpoch = runtime.accountEpoch
    private fun questionOwnerIsActive() = questionOwnerAccountId != null && runtime.accountId == questionOwnerAccountId &&
        runtime.accountEpoch == questionOwnerEpoch && runtime.accessToken != null

    override suspend fun next(request: SceneQuestionRequest): SceneQuestionResult {
        currentCoroutineContext().ensureActive()
        val token = runtime.accessToken ?: return SceneQuestionResult.Failed
        if (!questionOwnerIsActive()) return SceneQuestionResult.Failed
        return try {
            val input = sceneQuestionInput(request)
            val body = JSONObject(input)
                .put("commandId", sceneQuestionCommandId(input))
                .toString()
            currentCoroutineContext().ensureActive()
            if (!questionOwnerIsActive()) return SceneQuestionResult.Failed
            val response = transport.execute(authorized("POST", "/api/v1/sources/scenes/next-question", body, token))
            currentCoroutineContext().ensureActive()
            if (!questionOwnerIsActive() || response.statusCode != 200) return SceneQuestionResult.Failed
            val value = JSONObject(response.body)
            val result = when (value.getString("action")) {
                "ASK" -> SceneQuestionResult.Ask(SceneQuestion(value.getString("questionId"), value.getString("questionZh"), value.getJSONArray("options").strings()))
                "GENERATE" -> SceneQuestionResult.Generate(value.getJSONArray("collectedFacts").strings(), value.getJSONArray("missingNonBlockingFacts").strings())
                "INSUFFICIENT_INFORMATION" -> SceneQuestionResult.InsufficientInformation
                else -> SceneQuestionResult.Failed
            }
            currentCoroutineContext().ensureActive()
            if (questionOwnerIsActive()) result else SceneQuestionResult.Failed
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { SceneQuestionResult.Failed }
    }

    override suspend fun generate(request: SceneGenerationRequest, commandId: String): SceneGenerationResult {
        val token = runtime.accessToken ?: return SceneGenerationResult.Failed
        return runCatching {
            val body = JSONObject().put("task", request.task.taskId)
                .put("session", request.session.json())
                .put("remainingContentBudget", request.remainingContentBudget)
                .put("commandId", commandId)
                .apply { if (request.previousUnits.isNotEmpty()) put("previousUnits", JSONArray(request.previousUnits.map(SceneDraftUnit::json))) }
                .toString()
            val response = transport.execute(authorized("POST", "/api/v1/sources/scenes/drafts", body, token))
            if (response.statusCode != 200) return@runCatching SceneGenerationResult.Failed
            val value = JSONObject(response.body)
            val type = SceneContentType.valueOf(value.getString("contentType"))
            val units = value.getJSONArray("units").let { values -> List(values.length()) { index -> values.getJSONObject(index).unit() } }
            SceneGenerationResult.Generated(SceneGeneratedDraft(value.getString("draftId"), type, units))
        }.getOrDefault(SceneGenerationResult.Failed)
    }

    override suspend fun review(request: SceneEditReviewRequest, commandId: String): SceneEditReviewResult {
        val token = runtime.accessToken ?: return SceneEditReviewResult.Failed
        return runCatching {
            val body = JSONObject().put("contentType", request.contentType.name)
                .put("changedUnitIds", JSONArray(request.changedUnitIds.toList()))
                .put("originalUnits", JSONArray(request.originalDraft.units.map(SceneDraftUnit::json)))
                .put("editedUnits", JSONArray(request.editedUnits.map(SceneDraftUnit::json)))
                .put("commandId", commandId).toString()
            val response = transport.execute(authorized("POST", "/api/v1/sources/scenes/edit-reviews", body, token))
            if (response.statusCode != 200) return@runCatching SceneEditReviewResult.Failed
            val value = JSONObject(response.body)
            if (value.getString("decision") == "PASS") SceneEditReviewResult.Pass else {
                val issues = value.getJSONArray("issues").let { values -> List(values.length()) { index -> values.getJSONObject(index).let {
                    SceneEditIssue(it.getString("unitId"), it.getInt("startOffset"), it.getInt("endOffset"), SceneEditIssueReason.valueOf(it.getString("reason")), it.getString("explanationZh"))
                } } }
                if (issues.isEmpty()) SceneEditReviewResult.Failed else SceneEditReviewResult.NeedsUserEdit(issues)
            }
        }.getOrDefault(SceneEditReviewResult.Failed)
    }
}

class LocalSceneAbandonRepository : SceneAbandonRepository {
    override suspend fun abandon(session: SceneQuestionSession, commandId: String): SceneAbandonResult =
        if (session.operationId.isNotBlank() && commandId.isNotBlank()) SceneAbandonResult.Abandoned else SceneAbandonResult.Failed
}

private fun authorized(method: String, path: String, body: String, token: String) =
    HttpRequest(method, path, body, mapOf("Authorization" to "Bearer $token"))
private fun SceneStart.json(): JSONObject = when (this) {
    is SceneStart.Recommendation -> JSONObject().put("kind", "RECOMMENDATION").put("recommendationId", recommendation.recommendationId).put("shortTopicZh", recommendation.shortTopicZh).put("whyRelevantZh", recommendation.whyRelevantZh).put("sourceRefs", JSONArray(recommendation.sourceRefs))
    is SceneStart.Template -> JSONObject().put("kind", "TEMPLATE").put("templateId", template.templateId).put("titleZh", template.titleZh).put("scopeZh", template.scopeZh)
    is SceneStart.DirectDescription -> JSONObject().put("kind", "DIRECT_DESCRIPTION").put("description", description)
}
private fun SceneAnswer.json() = JSONObject()
    .put("questionId", questionId)
    .apply { questionZh?.let { put("questionZh", it) } }
    .put("answerZh", answerZh)
private fun SceneQuestionSession.json() = JSONObject().put("operationId", operationId).put("start", start.json()).put("contentType", contentType.name).put("answers", JSONArray(answers.map(SceneAnswer::json))).put("collectedFacts", JSONArray(collectedFacts)).put("missingNonBlockingFacts", JSONArray(missingNonBlockingFacts))
private fun SceneDraftUnit.json() = JSONObject().put("unitId", unitId).put("speaker", speaker?.name ?: JSONObject.NULL).put("textZh", textZh).put("factReferences", JSONArray(factReferences))
private fun JSONObject.unit() = SceneDraftUnit(getString("unitId"), if (isNull("speaker")) null else SceneSpeaker.valueOf(getString("speaker")), getString("textZh"), getJSONArray("factReferences").strings())
private fun JSONArray.strings() = List(length()) { getString(it) }

internal fun sceneQuestionCommandId(request: SceneQuestionRequest): String =
    sceneQuestionCommandId(sceneQuestionInput(request))

/** One frozen input supplies both the wire body and its identity; edits never reuse an older body's key. */
private fun sceneQuestionInput(request: SceneQuestionRequest): Map<String, Any> = linkedMapOf(
    "operationId" to request.operationId,
    "start" to when (val start = request.start) {
        is SceneStart.DirectDescription -> linkedMapOf("kind" to "DIRECT_DESCRIPTION", "description" to start.description)
        is SceneStart.Template -> linkedMapOf("kind" to "TEMPLATE", "templateId" to start.template.templateId,
            "titleZh" to start.template.titleZh, "scopeZh" to start.template.scopeZh)
        is SceneStart.Recommendation -> linkedMapOf("kind" to "RECOMMENDATION",
            "recommendationId" to start.recommendation.recommendationId, "shortTopicZh" to start.recommendation.shortTopicZh,
            "whyRelevantZh" to start.recommendation.whyRelevantZh, "sourceRefs" to start.recommendation.sourceRefs.toList())
    },
    "contentType" to request.contentType.name,
    "confirmedAnswers" to request.confirmedAnswers.map {
        linkedMapOf<String, Any>("questionId" to it.questionId, "answerZh" to it.answerZh).apply {
            it.questionZh?.let { question -> put("questionZh", question) }
        }
    },
    "remainingQuestions" to request.remainingQuestions,
    "remainingContentBudget" to request.remainingContentBudget,
    "stepIndex" to request.stepIndex,
)

private fun sceneQuestionCommandId(input: Map<String, Any>): String {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output ->
        // Typed, length-delimited UTF-8 retains exact text and list order, independent of JSON key ordering.
        fun write(value: Any) {
            when (value) {
                is String -> {
                    output.writeByte(1)
                    val text = value.toByteArray(Charsets.UTF_8)
                    output.writeInt(text.size); output.write(text)
                }
                is Int -> { output.writeByte(2); output.writeInt(value) }
                is List<*> -> {
                    output.writeByte(3); output.writeInt(value.size)
                    value.forEach { write(requireNotNull(it)) }
                }
                is Map<*, *> -> {
                    output.writeByte(4); output.writeInt(value.size)
                    value.keys.map { it as String }.sorted().forEach { key -> write(key); write(requireNotNull(value[key])) }
                }
                else -> error("Unsupported scene question identity value")
            }
        }
        write(input)
    }
    return "scene-question-v2:" + MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
}
