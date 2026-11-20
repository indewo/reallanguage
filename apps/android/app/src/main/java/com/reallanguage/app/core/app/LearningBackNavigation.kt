package com.reallanguage.app.core.app

import com.reallanguage.app.feature.learning.domain.LockedWritingTarget
import com.reallanguage.app.feature.learning.domain.WritingTargetType

/** LR-07 may not exist. Use the fixed chapter targets, never an empty placeholder module. */
internal fun writingPreviousRoute(route: HomeDiaryRoute.CompleteWriting, targets: List<LockedWritingTarget>): HomeDiaryRoute {
    val focuses = route.chapter.sentences.filter { it.isFocus }
    val patternSentenceIds = targets.filter { it.type == WritingTargetType.PATTERN }.map { it.sentenceId }.toSet()
    val lastPattern = focuses.lastOrNull { it.sentenceId in patternSentenceIds }
    return if (lastPattern != null) HomeDiaryRoute.PatternSpeaking(route.chapter, lastPattern.sentenceId, route.baseExplanationZh, route.expressions)
    else HomeDiaryRoute.FullSentenceSpeaking(route.chapter, focuses.last().sentenceId, route.baseExplanationZh, route.expressions)
}

/**
 * The single source of truth for the ordinary chapter-learning back path.
 *
 * A visible Back action returns to the immediately preceding visible learning
 * node. It must not detour through the completed-step archive screen.
 */
internal fun learningPreviousRoute(
    route: HomeDiaryRoute,
    chapterReturnRoute: HomeDiaryRoute = HomeDiaryRoute.Home,
): HomeDiaryRoute? = when (route) {
    is HomeDiaryRoute.ChapterExplanation -> HomeDiaryRoute.ChapterLearning(route.chapter.chapterId, chapterReturnRoute)
    is HomeDiaryRoute.ExplanationReading -> HomeDiaryRoute.ChapterLearning(route.chapter.chapterId, chapterReturnRoute)
    is HomeDiaryRoute.ReadSpeak -> HomeDiaryRoute.ExplanationReading(
        route.chapter,
        route.focusSentenceId,
        route.baseExplanationZh,
        route.expressions,
    )
    is HomeDiaryRoute.Handwriting -> HomeDiaryRoute.ReadSpeak(
        route.chapter,
        route.focusSentenceId,
        route.baseExplanationZh,
        route.expressions,
    )
    is HomeDiaryRoute.AudioDictation -> HomeDiaryRoute.Handwriting(
        route.chapter,
        route.focusSentenceId,
        route.baseExplanationZh,
        route.expressions,
    )
    is HomeDiaryRoute.ReadWriteCorrection -> HomeDiaryRoute.AudioDictation(
        route.chapter,
        route.focusSentenceId,
        route.baseExplanationZh,
        route.expressions,
        route.dictationInput,
    )
    is HomeDiaryRoute.FullSentenceSpeaking -> HomeDiaryRoute.AudioDictation(
        route.chapter,
        route.focusSentenceId,
        route.baseExplanationZh,
        route.expressions,
    )
    is HomeDiaryRoute.PatternSpeaking -> HomeDiaryRoute.FullSentenceSpeaking(
        route.chapter,
        route.focusSentenceId,
        route.baseExplanationZh,
        route.expressions,
    )
    is HomeDiaryRoute.CompleteWriting -> HomeDiaryRoute.PatternSpeaking(
        route.chapter,
        route.focusSentenceId,
        route.baseExplanationZh,
        route.expressions,
    )
    is HomeDiaryRoute.SceneResponse -> HomeDiaryRoute.CompleteWriting(
        route.chapter,
        route.focusSentenceId,
        route.baseExplanationZh,
        route.expressions,
    )
    else -> null
}
