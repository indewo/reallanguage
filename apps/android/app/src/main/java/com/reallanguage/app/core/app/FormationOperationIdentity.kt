package com.reallanguage.app.core.app

import com.reallanguage.app.feature.formation.domain.SemanticUnit

internal fun chapterFormationCommandId(route: HomeDiaryRoute.ChapterFormation): String =
    confirmedSourceCommandId("chapter-context-v1-", route.sourceEntryId, route.sourceType.name, route.contentShape.name,
        canonical(listOf(route.text) + unitFields(route.units) + listFields(route.selectedUnitIds)))

internal fun englishGenerationCommandId(route: HomeDiaryRoute.EnglishGeneration): String {
    val fields = listOf(route.text) + unitFields(route.units) + listFields(route.selectedUnitIds) +
        listOf(route.chapters.size.toString()) + route.chapters.flatMap { listOf(it.chapterId) + listFields(it.unitIds) }
    return confirmedSourceCommandId("english-v1-", route.sourceEntryId, route.sourceType.name, route.contentShape.name, canonical(fields))
}

internal fun learningTargetExtractionCommandId(route: HomeDiaryRoute.LearningTargets): String {
    // Match the actual selected-sentence payload, not Map insertion order or
    // unused alternatives that are never sent to the target provider.
    val fields = listOf(route.text, route.chapters.size.toString()) + route.chapters.flatMap { chapter ->
        listOf(chapter.chapterId, chapter.sentences.size.toString()) + chapter.sentences.flatMap { sentence ->
            val candidate = sentence.candidates.singleOrNull { it.candidateId == route.selections[sentence.unitId] }
            listOf(sentence.unitId, sentence.textZh, sentence.isFocus.toString(),
                if (candidate == null) "unresolved" else "selected",
                candidate?.textEn ?: route.selections[sentence.unitId].orEmpty())
        }
    }
    return confirmedSourceCommandId("targets-v1-", route.sourceEntryId, route.sourceType.name, route.contentShape.name, canonical(fields))
}

private fun canonical(fields: List<String>): String = fields.joinToString("") { "${it.length}:$it" }
private fun listFields(values: List<String>): List<String> = listOf(values.size.toString()) + values
private fun unitFields(units: List<SemanticUnit>): List<String> =
    listOf(units.size.toString()) + units.sortedBy { it.order }.flatMap { listOf(it.unitId, it.order.toString(), it.textZh) }

internal fun formationSourceEntryId(route: HomeDiaryRoute): String? = when (route) {
    is HomeDiaryRoute.Bilingual -> "bilingual-${route.commandId}"
    is HomeDiaryRoute.SemanticSplit -> route.entryId
    is HomeDiaryRoute.FocusSelection -> route.sourceEntryId
    is HomeDiaryRoute.ChapterFormation -> route.sourceEntryId
    is HomeDiaryRoute.EnglishGeneration -> route.sourceEntryId
    is HomeDiaryRoute.EnglishConfirmation -> route.sourceEntryId
    is HomeDiaryRoute.LearningTargets -> route.sourceEntryId
    is HomeDiaryRoute.LearningTargetSelection -> route.sourceEntryId
    is HomeDiaryRoute.FinalFix -> route.sourceEntryId
    else -> null
}

internal fun withFormationSourceEntryId(route: HomeDiaryRoute, sourceEntryId: String): HomeDiaryRoute = when (route) {
    is HomeDiaryRoute.SemanticSplit -> route.copy(entryId = sourceEntryId)
    is HomeDiaryRoute.FocusSelection -> route.copy(sourceEntryId = sourceEntryId)
    is HomeDiaryRoute.ChapterFormation -> route.copy(sourceEntryId = sourceEntryId)
    is HomeDiaryRoute.EnglishGeneration -> route.copy(sourceEntryId = sourceEntryId)
    is HomeDiaryRoute.EnglishConfirmation -> route.copy(sourceEntryId = sourceEntryId)
    is HomeDiaryRoute.LearningTargets -> route.copy(sourceEntryId = sourceEntryId)
    is HomeDiaryRoute.LearningTargetSelection -> route.copy(sourceEntryId = sourceEntryId)
    is HomeDiaryRoute.FinalFix -> route.copy(sourceEntryId = sourceEntryId)
    else -> route
}
