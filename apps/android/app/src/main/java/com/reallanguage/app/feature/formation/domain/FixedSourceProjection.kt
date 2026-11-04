package com.reallanguage.app.feature.formation.domain

import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.SourceType

data class FixedSourceUnit(val unitId: String, val order: Int, val textZh: String)

sealed interface FixedSourceOrigin {
    data object Diary : FixedSourceOrigin
    data class Recording(val recordingId: String, val transcriptId: String) : FixedSourceOrigin
    data class Scene(val operationId: String, val draftId: String) : FixedSourceOrigin
    data class Unavailable(val reviewRequestId: String? = null) : FixedSourceOrigin
}

/** Immutable original source and confirmed identities, not unselected AI candidates or learning state. */
data class FixedSourceProjection(
    val formationFlowId: String,
    val sourceEntryId: String,
    val sourceRevision: Long,
    val source: ConfirmedSource,
    val title: String,
    val units: List<FixedSourceUnit>,
    val selectedUnitIds: List<String>,
    val selectedCandidateIds: Map<String, String>,
    val selectedTargetIds: Set<String>,
    val origin: FixedSourceOrigin,
)

fun FormationRecoverySnapshot.toFixedSourceProjection(formationFlowId: String): FixedSourceProjection? {
    if (phase != FormationRecoveryPhase.FINAL_FIX || !isValidForRecovery() || formationFlowId.isBlank()) return null
    return FixedSourceProjection(formationFlowId, sourceEntryId, sourceRevision, source, sourceTitle,
        units.map { FixedSourceUnit(it.unitId, it.order, it.textZh) }, selectedUnitIds.toList(),
        selectedCandidateIds.toMap(), selectedTargetIds.toSet(), when (val value = origin) {
            FormationSourceOrigin.Diary -> FixedSourceOrigin.Diary
            is FormationSourceOrigin.Recording -> FixedSourceOrigin.Recording(value.recordingId, value.transcriptId)
            is FormationSourceOrigin.Scene -> FixedSourceOrigin.Scene(value.session.operationId, value.draft.draftId)
            is FormationSourceOrigin.Unavailable -> FixedSourceOrigin.Unavailable(value.reviewRequestId)
        }).takeIf { it.isValidFixedSource() }
}

/** Structural validation does not invent a missing original source or infer editor identities. */
fun FixedSourceProjection.isValidFixedSource(): Boolean {
    if (formationFlowId.isBlank() || !Regex("[A-Za-z0-9][A-Za-z0-9:._-]{0,255}").matches(sourceEntryId) ||
        sourceRevision < 0 || source.text.isBlank() || title.isBlank() || units.isEmpty()) return false
    val unitIds = units.map { it.unitId }
    if (unitIds.distinct().size != units.size || units.withIndex().any { (index, unit) ->
        unit.unitId.isBlank() || unit.order != index || unit.textZh.isBlank()
    }) return false
    if (selectedUnitIds.size !in 1..3 || selectedUnitIds.distinct().size != selectedUnitIds.size ||
        !unitIds.containsAll(selectedUnitIds) || !selectedCandidateIds.keys.containsAll(selectedUnitIds) ||
        selectedCandidateIds.any { it.key !in unitIds || it.value.isBlank() } || selectedTargetIds.any { it.isBlank() }) return false
    return when (val value = origin) {
        FixedSourceOrigin.Diary -> source.sourceType == SourceType.DIARY
        is FixedSourceOrigin.Recording -> source.sourceType == SourceType.RECORDING && value.recordingId.isNotBlank() && value.transcriptId.isNotBlank()
        is FixedSourceOrigin.Scene -> source.sourceType == SourceType.AI_SCENE && value.operationId.isNotBlank() && value.draftId.isNotBlank()
        is FixedSourceOrigin.Unavailable -> value.reviewRequestId == null || value.reviewRequestId.isNotBlank()
    }
}

fun FixedSourceProjection.isValidFor(request: ContentFixRequest): Boolean {
    if (!isValidFixedSource() || formationFlowId != request.formationFlowId || title != request.sourceTitle ||
        selectedCandidateIds != request.selectedCandidateIds || selectedTargetIds != request.selectedTargetIds) return false
    val storageType = if (source.sourceType == SourceType.RECORDING) "QUICK_RECORDING" else source.sourceType.name
    if (storageType != request.sourceType) return false
    val unitById = units.associateBy { it.unitId }
    val sentences = request.chapters.flatMap { it.sentences }
    return sentences.map { it.unitId }.distinct().size == sentences.size &&
        sentences.mapTo(mutableSetOf()) { it.unitId } == selectedCandidateIds.keys &&
        sentences.filter { it.isFocus }.mapTo(mutableSetOf()) { it.unitId } == selectedUnitIds.toSet() &&
        sentences.all { sentence -> sentence.textZh == unitById[sentence.unitId]?.textZh &&
            sentence.candidates.count { it.candidateId == selectedCandidateIds[sentence.unitId] } == 1 } &&
        sentences.zipWithNext().all { (left, right) -> unitById.getValue(left.unitId).order < unitById.getValue(right.unitId).order }
}

sealed interface FixedSourceLoad {
    data class Loaded(val source: FixedSourceProjection) : FixedSourceLoad
    data object Missing : FixedSourceLoad
    data object Unavailable : FixedSourceLoad
}

fun interface FixedSourceRepository {
    suspend fun load(formationFlowId: String): FixedSourceLoad
}
