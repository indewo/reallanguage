package com.reallanguage.app.core.app

import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningBackNavigationTest {
    private val chapter = FixedLearningChapter(
        chapterId = "chapter-1",
        chapterNumber = 1,
        sentences = listOf(
            FixedLearningSentence(
                sentenceId = "sentence-1",
                textEn = "It's a beautiful day today.",
                textZh = "今天是美好的一天。",
                isFocus = true,
            ),
        ),
    )
    private val focus = "sentence-1"
    private val explanation = "基础讲解"
    private val expressions = emptyList<com.reallanguage.app.feature.learning.domain.SelectableExpression>()

    @Test
    fun everyOrdinaryLearningBackRouteReturnsToTheImmediatelyPreviousNode() {
        val routes = listOf(
            HomeDiaryRoute.ChapterExplanation(chapter, focus) to HomeDiaryRoute.ChapterLearning::class,
            HomeDiaryRoute.ExplanationReading(chapter, focus, explanation, expressions) to HomeDiaryRoute.ChapterLearning::class,
            HomeDiaryRoute.ReadSpeak(chapter, focus, explanation, expressions) to HomeDiaryRoute.ExplanationReading::class,
            HomeDiaryRoute.Handwriting(chapter, focus, explanation, expressions) to HomeDiaryRoute.ReadSpeak::class,
            HomeDiaryRoute.AudioDictation(chapter, focus, explanation, expressions) to HomeDiaryRoute.Handwriting::class,
            HomeDiaryRoute.ReadWriteCorrection(chapter, focus, explanation, expressions, "draft") to HomeDiaryRoute.AudioDictation::class,
            HomeDiaryRoute.FullSentenceSpeaking(chapter, focus, explanation, expressions) to HomeDiaryRoute.AudioDictation::class,
            HomeDiaryRoute.PatternSpeaking(chapter, focus, explanation, expressions) to HomeDiaryRoute.FullSentenceSpeaking::class,
            HomeDiaryRoute.CompleteWriting(chapter, focus, explanation, expressions) to HomeDiaryRoute.PatternSpeaking::class,
            HomeDiaryRoute.SceneResponse(chapter, focus, explanation, expressions) to HomeDiaryRoute.CompleteWriting::class,
        )

        routes.forEach { (current, expectedType) ->
            val previous = requireNotNull(learningPreviousRoute(current))
            assertTrue("$current must return to $expectedType, not ${previous::class}", expectedType.isInstance(previous))
            assertTrue("Back must never detour through the completed-step archive", previous !is HomeDiaryRoute.CompletedStepReview)
        }
    }

    @Test
    fun correctionBackPreservesTheUsersDictationDraft() {
        val previous = learningPreviousRoute(
            HomeDiaryRoute.ReadWriteCorrection(chapter, focus, explanation, expressions, "kept draft"),
        ) as HomeDiaryRoute.AudioDictation

        assertEquals("kept draft", previous.input)
    }

    @Test
    fun chapterRootBackKeepsTheActualEntryNode() {
        val source = HomeDiaryRoute.ChapterSelection(
            com.reallanguage.app.feature.learning.domain.ChapterSelectionBoundary.ArchivedChapter("chapter-0"),
        )
        val previous = learningPreviousRoute(
            HomeDiaryRoute.ExplanationReading(chapter, focus, explanation, expressions),
            chapterReturnRoute = source,
        ) as HomeDiaryRoute.ChapterLearning

        assertEquals(source, previous.returnRoute)
        assertEquals(source, contextualPreviousRoute(previous))
    }
}
