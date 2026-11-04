package com.reallanguage.app.feature.learning.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class WritingTargetSpanTest {
    private val evaluator = CompleteWritingEvaluator(FixedWritingComparator())

    private fun target(source: String, surface: String, occurrence: Int = source.indexOf(surface)) = LockedWritingTarget(
        "t", "s", WritingTargetType.PATTERN, listOf(surface),
        WritingTargetSpan(occurrence, occurrence + surface.length, surface),
    )
    private fun result(source: String, actual: String, target: LockedWritingTarget) =
        (evaluator.evaluate(listOf(FixedLearningSentence("s", source, "隔离测试句", true)), mapOf("s" to actual), listOf(target), false)
            as FinalWritingBatchResult.Valid).sentenceResults.single()

    @Test fun canonicalUnicodeSpellingMatchesWithoutChangingOriginalOffsetsOrDroppingAccents() {
        val source = "😀 We met at the café."
        val decomposed = "😀 We met at the cafe\u0301."
        assertTrue(result(source, decomposed, target(source, "café")).wholeSentencePassed)
        assertTrue(result(decomposed, source, target(decomposed, "cafe\u0301")).targetResults.single().passed)
        assertFalse(result(source, "😀 We met at the cafe.", target(source, "café")).targetResults.single().passed)
    }

    @Test fun grammarRealizationAndInflectedPhraseAreNotComparedWithDisplayLabels() {
        val source = "I used to live here."
        val result = result(source, "I used to live there.", target(source, "used to live"))
        assertFalse(result.wholeSentencePassed)
        assertTrue(result.targetResults.single().passed)
        val inflected = "She worked up the courage."
        assertTrue(result(inflected, inflected, target(inflected, "worked up the courage")).targetResults.single().passed)
    }
    @Test fun anotherOccurrenceCannotHideAnErrorAtTheLockedOccurrence() {
        val source = "I run the team and run the program."
        assertFalse(result(source, "I lead the team and run the program.", target(source, "run")).targetResults.single().passed)
        assertTrue(result(source, "I lead the team and run the program.", target(source, "run", source.lastIndexOf("run"))).targetResults.single().passed)
    }
    @Test fun insertedWordInsidePhraseFailsButAnUnrelatedWordOutsideDoesNot() {
        val source = "She worked up the courage."
        assertFalse(result(source, "She worked right up the courage.", target(source, "worked up the courage")).targetResults.single().passed)
        assertTrue(result(source, "Yesterday she worked up the courage.", target(source, "worked up the courage")).targetResults.single().passed)
    }
    @Test fun missingOneOfIndistinguishableOccurrencesDoesNotInventAFormalTargetResult() {
        val source = "Run run."
        assertThrows(IllegalStateException::class.java) { result(source, "Run.", target(source, "Run")) }
    }
    @Test fun corruptOrUnboundFrozenSpanCannotCreateAResult() {
        val source = "I used to live here."
        assertThrows(IllegalStateException::class.java) {
            result(source, source, target(source, "used to live").copy(sourceSpan = WritingTargetSpan(3, 15, "used to live")))
        }
    }
    @Test fun duplicatedAnswerOccurrenceIsAmbiguousButIdenticalRepeatedSourceIsNot() {
        val source = "We work today."
        assertThrows(IllegalStateException::class.java) { result(source, "We work work today.", target(source, "work")) }
        val repeated = "I had had enough."
        assertTrue(result(repeated, repeated, target(repeated, "had")).targetResults.single().passed)
    }
    @Test fun movingTargetToTheWrongPartOfTheSentenceDoesNotPassByGlobalSearch() {
        val source = "I drink tea daily."
        assertFalse(result(source, "Daily I drink tea.", target(source, "daily")).targetResults.single().passed)
    }
    @Test fun contractionsAndUtf16OffsetsUseTheSameVersionedNormalizationAsTheWholeSentence() {
        val source = "😀 I cannot run the team."
        assertTrue(result(source, "😀 I can’t run the team.", target(source, "cannot run")).targetResults.single().passed)
        assertFalse(result(source, "😀 I can run the team.", target(source, "cannot run")).targetResults.single().passed)
    }
}
