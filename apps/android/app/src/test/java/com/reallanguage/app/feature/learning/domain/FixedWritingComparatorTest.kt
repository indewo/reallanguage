package com.reallanguage.app.feature.learning.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedWritingComparatorTest {
    private val comparator = FixedWritingComparator(
        equivalentForms = mapOf("i'm" to "i am"),
    )

    @Test
    fun equivalentMobileApostrophesAreNormalizedBeforeContractionExpansionOnBothSides() {
        listOf('\u2018', '\u2019', '\u02BC', '\uFF07').forEach { apostrophe ->
            val mobileInput = "I${apostrophe}m ready."
            assertEquals("mobile input $apostrophe", FixedWritingResult.Correct,
                comparator.compare("I am ready.", mobileInput))
            assertEquals("fixed text $apostrophe", FixedWritingResult.Correct,
                comparator.compare(mobileInput, "I'm ready"))
        }
        assertEquals(FixedWritingResult.Correct,
            comparator.compare("She said, “I'm ready.”", "She said, ‘I’m ready.’"))
    }

    @Test
    fun apostropheNormalizationDoesNotForgiveMissingWordsNegationOrWrongOrder() {
        listOf("Im ready", "I ready am", "I’m not ready", "You’re ready", "I′m ready").forEach { input ->
            assertTrue(input, comparator.compare("I'm ready.", input) is FixedWritingResult.Incorrect)
        }
        val defaults = FixedWritingComparator()
        assertTrue(defaults.compare("I can’t wait.", "I can wait.") is FixedWritingResult.Incorrect)
        assertTrue(defaults.compare("We’re ready.", "Were ready.") is FixedWritingResult.Incorrect)
    }

    @Test
    fun lockedTargetMatchingUsesTheSameNormalizationWithoutRelaxingTokenBoundaries() {
        val defaults = FixedWritingComparator()
        assertTrue(defaults.containsExpectedForm("I can’t wait.", "cannot"))
        assertTrue(defaults.containsExpectedForm("I cannot wait.", "can’t"))
        assertFalse(defaults.containsExpectedForm("I can wait.", "can’t"))
        assertFalse(defaults.containsExpectedForm("The scan’t string.", "cannot"))
    }

    @Test
    fun possessiveApostrophesStayDistinctFromOmittedLettersAndAreNotNewContractions() {
        assertEquals(FixedWritingResult.Correct,
            comparator.compare("John's bag is here.", "John’s bag is here."))
        assertTrue(comparator.compare("John's bag is here.", "John bag is here.") is FixedWritingResult.Incorrect)
        assertTrue(comparator.compare("John's bag is here.", "Johns bag is here.") is FixedWritingResult.Incorrect)
    }

    @Test
    fun ignoresCaseWhitespaceAndFixedPunctuation() {
        assertTrue(
            comparator.compare(
                expected = "I expressed myself more clearly.",
                actual = "  i   EXPRESSED myself more clearly  ",
            ) is FixedWritingResult.Correct,
        )
    }

    @Test
    fun appliesOnlyTheVersionedEquivalentFormsProvidedByBusinessCode() {
        assertTrue(
            comparator.compare("I am ready.", "I'm ready") is FixedWritingResult.Correct,
        )
    }

    @Test
    fun reportsMissingExtraChangedAndReorderedTokensDeterministically() {
        val result = comparator.compare(
            expected = "I expressed myself more clearly.",
            actual = "Clearly I express myself today.",
        ) as FixedWritingResult.Incorrect
        assertEquals(listOf("expressed", "more"), result.missingTokens)
        assertEquals(listOf("express", "today"), result.extraTokens)
        assertTrue(result.orderChanged)
    }
}
