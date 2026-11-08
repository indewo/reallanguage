package com.reallanguage.app.core.app

import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.formation.domain.FormationRecoveryPhase
import com.reallanguage.app.feature.formation.domain.FormationRecoverySnapshot

internal data class FormationReturnTarget(val route: HomeDiaryRoute, val editorUnavailable: Boolean = false)

internal fun formationReturnTarget(route: HomeDiaryRoute): FormationReturnTarget {
    val source = when (route) {
        is HomeDiaryRoute.SemanticSplit -> ConfirmedSource(route.text, route.sourceType, route.contentShape)
        is HomeDiaryRoute.FocusSelection -> ConfirmedSource(route.text, route.sourceType, route.contentShape)
        else -> return FormationReturnTarget(HomeDiaryRoute.Home)
    }
    val origin = formationSourceReturnRoute(route)
    safetySourceEditRoute(source, origin)?.let { return FormationReturnTarget(it) }
    if (origin is HomeDiaryRoute.ManualReviewResult || origin is HomeDiaryRoute.Messages) {
        return FormationReturnTarget(origin)
    }
    // Legacy reviewed sources may lack their recording/session identity. Never
    // fabricate an editor or turn Back into another billable safety request.
    return FormationReturnTarget(HomeDiaryRoute.Messages(), editorUnavailable = true)
}

internal fun withFormationSourceReturnRoute(route: HomeDiaryRoute, origin: HomeDiaryRoute?): HomeDiaryRoute = when (route) {
    is HomeDiaryRoute.SemanticSplit -> route.copy(sourceReturnRoute = origin)
    is HomeDiaryRoute.FocusSelection -> route.copy(sourceReturnRoute = origin)
    is HomeDiaryRoute.ChapterFormation -> route.copy(sourceReturnRoute = origin)
    is HomeDiaryRoute.EnglishGeneration -> route.copy(sourceReturnRoute = origin)
    is HomeDiaryRoute.EnglishConfirmation -> route.copy(sourceReturnRoute = origin)
    is HomeDiaryRoute.LearningTargets -> route.copy(sourceReturnRoute = origin)
    is HomeDiaryRoute.LearningTargetSelection -> route.copy(sourceReturnRoute = origin)
    is HomeDiaryRoute.FinalFix -> route.copy(sourceReturnRoute = origin)
    else -> route
}

internal fun preserveFormationOrigin(current: HomeDiaryRoute, next: HomeDiaryRoute): HomeDiaryRoute {
    val withOrigin = withFormationSourceReturnRoute(next, formationSourceReturnRoute(current))
    val withIdentity = formationSourceEntryId(current)?.let { withFormationSourceEntryId(withOrigin, it) } ?: withOrigin
    val selections = when (current) {
        is HomeDiaryRoute.LearningTargets -> current.selections
        is HomeDiaryRoute.LearningTargetSelection -> current.selections
        is HomeDiaryRoute.FinalFix -> current.selections
        else -> null
    }
    return if (withIdentity is HomeDiaryRoute.EnglishConfirmation && selections != null) {
        withIdentity.copy(initialSelectedCandidateIds = selections)
    } else withIdentity
}

/** One shared backward transition for system Back and visible Back buttons. */
internal fun formationPreviousRoute(
    current: HomeDiaryRoute,
    snapshot: FormationRecoverySnapshot? = null,
): HomeDiaryRoute? = when (current) {
    is HomeDiaryRoute.ChapterFormation -> preserveFormationOrigin(current,
        HomeDiaryRoute.FocusSelection(current.text, current.units, current.sourceType, current.contentShape,
            initialSelectedUnitIds = current.selectedUnitIds.toSet()))
    is HomeDiaryRoute.EnglishGeneration -> preserveFormationOrigin(current,
        HomeDiaryRoute.FocusSelection(current.text, current.units, current.sourceType, current.contentShape,
            initialSelectedUnitIds = current.selectedUnitIds.toSet()))
    is HomeDiaryRoute.EnglishConfirmation -> preserveFormationOrigin(current,
        HomeDiaryRoute.FocusSelection(current.text, current.units, current.sourceType, current.contentShape,
            initialSelectedUnitIds = current.chapters.flatMap { it.sentences }.filter { it.isFocus }.map { it.unitId }.toSet()))
    is HomeDiaryRoute.LearningTargets -> current.sourceReturnRoute?.takeIf { it is HomeDiaryRoute.Bilingual } ?: preserveFormationOrigin(current,
        HomeDiaryRoute.EnglishConfirmation(current.text, current.units, current.chapters, current.sourceType, current.contentShape))
    is HomeDiaryRoute.LearningTargetSelection -> current.sourceReturnRoute?.takeIf { it is HomeDiaryRoute.Bilingual } ?: preserveFormationOrigin(current,
        HomeDiaryRoute.EnglishConfirmation(current.text, current.units, current.chapters, current.sourceType, current.contentShape))
    is HomeDiaryRoute.FinalFix -> snapshot
        ?.copy(phase = FormationRecoveryPhase.LEARNING_TARGET_SELECTION)
        ?.toFormationRecoveryRoute()
        ?.let { restored ->
            (restored.route as? HomeDiaryRoute.LearningTargetSelection)
                ?.copy(initialSentenceIndex = restored.targetSentenceIndex)
        }
        // A legacy/missing journal must still move backward. English confirmation
        // contains all data carried by FinalFix and can regenerate target choices.
        ?: preserveFormationOrigin(current,
            HomeDiaryRoute.EnglishConfirmation(current.text, current.units, current.chapters,
                current.sourceType, current.contentShape))
    else -> null
}

internal fun formationSourceReturnRoute(route: HomeDiaryRoute): HomeDiaryRoute? = when (route) {
    is HomeDiaryRoute.SemanticSplit -> route.sourceReturnRoute
    is HomeDiaryRoute.FocusSelection -> route.sourceReturnRoute
    is HomeDiaryRoute.ChapterFormation -> route.sourceReturnRoute
    is HomeDiaryRoute.EnglishGeneration -> route.sourceReturnRoute
    is HomeDiaryRoute.EnglishConfirmation -> route.sourceReturnRoute
    is HomeDiaryRoute.LearningTargets -> route.sourceReturnRoute
    is HomeDiaryRoute.LearningTargetSelection -> route.sourceReturnRoute
    is HomeDiaryRoute.FinalFix -> route.sourceReturnRoute
    else -> null
}

internal fun semanticSplitCommandId(route: HomeDiaryRoute.SemanticSplit): String =
    confirmedSourceCommandId("split-v1-", route.entryId, route.sourceType.name, route.contentShape.name, route.text)
