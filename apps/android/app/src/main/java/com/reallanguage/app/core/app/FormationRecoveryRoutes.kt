package com.reallanguage.app.core.app

import com.reallanguage.app.feature.formation.domain.ChapterDraft
import com.reallanguage.app.feature.formation.domain.FormationRecoveryPhase
import com.reallanguage.app.feature.formation.domain.FormationRecoverySnapshot
import com.reallanguage.app.feature.formation.domain.FormationSourceOrigin
import com.reallanguage.app.feature.formation.domain.SavedBilingualReview
import com.reallanguage.app.feature.formation.domain.isValidForRecovery
import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.SourceType

data class RestoredFormationRoute(val route: HomeDiaryRoute, val targetSentenceIndex: Int)

fun HomeDiaryRoute.toFormationRecoverySnapshot(
    sourceRevision: Long,
    sourceTitle: String,
    previous: FormationRecoverySnapshot? = null,
    targetSentenceIndex: Int = 0,
): FormationRecoverySnapshot? {
    val source = when (this) {
        is HomeDiaryRoute.Bilingual -> ConfirmedSource(text, origin.legacy, shape)
        is HomeDiaryRoute.SemanticSplit -> ConfirmedSource(text, sourceType, contentShape)
        is HomeDiaryRoute.FocusSelection -> ConfirmedSource(text, sourceType, contentShape)
        is HomeDiaryRoute.ChapterFormation -> ConfirmedSource(text, sourceType, contentShape)
        is HomeDiaryRoute.EnglishGeneration -> ConfirmedSource(text, sourceType, contentShape)
        is HomeDiaryRoute.EnglishConfirmation -> ConfirmedSource(text, sourceType, contentShape)
        is HomeDiaryRoute.LearningTargets -> ConfirmedSource(text, sourceType, contentShape)
        is HomeDiaryRoute.LearningTargetSelection -> ConfirmedSource(text, sourceType, contentShape)
        is HomeDiaryRoute.FinalFix -> ConfirmedSource(text, sourceType, contentShape)
        else -> return null
    }
    val phase = when (this) {
        is HomeDiaryRoute.Bilingual -> FormationRecoveryPhase.BILINGUAL_SELECTION
        is HomeDiaryRoute.SemanticSplit -> FormationRecoveryPhase.SEMANTIC_SPLIT
        is HomeDiaryRoute.FocusSelection -> FormationRecoveryPhase.FOCUS_SELECTION
        is HomeDiaryRoute.ChapterFormation -> FormationRecoveryPhase.CHAPTER_FORMATION
        is HomeDiaryRoute.EnglishGeneration -> FormationRecoveryPhase.ENGLISH_GENERATION
        is HomeDiaryRoute.EnglishConfirmation -> FormationRecoveryPhase.ENGLISH_CONFIRMATION
        is HomeDiaryRoute.LearningTargets -> FormationRecoveryPhase.LEARNING_TARGET_LOADING
        is HomeDiaryRoute.LearningTargetSelection -> FormationRecoveryPhase.LEARNING_TARGET_SELECTION
        is HomeDiaryRoute.FinalFix -> FormationRecoveryPhase.FINAL_FIX
        else -> return null
    }
    val sourceEntryId = formationSourceEntryId(this) ?: return null
    val review = (this as? HomeDiaryRoute.Bilingual) ?: (formationSourceReturnRoute(this) as? HomeDiaryRoute.Bilingual)
    val origin = recoveryOrigin(source, review?.back ?: formationSourceReturnRoute(this))
    val savedReview = review?.let { SavedBilingualReview(it.origin.name, it.paragraphs, it.selected) }
    // A route carries accepted inputs, not an instruction to repair a stale snapshot.
    var snapshot = previous?.takeIf {
        it.isValidForRecovery() && it.sourceEntryId == sourceEntryId && it.sourceRevision == sourceRevision &&
            it.source == source && it.origin == origin
    }?.copy(phase = phase, sourceTitle = sourceTitle) ?: FormationRecoverySnapshot(
        sourceEntryId, sourceRevision, phase, source, sourceTitle, origin,
    )
    if (savedReview != null) snapshot = snapshot.copy(bilingual = savedReview)
    if (this is HomeDiaryRoute.Bilingual) return snapshot.takeIf { it.isValidForRecovery() }
    val units = when (this) {
        is HomeDiaryRoute.FocusSelection -> this.units
        is HomeDiaryRoute.ChapterFormation -> this.units
        is HomeDiaryRoute.EnglishGeneration -> this.units
        is HomeDiaryRoute.EnglishConfirmation -> this.units
        is HomeDiaryRoute.LearningTargets -> this.units
        is HomeDiaryRoute.LearningTargetSelection -> this.units
        is HomeDiaryRoute.FinalFix -> this.units
        else -> null
    }
    if (units != null && units != snapshot.units) snapshot = snapshot.withoutTargets().copy(
        units = units, selectedUnitIds = emptyList(), chapters = emptyList(), englishChapters = emptyList(), selectedCandidateIds = emptyMap(),
    )
    val english = when (this) {
        is HomeDiaryRoute.EnglishConfirmation -> this.chapters
        is HomeDiaryRoute.LearningTargets -> this.chapters
        is HomeDiaryRoute.LearningTargetSelection -> this.chapters
        is HomeDiaryRoute.FinalFix -> this.chapters
        else -> null
    }
    val focus = when (this) {
        is HomeDiaryRoute.FocusSelection -> {
            if (!this.units.map { it.unitId }.containsAll(initialSelectedUnitIds)) return null
            // The route uses a Set; retain a valid saved ordering when membership did not change.
            if (snapshot.selectedUnitIds.toSet() == initialSelectedUnitIds) snapshot.selectedUnitIds
            else this.units.filter { it.unitId in initialSelectedUnitIds }.map { it.unitId }
        }
        is HomeDiaryRoute.ChapterFormation -> selectedUnitIds
        is HomeDiaryRoute.EnglishGeneration -> selectedUnitIds
        else -> english?.flatMap { it.sentences }?.filter { it.isFocus }?.map { it.unitId }?.let {
            if (it.toSet() == snapshot.selectedUnitIds.toSet()) snapshot.selectedUnitIds else it
        }
    }
    if (focus != null && focus != snapshot.selectedUnitIds) snapshot = snapshot.withoutTargets().copy(
        selectedUnitIds = focus, chapters = emptyList(), englishChapters = emptyList(), selectedCandidateIds = emptyMap(),
    )
    val chapters = when (this) {
        is HomeDiaryRoute.EnglishGeneration -> this.chapters
        else -> english?.map { chapter -> ChapterDraft(chapter.chapterId, chapter.sentences.map { it.unitId }) }
    }
    if (chapters != null && chapters != snapshot.chapters) snapshot = snapshot.withoutTargets().copy(
        chapters = chapters, englishChapters = emptyList(), selectedCandidateIds = emptyMap(),
    )
    if (english != null && english != snapshot.englishChapters) snapshot = snapshot.withoutTargets().copy(
        englishChapters = english, selectedCandidateIds = emptyMap(),
    )
    val selections = when (this) {
        is HomeDiaryRoute.EnglishConfirmation -> initialSelectedCandidateIds
        is HomeDiaryRoute.LearningTargets -> this.selections
        is HomeDiaryRoute.LearningTargetSelection -> this.selections
        is HomeDiaryRoute.FinalFix -> this.selections
        else -> null
    }
    if (selections != null && selections != snapshot.selectedCandidateIds) snapshot = snapshot.withoutTargets().copy(selectedCandidateIds = selections)
    when (this) {
        is HomeDiaryRoute.LearningTargetSelection -> {
            val selected = initialSelectedTargetIds ?: targets.map { it.targetId }.toSet()
            val unchanged = snapshot.targets == targets && snapshot.queryableExpressions == queryableExpressions &&
                snapshot.targetSelectionEntryId == entryId && snapshot.selectedTargetIds == selected
            snapshot = snapshot.copy(targets = targets, queryableExpressions = queryableExpressions,
                selectedTargetIds = selected, targetSelectionEntryId = entryId,
                fixEntryId = snapshot.fixEntryId.takeIf { unchanged }, targetSentenceIndex = targetSentenceIndex)
        }
        is HomeDiaryRoute.FinalFix -> {
            // FinalFix has no queryable-expression payload or target-page identity. Only
            // the same accepted selection context may supply these; never invent either.
            if (snapshot.targetSelectionEntryId == null || snapshot.targets != targets) return null
            snapshot = snapshot.copy(selectedTargetIds = selectedTargetIds, fixEntryId = entryId, targetSentenceIndex = targetSentenceIndex)
        }
        else -> Unit
    }
    return snapshot.takeIf { it.isValidForRecovery() }
}

