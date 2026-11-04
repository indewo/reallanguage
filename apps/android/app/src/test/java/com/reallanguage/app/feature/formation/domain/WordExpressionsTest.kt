package com.reallanguage.app.feature.formation.domain

import org.junit.Assert.*
import org.junit.Test

class WordExpressionsTest {
    @Test fun `every supported mobile apostrophe produces whole contractions at their original offsets`() {
        listOf('\'', '\u2018', '\u2019', '\u02BC', '\uFF07').forEach { apostrophe ->
            val text = "😀 I${apostrophe}m ready; we${apostrophe}re here and can${apostrophe}t wait."
            val words = sentence(text).wordExpressions("chosen")
            val surfaces = listOf("I${apostrophe}m", "ready", "we${apostrophe}re", "here", "and", "can${apostrophe}t", "wait")
            assertEquals("whole words $apostrophe", surfaces, words.map { it.display })
            assertEquals("first UTF16 offset $apostrophe", "word:3", words.first().expressionId)
            assertEquals(surfaces.map { "word:${text.indexOf(it)}" }, words.map { it.expressionId })
            assertTrue(words.all { it.unitId == "sentence" })
            words.forEach { word ->
                val start = word.expressionId.removePrefix("word:").toInt()
                assertTrue(SourceEvidence(1, start, start + word.display.length, word.display, word.display,
                    listOf(word.display)).isValidForTarget(text, word.display, LearningTargetType.WORD))
            }
        }
    }

    @Test fun `compound emoji Unicode marks digits and hyphens keep complete source token boundaries`() {
        val text = "👩‍💻 I’m re-entering café at 2pm."
        val words = sentence(text).wordExpressions("chosen")
        val surfaces = listOf("I’m", "re-entering", "café", "at", "2pm")
        assertEquals(surfaces, words.map { it.display })
        assertEquals("word:6", words.first().expressionId)
        assertEquals(surfaces.map { "word:${text.indexOf(it)}" }, words.map { it.expressionId })
        assertFalse(words.any { it.display in listOf("I", "m", "re", "entering", "cafe", "pm") })
    }

    @Test fun `word mapping never falls back to a different candidate or an unknown selection`() {
        val chosen = sentence("I’m ready.").copy(candidates = listOf(
            EnglishCandidate("other", "Different content."),
            EnglishCandidate("chosen", "I’m ready."),
        ))
        assertEquals(listOf("I’m", "ready"), chosen.wordExpressions("chosen").map { it.display })
        assertTrue(chosen.wordExpressions("missing").isEmpty())
        assertTrue(chosen.wordExpressions(null).isEmpty())
    }

    private fun sentence(text: String) = EnglishSentenceDraft(
        "sentence", "隔离分词样本", true, listOf(EnglishCandidate("chosen", text)),
    )
}
