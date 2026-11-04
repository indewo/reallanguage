package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import com.reallanguage.app.feature.formation.domain.RelatedVocabularyItem
import com.reallanguage.app.feature.formation.domain.hasValidRelatedVocabularyItems
import com.reallanguage.app.feature.formation.domain.validateRelatedVocabularyGroup
import org.json.JSONObject

/** Immutable current-sentence explanations, never foreign keys to optional learning targets. */
data class RelatedVocabularySnapshot(
    val sourceTextEn: String,
    val unitId: String,
    val items: List<RelatedVocabularyItem>,
    val refs: List<String>,
) {
    fun orderedItems(): List<RelatedVocabularyItem> {
        val byRef = items.associateBy { it.itemRef }
        return refs.map { byRef.getValue(it) }
    }
}

/** Null is genuine legacy absence, not a decoding fallback for damaged new evidence. */
fun encodeRelatedVocabularySnapshot(target: LearningTarget, textEn: String): String? {
    require(target.hasValidRelatedVocabularyItems(textEn))
    if (target.relatedVocabularyItems == null && target.relatedVocabularyItemRefs == null) return null
    require(textEn.isNotBlank() && target.unitId.isNotBlank())
    val group = JSONObject().also { encodeRelatedVocabularyGroup(target, it) }
    return JSONObject().put("version", 1).put("sourceTextEn", textEn).put("unitId", target.unitId)
        .put("items", group.get("relatedVocabularyItems"))
        .put("refs", group.get("relatedVocabularyItemRefs")).toString()
}

/** Callers must additionally bind this envelope to their own persisted account/sentence. */
fun decodeRelatedVocabularySnapshot(raw: String): RelatedVocabularySnapshot {
    val json = JSONObject(raw)
    require(json.keys().asSequence().toSet() == setOf("version", "sourceTextEn", "unitId", "items", "refs"))
    val version = json.get("version")
    require((version is Int || version is Long) && (version as Number).toLong() == 1L)
    fun text(key: String) = json.get(key).let { require(it is String && it.isNotBlank()); it }
    val source = text("sourceTextEn")
    val unit = text("unitId")
    val (rawItems, rawRefs) = decodeRelatedVocabularyGroup(JSONObject()
        .put("relatedVocabularyItems", json.get("items")).put("relatedVocabularyItemRefs", json.get("refs")))
    val items = requireNotNull(rawItems)
    val refs = requireNotNull(rawRefs)
    val byRef = items.associateBy { it.itemRef }
    val displays = refs.map { byRef.getValue(it).display }
    require(validateRelatedVocabularyGroup(unit, LearningTargetType.PATTERN, displays, items, refs, source))
    return RelatedVocabularySnapshot(source, unit, items, refs)
}