fun FormationRecoverySnapshot.toFormationRecoveryRoute(): RestoredFormationRoute? {
    if (!isValidForRecovery()) return null
    val originalEntry = when (val saved = origin) {
        FormationSourceOrigin.Diary -> HomeDiaryRoute.Input(source.text)
        is FormationSourceOrigin.Recording -> HomeDiaryRoute.TranscriptResult(saved.recordingId, saved.transcriptId)
        is FormationSourceOrigin.Scene -> HomeDiaryRoute.SceneChineseReview(saved.session, saved.draft)
        is FormationSourceOrigin.Unavailable -> saved.reviewRequestId?.let { HomeDiaryRoute.ManualReviewResult(it) }
    }
    val review = bilingual?.let { saved ->
        val kind = CaptureOrigin.valueOf(saved.sourceKind)
        val back = when (kind) {
            CaptureOrigin.AUDIO_IMPORT, CaptureOrigin.SCREENSHOT -> HomeDiaryRoute.CaptureImport(kind)
            else -> originalEntry ?: HomeDiaryRoute.Home
        }
        HomeDiaryRoute.Bilingual(source.text, kind, source.contentShape, sourceEntryId.removePrefix("bilingual-"),
            saved.paragraphs, back, saved.selected)
    }
    val originRoute = review ?: originalEntry
    val route = when (phase) {
        FormationRecoveryPhase.BILINGUAL_SELECTION -> review ?: return null
        FormationRecoveryPhase.SEMANTIC_SPLIT -> HomeDiaryRoute.SemanticSplit(
            source.text, source.sourceType, source.contentShape, originRoute, sourceEntryId)
        FormationRecoveryPhase.FOCUS_SELECTION -> HomeDiaryRoute.FocusSelection(
            source.text, units, source.sourceType, source.contentShape, originRoute, selectedUnitIds.toSet(), sourceEntryId)
        FormationRecoveryPhase.CHAPTER_FORMATION -> HomeDiaryRoute.ChapterFormation(
            source.text, units, selectedUnitIds, source.sourceType, source.contentShape, originRoute, sourceEntryId)
        FormationRecoveryPhase.ENGLISH_GENERATION -> HomeDiaryRoute.EnglishGeneration(
            source.text, units, selectedUnitIds, chapters, source.sourceType, source.contentShape, originRoute, sourceEntryId)
        FormationRecoveryPhase.ENGLISH_CONFIRMATION -> HomeDiaryRoute.EnglishConfirmation(
            source.text, units, englishChapters, source.sourceType, source.contentShape, originRoute, sourceEntryId, selectedCandidateIds)
        FormationRecoveryPhase.LEARNING_TARGET_LOADING -> HomeDiaryRoute.LearningTargets(
            source.text, units, englishChapters, selectedCandidateIds, source.sourceType, source.contentShape, originRoute, sourceEntryId)
        FormationRecoveryPhase.LEARNING_TARGET_SELECTION -> HomeDiaryRoute.LearningTargetSelection(
            source.text, units, englishChapters, selectedCandidateIds, targets, queryableExpressions, selectedTargetIds,
            source.sourceType, source.contentShape, requireNotNull(targetSelectionEntryId), originRoute, sourceEntryId)
        FormationRecoveryPhase.FINAL_FIX -> HomeDiaryRoute.FinalFix(
            source.text, units, englishChapters, selectedCandidateIds, targets, selectedTargetIds,
            source.sourceType, source.contentShape, requireNotNull(fixEntryId), originRoute, sourceEntryId)
    }
    return RestoredFormationRoute(route, targetSentenceIndex)
}

