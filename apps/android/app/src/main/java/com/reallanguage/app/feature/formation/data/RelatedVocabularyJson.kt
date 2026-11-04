package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.feature.formation.domain.*
import org.json.JSONArray
import org.json.JSONObject

/** Missing and explicit null are legacy only when BOTH fields have that same presence. */
fun decodeRelatedVocabularyGroup(target: JSONObject): Pair<List<RelatedVocabularyItem>?, List<String>?> {
    val hasItems = target.has("relatedVocabularyItems")
    val hasRefs = target.has("relatedVocabularyItemRefs")
    if (!hasItems && !hasRefs) return null to null
    require(hasItems && hasRefs) { "Incomplete related vocabulary group" }
    if (target.isNull("relatedVocabularyItems") && target.isNull("relatedVocabularyItemRefs")) return null to null
    val rawItems = target.get("relatedVocabularyItems")
    val rawRefs = target.get("relatedVocabularyItemRefs")
    require(rawItems is JSONArray && rawRefs is JSONArray)
    val items = List(rawItems.length()) { index ->
        val raw = rawItems.get(index)
        require(raw is JSONObject)
        require(raw.keys().asSequence().toSet() == setOf("itemRef", "unitId", "display", "senseZh", "unitType", "sourceEvidence"))
        fun text(key: String) = raw.get(key).let { require(it is String && it.isNotBlank()); it }
        RelatedVocabularyItem(text("itemRef"), text("unitId"), text("display"), text("senseZh"),
            LearningTargetType.valueOf(text("unitType")).also { require(it != LearningTargetType.PATTERN) },
            requireNotNull(decodeSourceEvidence(raw.get("sourceEvidence"))))
    }
    val refs = List(rawRefs.length()) { rawRefs.get(it).let { value ->
        require(value is String && value.isNotBlank()); value
    } }
    require(items.map { it.itemRef }.distinct().size == items.size)
    require(items.all { it.itemRef.length <= 128 } && refs.all { it.length <= 128 })
    require(items.map { it.sourceEvidence.startUtf16 to it.sourceEvidence.endUtf16 }.distinct().size == items.size)
    require(refs.distinct().size == refs.size && refs.toSet() == items.map { it.itemRef }.toSet())
    return items to refs
}

fun encodeRelatedVocabularyGroup(target: LearningTarget, json: JSONObject) {
    require(target.hasValidRelatedVocabularyStructure())
    val items = target.relatedVocabularyItems
    val refs = target.relatedVocabularyItemRefs
    if (items == null && refs == null) {
        // Do not needlessly make a legacy draft forward-incompatible with its old strict reader.
        json.remove("relatedVocabularyItems")
        json.remove("relatedVocabularyItemRefs")
        return
    }
    json.put("relatedVocabularyItems", JSONArray(requireNotNull(items).map { item ->
        JSONObject().put("itemRef", item.itemRef).put("unitId", item.unitId).put("display", item.display)
            .put("senseZh", item.senseZh).put("unitType", item.unitType.name).put("sourceEvidence", item.sourceEvidence.toJson())
    }))
    json.put("relatedVocabularyItemRefs", JSONArray(requireNotNull(refs)))
}
