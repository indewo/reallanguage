package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.safety.domain.*
import com.reallanguage.app.feature.scene.domain.*
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Strict, versioned domain JSON; encryption and account authorization are separate mandatory gates. */
class FormationRecoveryPayloadCodec {
    fun encode(snapshot: FormationRecoverySnapshot): ByteArray {
        require(snapshot.isValidForRecovery())
        val json = fields(
            "schemaVersion" to 1, "sourceEntryId" to snapshot.sourceEntryId, "sourceRevision" to snapshot.sourceRevision,
            "phase" to snapshot.phase.name, "source" to fields("text" to snapshot.source.text,
                "sourceType" to snapshot.source.sourceType.name, "contentShape" to snapshot.source.contentShape.name),
            "sourceTitle" to snapshot.sourceTitle, "origin" to snapshot.origin.toRecoveryJson(),
            "units" to JSONArray(snapshot.units.map { fields("unitId" to it.unitId, "order" to it.order,
                "textZh" to it.textZh, "recommendationReasonZh" to it.recommendationReasonZh) }),
            "selectedUnitIds" to JSONArray(snapshot.selectedUnitIds),
            "chapters" to JSONArray(snapshot.chapters.map { fields("chapterId" to it.chapterId, "unitIds" to JSONArray(it.unitIds)) }),
            "englishChapters" to JSONArray(snapshot.englishChapters.map { chapter -> fields("chapterId" to chapter.chapterId,
                "sentences" to JSONArray(chapter.sentences.map { sentence -> fields(
                    "unitId" to sentence.unitId, "textZh" to sentence.textZh, "isFocus" to sentence.isFocus,
                    "candidates" to JSONArray(sentence.candidates.map { fields("candidateId" to it.candidateId,
                        "textEn" to it.textEn, "reusedKnowledgeRefs" to JSONArray(it.reusedKnowledgeRefs)) })) })) }),
            "selectedCandidateIds" to JSONObject().apply { snapshot.selectedCandidateIds.toSortedMap().forEach { (id, candidate) -> put(id, candidate) } },
            "targets" to JSONArray(snapshot.targets.map { fields(
                "targetId" to it.targetId, "unitId" to it.unitId, "display" to it.display, "explanationZh" to it.explanationZh,
                "type" to it.type.name, "structureParts" to JSONArray(it.structureParts), "relatedVocabulary" to JSONArray(it.relatedVocabulary),
                "learningScopes" to JSONArray(it.learningScopes.sorted()), "sourceEvidence" to it.sourceEvidence?.toJson(),
                "knownKnowledgeRef" to it.knownKnowledgeRef).apply {
                    // Do not needlessly make legacy no-example drafts unreadable by an older strict decoder.
                    it.candidateExample?.let { example -> put("candidateExample", example.toCandidateExampleJson()) }
                    encodeRelatedVocabularyGroup(it, this)
                } }),
            "queryableExpressions" to JSONArray(snapshot.queryableExpressions.map {
                fields("expressionId" to it.expressionId, "unitId" to it.unitId, "display" to it.display).apply {
                    it.explanationZh?.let { explanation -> put("explanationZh", explanation) }
                }
            }),
            "selectedTargetIds" to JSONArray(snapshot.selectedTargetIds.sorted()),
            "targetSelectionEntryId" to snapshot.targetSelectionEntryId, "fixEntryId" to snapshot.fixEntryId,
            "targetSentenceIndex" to snapshot.targetSentenceIndex,
        ).toString()
        val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(json))
        return ByteArray(bytes.remaining()).also { bytes.get(it) }
    }

    fun decode(payload: ByteArray): FormationRecoverySnapshot? = try {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload)).toString()
        val tokener = JSONTokener(text)
        val root = tokener.nextValue().objectValue().checked(setOf("schemaVersion", "sourceEntryId", "sourceRevision", "phase",
            "source", "sourceTitle", "origin", "units", "selectedUnitIds", "chapters", "englishChapters", "selectedCandidateIds",
            "targets", "queryableExpressions", "selectedTargetIds", "targetSelectionEntryId", "fixEntryId", "targetSentenceIndex"))
        require(tokener.nextClean() == '\u0000' && root.integer("schemaVersion") == 1)
        val source = root.obj("source").checked(setOf("text", "sourceType", "contentShape"))
        FormationRecoverySnapshot(
            sourceEntryId = root.string("sourceEntryId"), sourceRevision = root.longInteger("sourceRevision"), phase = root.enum("phase"),
            source = ConfirmedSource(source.string("text"), source.enum("sourceType"), source.enum("contentShape")),
            sourceTitle = root.string("sourceTitle"), origin = root.obj("origin").recoveryOrigin(),
            units = root.objects("units") { unit -> unit.checked(setOf("unitId", "order", "textZh", "recommendationReasonZh"))
                SemanticUnit(unit.string("unitId"), unit.integer("order"), unit.string("textZh"), unit.nullableString("recommendationReasonZh")) },
            selectedUnitIds = root.strings("selectedUnitIds"),
            chapters = root.objects("chapters") { chapter -> chapter.checked(setOf("chapterId", "unitIds"))
                ChapterDraft(chapter.string("chapterId"), chapter.strings("unitIds")) },
            englishChapters = root.objects("englishChapters") { chapter -> chapter.checked(setOf("chapterId", "sentences"))
                EnglishChapterDraft(chapter.string("chapterId"), chapter.objects("sentences") { sentence ->
                    sentence.checked(setOf("unitId", "textZh", "isFocus", "candidates"))
                    EnglishSentenceDraft(sentence.string("unitId"), sentence.string("textZh"), sentence.boolean("isFocus"),
                        sentence.objects("candidates") { candidate ->
                            candidate.checked(setOf("candidateId", "textEn"), setOf("reusedKnowledgeRefs"))
                            EnglishCandidate(candidate.string("candidateId"), candidate.string("textEn"),
                                if (!candidate.has("reusedKnowledgeRefs")) emptyList() else decodeKnowledgeRefs(candidate.get("reusedKnowledgeRefs")))
                        })
                }) },
            selectedCandidateIds = root.obj("selectedCandidateIds").let { map -> map.keys().asSequence().associateWith { map.string(it) } },
            targets = root.objects("targets") { target ->
                target.checked(setOf("targetId", "unitId", "display", "explanationZh", "type", "structureParts", "relatedVocabulary", "learningScopes", "sourceEvidence"), setOf("knownKnowledgeRef", "candidateExample", "relatedVocabularyItems", "relatedVocabularyItemRefs"))
                val related = decodeRelatedVocabularyGroup(target)
                LearningTarget(target.string("targetId"), target.string("unitId"), target.string("display"), target.string("explanationZh"),
                    target.enum("type"), target.strings("structureParts"), target.strings("relatedVocabulary"), target.stringSet("learningScopes"),
                    decodeSourceEvidence(target.get("sourceEvidence")),
                    if (target.has("knownKnowledgeRef")) target.nullableString("knownKnowledgeRef")?.also { require(isValidKnowledgeRef(it)) } else null,
                    candidateExample = decodeCandidateExample(target.opt("candidateExample"), normalize = false),
                    relatedVocabularyItems = related.first, relatedVocabularyItemRefs = related.second)
            },
            queryableExpressions = root.objects("queryableExpressions") { expression ->
                expression.checked(setOf("expressionId", "unitId", "display"), setOf("explanationZh"))
                QueryableExpression(
                    expression.string("expressionId"),
                    expression.string("unitId"),
                    expression.string("display"),
                    if (expression.has("explanationZh")) expression.nullableString("explanationZh") else null,
                )
            },
            selectedTargetIds = root.stringSet("selectedTargetIds"), targetSelectionEntryId = root.nullableString("targetSelectionEntryId"),
            fixEntryId = root.nullableString("fixEntryId"), targetSentenceIndex = root.integer("targetSentenceIndex"),
        ).also { require(it.isValidForRecovery()) }
    } catch (_: Exception) { null }
}

