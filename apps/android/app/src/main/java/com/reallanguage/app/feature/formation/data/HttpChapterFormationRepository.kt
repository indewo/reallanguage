package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.ChapterDraft
import com.reallanguage.app.feature.formation.domain.ChapterFormationRepository
import com.reallanguage.app.feature.formation.domain.ChapterFormationResult
import com.reallanguage.app.feature.formation.domain.SemanticUnit
import org.json.JSONArray
import org.json.JSONObject

interface ChapterFormationWireCodec {
    fun request(units: List<SemanticUnit>, selectedUnitIds: List<String>, commandId: String): String
    fun chapters(body: String): List<ChapterDraft>
}

class HttpChapterFormationRepository(
    private val transport: HttpTransport,
    private val runtime: SessionRuntime,
    private val commandId: String,
    private val codec: ChapterFormationWireCodec = JsonChapterFormationWireCodec(),
) : ChapterFormationRepository {
    override suspend fun formChapters(units: List<SemanticUnit>, selectedUnitIds: List<String>): ChapterFormationResult {
        val token = runtime.accessToken ?: return ChapterFormationResult.Failed
        return runCatching {
            val response = transport.execute(HttpRequest("POST", "/api/v1/content-formation/chapters/context-selection", codec.request(units, selectedUnitIds, commandId), mapOf("Authorization" to "Bearer $token")))
            if (response.statusCode == 200) ChapterFormationResult.Formed(codec.chapters(response.body)) else ChapterFormationResult.Failed
        }.getOrDefault(ChapterFormationResult.Failed)
    }
}

class JsonChapterFormationWireCodec : ChapterFormationWireCodec {
    override fun request(units: List<SemanticUnit>, selectedUnitIds: List<String>, commandId: String) = JSONObject()
        .put("units", JSONArray(units.sortedBy { it.order }.map { JSONObject().put("unitId", it.unitId).put("order", it.order).put("textZh", it.textZh) }))
        .put("selectedUnitIds", JSONArray(selectedUnitIds))
        .put("commandId", commandId).toString()
    override fun chapters(body: String): List<ChapterDraft> {
        val array = JSONObject(body).getJSONArray("chapters")
        return List(array.length()) { index -> array.getJSONObject(index).let { chapter ->
            val ids = chapter.getJSONArray("unitIds")
            ChapterDraft(chapter.getString("chapterId"), List(ids.length()) { ids.getString(it) })
        } }.also { chapters -> check(chapters.isNotEmpty() && chapters.all { it.unitIds.size in 1..4 }) }
    }
}
