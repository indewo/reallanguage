package com.reallanguage.app.core.app

import com.reallanguage.app.feature.formation.domain.FormationRecoverySnapshot
import com.reallanguage.app.feature.formation.domain.FormationRecoveryPhase
import com.reallanguage.app.feature.formation.domain.isValidForRecovery

/** Only previously saved results for these exact accepted inputs may bypass a billable loading stage. */
internal fun cachedFormationContinuation(route: HomeDiaryRoute, snapshot: FormationRecoverySnapshot): HomeDiaryRoute? {
    if (!snapshot.isValidForRecovery() || formationSourceEntryId(route) != snapshot.sourceEntryId) return null
    val next = when {
        route is HomeDiaryRoute.SemanticSplit && snapshot.units.isNotEmpty() -> FormationRecoveryPhase.FOCUS_SELECTION
        route is HomeDiaryRoute.ChapterFormation && snapshot.chapters.isNotEmpty() -> FormationRecoveryPhase.ENGLISH_GENERATION
        route is HomeDiaryRoute.EnglishGeneration && snapshot.englishChapters.isNotEmpty() -> FormationRecoveryPhase.ENGLISH_CONFIRMATION
        route is HomeDiaryRoute.LearningTargets && snapshot.targetSelectionEntryId != null -> FormationRecoveryPhase.LEARNING_TARGET_SELECTION
        else -> return null
    }
    return snapshot.copy(phase = next).toFormationRecoveryRoute()?.let { restored ->
        when (val recovered = restored.route) {
            is HomeDiaryRoute.LearningTargetSelection -> recovered.copy(initialSentenceIndex = restored.targetSentenceIndex)
            else -> recovered
        }
    }
}