private fun fields(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }
private fun Any.objectValue(): JSONObject = this as? JSONObject ?: error("Invalid recovery object")
private fun JSONObject.checked(required: Set<String>, optional: Set<String> = emptySet()): JSONObject = apply {
    val actual = keys().asSequence().toSet()
    require(actual.containsAll(required) && actual.all { it in required || it in optional })
}
private fun JSONObject.obj(key: String) = get(key).objectValue()
private fun JSONObject.string(key: String) = get(key).let { require(it is String); it }
private fun JSONObject.nullableString(key: String): String? = get(key).let { if (it === JSONObject.NULL) null else { require(it is String); it } }
private fun JSONObject.boolean(key: String) = get(key).let { require(it is Boolean); it }
private fun JSONObject.longInteger(key: String): Long = get(key).let { require(it is Int || it is Long); (it as Number).toLong() }
private fun JSONObject.integer(key: String): Int = longInteger(key).also { require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) }.toInt()
private inline fun <reified T : Enum<T>> JSONObject.enum(key: String): T = enumValueOf(string(key))
private fun JSONObject.array(key: String) = get(key).let { require(it is JSONArray); it }
private fun JSONObject.strings(key: String): List<String> = array(key).let { array -> List(array.length()) { index ->
    array.get(index).let { require(it is String); it }
} }
private fun JSONObject.stringSet(key: String): Set<String> = strings(key).let { values -> require(values.distinct().size == values.size); values.toSet() }
private fun <T> JSONObject.objects(key: String, read: (JSONObject) -> T): List<T> = array(key).let { array ->
    List(array.length()) { read(array.get(it).objectValue()) }
}

