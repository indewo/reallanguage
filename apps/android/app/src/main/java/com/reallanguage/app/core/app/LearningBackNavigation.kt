package com.reallanguage.app.core.app

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
