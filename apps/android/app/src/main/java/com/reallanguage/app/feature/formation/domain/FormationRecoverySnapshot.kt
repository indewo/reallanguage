package com.reallanguage.app.feature.formation.domain

import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.SourceType
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneSpeaker
import com.reallanguage.app.feature.scene.domain.SceneGeneratedDraft
import com.reallanguage.app.feature.scene.domain.SceneQuestionSession

enum class FormationRecoveryPhase {
    SEMANTIC_SPLIT, FOCUS_SELECTION, CHAPTER_FORMATION, ENGLISH_GENERATION,
    ENGLISH_CONFIRMATION, LEARNING_TARGET_LOADING, LEARNING_TARGET_SELECTION, FINAL_FIX,
}

/** Domain references, never Android routes, credentials, file paths or inferred editor identities. */
sealed interface FormationSourceOrigin {
    data object Diary : FormationSourceOrigin
    data class Recording(val recordingId: String, val transcriptId: String) : FormationSourceOrigin
    data class Scene(val session: SceneQuestionSession, val draft: SceneGeneratedDraft) : FormationSourceOrigin
    data class Unavailable(val reviewRequestId: String? = null) : FormationSourceOrigin
}

/** Only accepted formation data. Unsubmitted inputs and formal learning state are not this DTO. */
data class FormationRecoverySnapshot(
    val sourceEntryId: String,
    val sourceRevision: Long,
    val phase: FormationRecoveryPhase,
    val source: ConfirmedSource,
    val sourceTitle: String,
    val origin: FormationSourceOrigin,
    val units: List<SemanticUnit> = emptyList(),
    val selectedUnitIds: List<String> = emptyList(),
    val chapters: List<ChapterDraft> = emptyList(),
    val englishChapters: List<EnglishChapterDraft> = emptyList(),
    val selectedCandidateIds: Map<String, String> = emptyMap(),
    val targets: List<LearningTarget> = emptyList(),
    val queryableExpressions: List<QueryableExpression> = emptyList(),
    val selectedTargetIds: Set<String> = emptySet(),
    val targetSelectionEntryId: String? = null,
    val fixEntryId: String? = null,
    val targetSentenceIndex: Int = 0,
)

