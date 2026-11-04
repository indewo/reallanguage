package com.reallanguage.app.feature.formation.data

import android.database.sqlite.SQLiteDatabase
import com.reallanguage.app.feature.formation.domain.ContentFixRequest
import org.json.JSONArray
import java.util.UUID

/** The command is an identity, not permission to claim that changed content was already saved. */
internal fun SQLiteDatabase.matchesFixedSelection(account: String, request: ContentFixRequest, firstChapterId: String): Boolean {
    fun id(kind: String, raw: String) = UUID.nameUUIDFromBytes("${request.formationFlowId}:$kind:$raw".toByteArray(Charsets.UTF_8)).toString()
    if (firstChapterId != request.chapters.firstOrNull()?.let { id("chapter", it.chapterId) }) return false
    val actualChapters = rows("select chapter_id,chapter_order from chapters where account_id=? and flow_id=? order by chapter_order", account, request.formationFlowId)
    val expectedChapters = request.chapters.mapIndexed { index, chapter -> listOf(id("chapter", chapter.chapterId), index.toString()) }
    if (actualChapters != expectedChapters) return false
    val source = rows("select source_type,source_title from formation_flows where account_id=? and flow_id=?", account, request.formationFlowId)
    if (source != listOf(listOf(request.sourceType, request.sourceTitle))) return false
    for (chapter in request.chapters) {
        val stored = rows("select unit_id,text_zh,text_en,role,reused_knowledge_refs from fixed_sentences where account_id=? and chapter_id=? order by sentence_order",
            account, id("chapter", chapter.chapterId))
        if (stored.size != chapter.sentences.size) return false
        for ((actual, sentence) in stored.zip(chapter.sentences)) {
            val selected = sentence.candidates.singleOrNull { it.candidateId == request.selectedCandidateIds[sentence.unitId] } ?: return false
            if (actual.take(4) != listOf(id("sentence", sentence.unitId), sentence.textZh, selected.textEn, if (sentence.isFocus) "FOCUS" else "CONTEXT")) return false
            val refs = actual[4]?.let { text -> runCatching { decodeKnowledgeRefs(JSONArray(text)) }.getOrNull() } ?: return false
            if (refs != selected.reusedKnowledgeRefs) return false
        }
    }
    val actualTargets = rows("select t.target_id,t.chapter_id,t.unit_id,t.type,t.display,t.explanation_zh,t.structure_parts,t.related_vocabulary,t.learning_scopes,t.source_evidence,t.known_knowledge_ref,t.related_vocabulary_snapshot " +
        "from target_selections t join chapters c on c.account_id=t.account_id and c.chapter_id=t.chapter_id where t.account_id=? and c.flow_id=? order by t.target_id", account, request.formationFlowId)
    val expectedTargets = request.targets.filter { it.targetId in request.selectedTargetIds }.map { target ->
        val chapter = request.chapters.single { it.sentences.any { sentence -> sentence.unitId == target.unitId } }
        val sentence = chapter.sentences.single { it.unitId == target.unitId }
        val english = sentence.candidates.single { it.candidateId == request.selectedCandidateIds[target.unitId] }.textEn
        val persisted = target.copy(unitId = id("sentence", target.unitId), relatedVocabularyItems = target.relatedVocabularyItems?.map {
            it.copy(unitId = id("sentence", it.unitId))
        })
        listOf(id("target", target.targetId), id("chapter", chapter.chapterId), id("sentence", target.unitId), target.type.name,
            target.display, target.explanationZh, target.structureParts.joinToString("\u001f"), target.relatedVocabulary.joinToString("\u001f"),
            target.learningScopes.sorted().joinToString("\u001f"), target.sourceEvidence?.toJson()?.toString(), target.knownKnowledgeRef,
            encodeRelatedVocabularySnapshot(persisted, english))
    }.sortedBy { it.first() }
    if (actualTargets.size != expectedTargets.size) return false
    return actualTargets.zip(expectedTargets).all { (actual, expected) ->
        actual.dropLast(1) == expected.dropLast(1) && sameRelatedSnapshot(actual.last(), expected.last())
    }
}

private fun sameRelatedSnapshot(actual: String?, expected: String?): Boolean {
    if (actual == null || expected == null) return actual == null && expected == null
    return runCatching { decodeRelatedVocabularySnapshot(actual) == decodeRelatedVocabularySnapshot(expected) }.getOrDefault(false)
}

private fun SQLiteDatabase.rows(sql: String, vararg args: String): List<List<String?>> = rawQuery(sql, args).use { cursor ->
    buildList { while (cursor.moveToNext()) add(List(cursor.columnCount) { if (cursor.isNull(it)) null else cursor.getString(it) }) }
}