private fun FormationSourceOrigin.toRecoveryJson(): JSONObject = when (this) {
    FormationSourceOrigin.Diary -> fields("kind" to "DIARY")
    is FormationSourceOrigin.Recording -> fields("kind" to "RECORDING", "recordingId" to recordingId, "transcriptId" to transcriptId)
    is FormationSourceOrigin.Unavailable -> fields("kind" to "UNAVAILABLE", "reviewRequestId" to reviewRequestId)
    is FormationSourceOrigin.Scene -> fields("kind" to "SCENE", "session" to session.toRecoveryJson(), "draft" to draft.toRecoveryJson())
}
private fun JSONObject.recoveryOrigin(): FormationSourceOrigin = when (string("kind")) {
    "DIARY" -> { checked(setOf("kind")); FormationSourceOrigin.Diary }
    "RECORDING" -> { checked(setOf("kind", "recordingId", "transcriptId")); FormationSourceOrigin.Recording(string("recordingId"), string("transcriptId")) }
    "UNAVAILABLE" -> { checked(setOf("kind", "reviewRequestId")); FormationSourceOrigin.Unavailable(nullableString("reviewRequestId")) }
    "SCENE" -> { checked(setOf("kind", "session", "draft")); FormationSourceOrigin.Scene(obj("session").recoverySession(), obj("draft").recoveryDraft()) }
    else -> error("Unsupported recovery origin")
}

private fun SceneQuestionSession.toRecoveryJson() = fields("start" to start.toRecoveryJson(), "contentType" to contentType.name,
    "answers" to JSONArray(answers.map { answer -> fields("questionId" to answer.questionId, "answerZh" to answer.answerZh).apply {
        // Real scene answers retain the question used to collect this fact. Omitting it
        // changes the snapshot on read-back and rejects every production scene save.
        answer.questionZh?.let { put("questionZh", it) }
    } }),
    "collectedFacts" to JSONArray(collectedFacts), "missingNonBlockingFacts" to JSONArray(missingNonBlockingFacts), "operationId" to operationId)
private fun JSONObject.recoverySession(): SceneQuestionSession {
    checked(setOf("start", "contentType", "answers", "collectedFacts", "missingNonBlockingFacts", "operationId"))
    return SceneQuestionSession(obj("start").recoveryStart(), enum("contentType"), objects("answers") { answer ->
        answer.checked(setOf("questionId", "answerZh"), setOf("questionZh"))
        SceneAnswer(answer.string("questionId"), answer.string("answerZh"),
            if (answer.has("questionZh")) answer.nullableString("questionZh") else null)
    }, strings("collectedFacts"), strings("missingNonBlockingFacts"), string("operationId"))
}
private fun SceneGeneratedDraft.toRecoveryJson() = fields("draftId" to draftId, "contentType" to contentType.name,
    "units" to JSONArray(units.map { fields("unitId" to it.unitId, "speaker" to it.speaker?.name, "textZh" to it.textZh, "factReferences" to JSONArray(it.factReferences)) }))
private fun JSONObject.recoveryDraft(): SceneGeneratedDraft {
    checked(setOf("draftId", "contentType", "units"))
    return SceneGeneratedDraft(string("draftId"), enum("contentType"), objects("units") { unit ->
        unit.checked(setOf("unitId", "speaker", "textZh", "factReferences"))
        SceneDraftUnit(unit.string("unitId"), unit.nullableString("speaker")?.let { enumValueOf<SceneSpeaker>(it) }, unit.string("textZh"), unit.strings("factReferences"))
    })
}
private fun SceneStart.toRecoveryJson(): JSONObject = when (this) {
    is SceneStart.DirectDescription -> fields("kind" to "DIRECT_DESCRIPTION", "description" to description)
    is SceneStart.Template -> fields("kind" to "TEMPLATE", "template" to fields("templateId" to template.templateId, "titleZh" to template.titleZh, "scopeZh" to template.scopeZh))
    is SceneStart.Recommendation -> fields("kind" to "RECOMMENDATION", "recommendation" to fields("recommendationId" to recommendation.recommendationId,
        "shortTopicZh" to recommendation.shortTopicZh, "whyRelevantZh" to recommendation.whyRelevantZh, "sourceRefs" to JSONArray(recommendation.sourceRefs)))
}
private fun JSONObject.recoveryStart(): SceneStart = when (string("kind")) {
    "DIRECT_DESCRIPTION" -> { checked(setOf("kind", "description")); SceneStart.DirectDescription(string("description")) }
    "TEMPLATE" -> {
        checked(setOf("kind", "template"))
        val template = obj("template").checked(setOf("templateId", "titleZh", "scopeZh"))
        SceneStart.Template(PublicSceneTemplate(template.string("templateId"), template.string("titleZh"), template.string("scopeZh")))
    }
    "RECOMMENDATION" -> {
        checked(setOf("kind", "recommendation"))
        val recommendation = obj("recommendation").checked(setOf("recommendationId", "shortTopicZh", "whyRelevantZh", "sourceRefs"))
        SceneStart.Recommendation(RecentSceneRecommendation(recommendation.string("recommendationId"), recommendation.string("shortTopicZh"),
            recommendation.string("whyRelevantZh"), recommendation.strings("sourceRefs")))
    }
    else -> error("Unsupported scene start")
}