fun FormationRecoverySnapshot.isValidForRecovery(): Boolean {
    if (!Regex("[A-Za-z0-9][A-Za-z0-9:._-]{0,255}").matches(sourceEntryId) || sourceRevision < 0 ||
        source.text.isBlank() || sourceTitle.isBlank() || !validOrigin()) return false
    if (targetSelectionEntryId?.isBlank() == true || fixEntryId?.isBlank() == true || targetSentenceIndex < 0) return false
    val unitById = units.associateBy { it.unitId }
    if (unitById.size != units.size || units.withIndex().any { (index, unit) ->
        unit.unitId.isBlank() || unit.order != index || unit.textZh.isBlank() ||
            !unit.textZh.hasMinimumSemanticGranularity()
    }) return false
    val focus = selectedUnitIds.toSet()
    if (focus.size != selectedUnitIds.size || focus.size > 3 || !unitById.keys.containsAll(focus)) return false
    if (chapters.map { it.chapterId }.distinct().size != chapters.size || chapters.any { chapter ->
        chapter.chapterId.isBlank() || chapter.unitIds.size !in 1..4 || chapter.unitIds.any { it !in unitById } ||
            chapter.unitIds.none { it in focus }
    }) return false
    val chapterUnits = chapters.flatMap { it.unitIds }
    if (chapterUnits.distinct().size != chapterUnits.size || (chapters.isNotEmpty() && !chapterUnits.containsAll(focus))) return false
    // Preserve business ordering and require a coherent snapshot; do not reorder or repair corrupt data.
    if (chapterUnits.zipWithNext().any { (left, right) -> unitById.getValue(left).order >= unitById.getValue(right).order }) return false
    if (englishChapters.isNotEmpty() && (englishChapters.map { it.chapterId } != chapters.map { it.chapterId } ||
        englishChapters.zip(chapters).any { (english, chapter) -> english.sentences.map { it.unitId } != chapter.unitIds })) return false
    val sentences = englishChapters.flatMap { it.sentences }
    if (sentences.any { sentence ->
        sentence.textZh != unitById[sentence.unitId]?.textZh || sentence.isFocus != (sentence.unitId in focus) ||
            sentence.candidates.size !in 1..3 || sentence.candidates.map { it.candidateId }.distinct().size != sentence.candidates.size ||
            sentence.candidates.any { candidate -> candidate.candidateId.isBlank() || candidate.textEn.isBlank() ||
                candidate.reusedKnowledgeRefs.size > 16 || candidate.reusedKnowledgeRefs.distinct().size != candidate.reusedKnowledgeRefs.size ||
                !candidate.reusedKnowledgeRefs.all(::isValidKnowledgeRef) }
    }) return false
    val sentenceById = sentences.associateBy { it.unitId }
    if (selectedCandidateIds.any { (unit, candidate) -> sentenceById[unit]?.candidates?.count { it.candidateId == candidate } != 1 }) return false
    val selectedEnglish = sentences.mapNotNull { sentence ->
        sentence.candidates.singleOrNull { it.candidateId == selectedCandidateIds[sentence.unitId] }?.let { sentence.unitId to it }
    }.toMap()
    val allEnglishSelected = sentences.isNotEmpty() && selectedEnglish.size == sentences.size
    if (targets.map { it.targetId }.distinct().size != targets.size || targets.any { target ->
        val english = selectedEnglish[target.unitId]
        target.targetId.isBlank() || target.unitId !in focus || target.display.isBlank() || target.explanationZh.isBlank() || english == null ||
            !target.hasValidStructureParts(requirePatternStructure = target.sourceEvidence != null) ||
            !target.hasValidCandidateExample() ||
            !target.hasValidRelatedVocabularyItems(english.textEn) ||
            (target.sourceEvidence?.isValidForTarget(english.textEn, target.display, target.type) == false) ||
            (target.knownKnowledgeRef?.let { ref ->
                !isValidKnowledgeRef(ref) || ref !in english.reusedKnowledgeRefs || target.sourceEvidence == null ||
                    !ref.startsWith(if (target.type == LearningTargetType.PATTERN) "PATTERN:" else "VOCABULARY:")
            } == true)
    }) return false
    if (!targets.hasConsistentVocabularyExplanations()) return false
    val knownTargets = targets.mapNotNull { target -> target.knownKnowledgeRef?.let { target.unitId to it } }
    if (knownTargets.distinct().size != knownTargets.size || !targets.map { it.targetId }.containsAll(selectedTargetIds)) return false
    if (queryableExpressions.map { it.unitId to it.expressionId }.distinct().size != queryableExpressions.size ||
        queryableExpressions.any { expression ->
            expression.unitId !in focus || expression.expressionId.isBlank() || expression.display.isBlank() ||
                selectedEnglish[expression.unitId] == null || (expression.expressionId.startsWith("word:") &&
                sentenceById.getValue(expression.unitId).wordExpressions(selectedCandidateIds[expression.unitId]).none {
                    it.expressionId == expression.expressionId && it.display.equals(expression.display.trim(), ignoreCase = true)
                })
        }) return false
    val hasTargetData = targets.isNotEmpty() || queryableExpressions.isNotEmpty() || selectedTargetIds.isNotEmpty() ||
        targetSelectionEntryId != null || fixEntryId != null || targetSentenceIndex != 0
    if (hasTargetData && (!allEnglishSelected || targetSelectionEntryId == null || targetSentenceIndex !in 0 until focus.size)) return false
    // A return to an earlier page may keep *unchanged* valid results. Phase is a minimum, not a deletion command.
    return when (phase) {
        FormationRecoveryPhase.SEMANTIC_SPLIT -> true
        FormationRecoveryPhase.FOCUS_SELECTION -> units.isNotEmpty()
        FormationRecoveryPhase.CHAPTER_FORMATION -> focus.size in 1..3
        FormationRecoveryPhase.ENGLISH_GENERATION -> chapters.isNotEmpty()
        FormationRecoveryPhase.ENGLISH_CONFIRMATION -> englishChapters.isNotEmpty()
        FormationRecoveryPhase.LEARNING_TARGET_LOADING -> allEnglishSelected
        FormationRecoveryPhase.LEARNING_TARGET_SELECTION -> allEnglishSelected && targetSelectionEntryId != null
        FormationRecoveryPhase.FINAL_FIX -> allEnglishSelected && targetSelectionEntryId != null && fixEntryId != null
    }
}

private fun FormationRecoverySnapshot.validOrigin(): Boolean = when (val value = origin) {
    FormationSourceOrigin.Diary -> source.sourceType == SourceType.DIARY
    is FormationSourceOrigin.Recording -> source.sourceType == SourceType.RECORDING && value.recordingId.isNotBlank() && value.transcriptId.isNotBlank()
    is FormationSourceOrigin.Unavailable -> value.reviewRequestId == null || value.reviewRequestId.isNotBlank()
    is FormationSourceOrigin.Scene -> {
        val session = value.session
        val draft = value.draft
        val contentType = session.contentType
        val text = when (contentType) {
            SceneContentType.ARTICLE -> draft.units.joinToString("\n") { it.textZh.trim() }
            SceneContentType.DIALOGUE -> draft.units.joinToString("\n") {
                "${if (it.speaker == SceneSpeaker.USER) "用户" else "对方"}：${it.textZh.trim()}"
            }
        }
        source.sourceType == SourceType.AI_SCENE && session.operationId.isNotBlank() && draft.draftId.isNotBlank() &&
            draft.contentType == contentType && source.contentShape.name == contentType.name && draft.units.isNotEmpty() &&
            draft.units.map { it.unitId }.distinct().size == draft.units.size &&
            draft.units.all { it.unitId.isNotBlank() && it.textZh.isNotBlank() && (contentType != SceneContentType.DIALOGUE || it.speaker != null) } &&
            source.text == text
    }
}
