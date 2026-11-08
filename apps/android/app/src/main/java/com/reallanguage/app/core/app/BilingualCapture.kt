package com.reallanguage.app.core.app

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.capture.domain.BilingualParagraph
import com.reallanguage.app.feature.capture.domain.BilingualSentence
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

internal class CaptureContentBlocked(val caseId: String) : IOException("这段内容需要先处理。")

enum class CaptureOrigin(val legacy: SourceType) {
    DIARY(SourceType.DIARY), AI_SCENE(SourceType.AI_SCENE), SCREENSHOT(SourceType.DIARY),
    RECORDING(SourceType.RECORDING), AUDIO_IMPORT(SourceType.RECORDING)
}

internal suspend fun generateCapturePassages(
    transport: HttpTransport, session: SessionRuntime, entryId: String,
    text: String, origin: CaptureOrigin, shape: SourceContentShape, back: HomeDiaryRoute,
): HomeDiaryRoute.Bilingual {
    require(text.isNotBlank()) { "没有可供学习的文字。" }
    if (session.accessToken == null) throw IOException("请先登录。")
    val commandId = UUID.nameUUIDFromBytes("$entryId\u0000${origin.name}\u0000${shape.name}\u0000$text".toByteArray()).toString()
    val body = JSONObject().put("text",text).put("sourceType",origin.name)
        .put("contentShape",shape.name).put("commandId",commandId)
    val endpoint = "/api/v1/content-formation/bilingual-passages"
    val pollPath = "$endpoint?commandId=$commandId"
    val initialOwner = session.accountId
    val epoch = session.accountEpoch
    fun sameSession() = session.accountId == initialOwner && session.accountEpoch == epoch
    fun headers() = mapOf("Authorization" to "Bearer ${session.accessToken ?: throw IOException("请先登录。")}",
        "Prefer" to "respond-async")
    suspend fun request(method: String) = transport.execute(HttpRequest(method,
        if (method == "POST") endpoint else pollPath, if (method == "POST") body.toString() else "", headers()))
    var method = "POST"
    var transientFailures = 0
    val response = try {
        var result: com.reallanguage.app.core.network.HttpResponse
        while (true) {
            if (!sameSession()) throw CancellationException("Account changed")
            try {
                result = request(method)
            } catch (failure: IOException) {
                if (++transientFailures > 3) {
                    throw IOException("连接中断。请恢复网络后，用原内容重试。", failure)
                }
                delay(1_000L * transientFailures)
                continue
            }
            if (method == "GET" && result.statusCode == 404) {
                // A backend restart or expired transient cache can replay completed supplier stages.
                method = "POST"
                delay(500)
                continue
            }
            if (result.statusCode in listOf(429, 502, 503, 504)) {
                if (++transientFailures > 3) break
                delay(1_000L * transientFailures)
                continue
            }
            if (result.statusCode != 202) break
            transientFailures = 0
            method = "GET"
            delay(1_500)
        }
        if (!sameSession()) throw CancellationException("Account changed")
        result
    } catch (cancelled: CancellationException) {
        // A killed process cannot send DELETE. The server expires unpolled work in that case.
        withContext(NonCancellable) {
            withTimeoutOrNull(1_500) {
                if (sameSession()) runCatching { request("DELETE") }
            }
        }
        throw cancelled
    }
    if (response.statusCode == 422) {
        val details = runCatching { JSONObject(response.body).optJSONObject("error") }.getOrNull()
        val caseId = details?.optString("safetyCaseId").orEmpty()
        if (caseId.isNotBlank()) throw CaptureContentBlocked(caseId)
    }
    if (response.statusCode != 200) {
        val message = when (response.statusCode) {
            401 -> "登录已过期，请重新登录。"
            402 -> "余额不足，请查看账户。"
            422 -> "这段内容暂时无法生成学习材料，请检查原文。"
            else -> "中英对照生成失败，请重试。"
        }
        throw IOException(message)
    }
    val array = JSONObject(response.body).getJSONArray("paragraphs")
    check(array.length() > 0) { "没有获取到中英对照内容。" }
    val paragraphs = List(array.length()) { index ->
        val paragraph = array.getJSONObject(index)
        val sentences = paragraph.getJSONArray("sentences")
        check(sentences.length() > 0) { "英文段落为空，请重试。" }
        BilingualParagraph(paragraph.getString("textZh"), paragraph.getString("textEn"),
            List(sentences.length()) { j ->
                val sentence = sentences.getJSONObject(j)
                BilingualSentence("p${index+1}-s${j+1}",sentence.getString("textEn"),sentence.getString("textZh"))
            })
    }
    return HomeDiaryRoute.Bilingual(text, origin, shape, commandId, paragraphs, back)
}

internal fun HomeDiaryRoute.Bilingual.learningTargets(): HomeDiaryRoute.LearningTargets {
    val chosen = paragraphs.flatMap { it.sentences }.filter { it.id in selected }
    require(chosen.size in 1..3 && chosen.size == selected.size)
    val units = chosen.mapIndexed { i,s -> SemanticUnit(s.id, i, s.textZh) }
    val sentences = chosen.map { s ->
        EnglishSentenceDraft(s.id,s.textZh,true,listOf(EnglishCandidate("${s.id}-en",s.textEn)))
    }
    val chapters = listOf(EnglishChapterDraft("capture-$commandId", sentences))
    return HomeDiaryRoute.LearningTargets(text, units, chapters, sentences.associate { it.unitId to it.candidates.single().candidateId },
        origin.legacy, shape, this, "bilingual-$commandId")
}
