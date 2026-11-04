package com.reallanguage.app.feature.formation.domain

import org.junit.Assert.*
import org.junit.Test

class SourceEvidenceTest {
    @Test fun `a structural display may bind only the actual original phrase`() {
        val english = "I used to walk home."
        val source = evidence(english, "used to walk", "used to + verb")
        assertTrue(source.isValidFor(english))
        assertFalse(source.copy(allowedForms = listOf("used to walk", "used to run")).isValidFor(english))
        assertFalse(source.copy(allowedForms = listOf("used to + verb")).isValidFor(english))
        assertFalse(source.copy(normalizedForm = "  ").isValidFor(english))
        assertFalse(source.copy(version = 2).isValidFor(english))
    }

    @Test fun `offsets refer to UTF16 and complete Unicode tokens including apostrophes and hyphens`() {
        val english = "😀 I re-entered café at 2pm and can't wait."
        listOf("re-entered", "café", "2pm", "can't").forEach { assertTrue(evidence(english, it).isValidFor(english)) }
        listOf("entered", "cafe", "2", "can").forEach { assertFalse(evidence(english, it).isValidFor(english)) }
        assertFalse(evidence(english, "re-entered").copy(startUtf16 = 4).isValidFor(english))
    }

    @Test fun `all scoring apostrophes preserve one source word and reject contraction fragments`() {
        listOf('\'', '\u2018', '\u2019', '\u02BC', '\uFF07').forEach { apostrophe ->
            val english = "😀 I${apostrophe}m ready."
            assertTrue("whole contraction $apostrophe", evidence(english, "I${apostrophe}m").isValidFor(english))
            assertFalse("first fragment $apostrophe", evidence(english, "I").isValidFor(english))
            assertFalse("last fragment $apostrophe", evidence(english, "m").isValidFor(english))
            assertNull("legacy fragment $apostrophe", uniqueLiteralSourceEvidence(english, "m"))
        }
    }

    @Test fun `out of range empty wrong sentence and incomplete surface are rejected`() {
        val source = evidence("I felt calm.", "calm")
        listOf(source.copy(startUtf16 = -1), source.copy(endUtf16 = 100), source.copy(endUtf16 = source.startUtf16),
            source.copy(surfaceForm = "warm"), source.copy(surfaceForm = ""), source.copy(allowedForms = emptyList()))
            .forEach { assertFalse(it.isValidFor("I felt calm.")) }
        assertFalse(source.isValidFor("She felt calm."))
    }

    @Test fun `legacy evidence accepts only one complete literal occurrence ignoring case`() {
        assertEquals(SourceEvidence(1, 7, 11, "calm", "CALM", listOf("calm")), uniqueLiteralSourceEvidence("I felt calm.", "CALM"))
        assertNull(uniqueLiteralSourceEvidence("I worked.", "work"))
        assertNull(uniqueLiteralSourceEvidence("I used to walk.", "used to + verb"))
        assertNull(uniqueLiteralSourceEvidence("I can’t wait.", "can't"))
        assertNull(uniqueLiteralSourceEvidence("calm CALM", "calm"))
        assertNull(uniqueLiteralSourceEvidence("a a a", "a a"))
        assertNull(uniqueLiteralSourceEvidence("I felt calm.", "  "))
    }

    private fun evidence(text: String, surface: String, normalized: String = surface): SourceEvidence {
        val start = text.indexOf(surface)
        return SourceEvidence(1, start, start + surface.length, surface, normalized, listOf(surface))
    }
}
