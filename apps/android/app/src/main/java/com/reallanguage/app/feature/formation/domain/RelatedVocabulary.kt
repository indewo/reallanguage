package com.reallanguage.app.feature.formation.domain

/** A current-sentence explanation, not an additional selected learning target. */
data class RelatedVocabularyItem(
    val itemRef: String,
    val unitId: String,
    val display: String,
    val senseZh: String,
    val unitType: LearningTargetType,
    val sourceEvidence: SourceEvidence,
)

/** Already delivered meaning for one explicitly tapped source occurrence; not a knowledge identity. */
data class AcceptedVocabularyContext(
    val display: String,
    val senseZh: String,
    val unitType: LearningTargetType,
    val sourceEvidence: SourceEvidence,
)

fun validateRelatedVocabularyGroup(
    unitId: String,
    type: LearningTargetType,
    legacyDisplays: List<String>,
    items: List<RelatedVocabularyItem>?,
    refs: List<String>?,
    textEn: String,
): Boolean = validRelatedVocabularyShape(unitId, type, legacyDisplays, items, refs) &&
    items.orEmpty().all { it.sourceEvidence.isValidForTarget(textEn, it.display, it.unitType) }

fun LearningTarget.hasValidRelatedVocabularyItems(textEn: String): Boolean =
    validateRelatedVocabularyGroup(unitId, type, relatedVocabulary, relatedVocabularyItems,
        relatedVocabularyItemRefs, textEn)

/** Wire codecs can check the complete shape; the request/snapshot owner additionally checks the actual sentence. */
fun LearningTarget.hasValidRelatedVocabularyStructure(): Boolean =
    validRelatedVocabularyShape(unitId, type, relatedVocabulary, relatedVocabularyItems, relatedVocabularyItemRefs)

private fun validRelatedVocabularyShape(
    unitId: String,
    type: LearningTargetType,
    legacyDisplays: List<String>,
    items: List<RelatedVocabularyItem>?,
    refs: List<String>?,
): Boolean {
    if (items == null || refs == null) return items == null && refs == null
    if (legacyDisplays.any(String::isBlank)) return false
    if (type != LearningTargetType.PATTERN && (items.isNotEmpty() || refs.isNotEmpty())) return false
    if (items.any { it.itemRef.isBlank() || it.itemRef.length > 128 || it.unitId != unitId || it.unitId.isBlank() || it.display.isBlank() ||
        it.senseZh.isBlank() || it.unitType == LearningTargetType.PATTERN || !it.sourceEvidence.hasValidStructure()
    }) return false
    val byRef = items.associateBy { it.itemRef }
    if (byRef.size != items.size || refs.any { it.isBlank() || it.length > 128 } || refs.distinct().size != refs.size ||
        refs.toSet() != byRef.keys) return false
    if (items.map { it.sourceEvidence.startUtf16 to it.sourceEvidence.endUtf16 }.distinct().size != items.size) return false
    return refs.map { byRef.getValue(it).display } == legacyDisplays
}

/** The same occurrence cannot be delivered with two different meanings, even if its vocabulary target is unselected. */
fun List<LearningTarget>.hasConsistentVocabularyExplanations(): Boolean {
    data class Occurrence(val unitId: String, val start: Int, val end: Int)
    data class Explanation(val display: String, val senseZh: String, val type: LearningTargetType)
    val seen = mutableMapOf<Occurrence, Explanation>()
    val actualVocabulary = mutableSetOf<Occurrence>()
    fun accept(unitId: String, evidence: SourceEvidence, display: String, senseZh: String, type: LearningTargetType): Boolean {
        val occurrence = Occurrence(unitId, evidence.startUtf16, evidence.endUtf16)
        val explanation = Explanation(display, senseZh, type)
        val previous = seen.putIfAbsent(occurrence, explanation)
        return previous == null || previous == explanation
    }
    for (target in this) {
        if (target.type != LearningTargetType.PATTERN && target.sourceEvidence != null) {
            if (!actualVocabulary.add(Occurrence(target.unitId, target.sourceEvidence.startUtf16, target.sourceEvidence.endUtf16)) ||
                !accept(target.unitId, target.sourceEvidence, target.display, target.explanationZh, target.type)) return false
        }
        for (item in target.relatedVocabularyItems.orEmpty()) {
            if (!accept(item.unitId, item.sourceEvidence, item.display, item.senseZh, item.unitType)) return false
        }
    }
    return true
}