private fun FormationRecoverySnapshot.withoutTargets() = copy(
    targets = emptyList(), queryableExpressions = emptyList(), selectedTargetIds = emptySet(),
    targetSelectionEntryId = null, fixEntryId = null, targetSentenceIndex = 0,
)

private fun recoveryOrigin(source: ConfirmedSource, route: HomeDiaryRoute?): FormationSourceOrigin = when (source.sourceType) {
    SourceType.DIARY -> FormationSourceOrigin.Diary
    SourceType.RECORDING -> (route as? HomeDiaryRoute.TranscriptResult)?.takeIf {
        it.recordingId.isNotBlank() && it.transcriptId.isNotBlank()
    }?.let { FormationSourceOrigin.Recording(it.recordingId, it.transcriptId) }
        ?: FormationSourceOrigin.Unavailable((route as? HomeDiaryRoute.ManualReviewResult)?.reviewRequestId)
    SourceType.AI_SCENE -> (route as? HomeDiaryRoute.SceneChineseReview)?.let {
        val origin = FormationSourceOrigin.Scene(it.session, it.draft)
        // Reuse the strict DTO source/scene validator rather than maintaining a
        // second, weaker text reconstruction policy at the navigation boundary.
        val probe = FormationRecoverySnapshot("origin-probe", 0, FormationRecoveryPhase.SEMANTIC_SPLIT, source, "origin-probe", origin)
        origin.takeIf { probe.isValidForRecovery() }
    } ?: FormationSourceOrigin.Unavailable((route as? HomeDiaryRoute.ManualReviewResult)?.reviewRequestId)
}
