package com.reallanguage.app.feature.formation.domain

import org.junit.Assert.*
import org.junit.Test

class KnownKnowledgeSelectionTest {
    private fun entry(id: String, status: Int = 1, count: Long = 0, order: Long = 1, type: KnownKnowledgeType = KnownKnowledgeType.PATTERN, scopes: Set<String> = emptySet(), display: String = "used to + verb", meaning: String = "过去习惯") =
        KnownKnowledgeEntry(KnownKnowledgeCandidate("${type.name}:$id", type, display, meaning), status, count, order, scopes)

    @Test fun `vocabulary is in current scope while every supplied active pattern status remains eligible`() {
        val entries = listOf(entry("outside", type = KnownKnowledgeType.VOCABULARY, scopes = setOf("OTHER")),
            entry("inside", type = KnownKnowledgeType.VOCABULARY, scopes = setOf("DAILY_CORE"))) + (0..3).map { entry("p$it", it) }
        assertEquals(setOf("VOCABULARY:inside", "PATTERN:p0", "PATTERN:p1", "PATTERN:p2", "PATTERN:p3"), KnownKnowledgeSelection().select(entries, setOf("DAILY_CORE")).map { it.ref }.toSet())
    }
    @Test fun `sorts by existing status occurrence and entry order without inventing times`() {
        val entries = listOf(entry("late", 1, 1, 9), entry("many", 1, 4, 1), entry("pending", 0, 50, 99), entry("early", 1, 1, 2), entry("verified", 3))
        assertEquals(listOf("pending", "early", "late", "many", "verified").map { "PATTERN:$it" }, KnownKnowledgeSelection().select(entries, emptySet()).map { it.ref })
    }
    @Test fun `item and unicode budgets keep a priority prefix and never truncate a candidate`() {
        assertEquals(16, KnownKnowledgeSelection().select((1..20).map { entry("p$it", order = it.toLong()) }, emptySet()).size)
        val exact = entry("emoji", display = "😀".repeat(1999), meaning = "中".repeat(2001))
        assertEquals(listOf(exact.candidate), KnownKnowledgeSelection().select(listOf(exact, entry("next")), emptySet()))
        assertEquals(emptyList<KnownKnowledgeCandidate>(), KnownKnowledgeSelection().select(listOf(exact.copy(candidate = exact.candidate.copy(meaningZh = exact.candidate.meaningZh + "超")), entry("next")), emptySet()))
    }
    @Test fun `invalid references statuses counts or duplicates fail instead of fabricating priorities`() {
        val valid = entry("p")
        listOf(listOf(valid, valid), listOf(valid.copy(statusPriority = 9)), listOf(valid.copy(occurrenceCount = -1)), listOf(valid.copy(candidate = valid.candidate.copy(ref = "VOCABULARY:p"))), listOf(valid.copy(candidate = valid.candidate.copy(meaningZh = " ")))).forEach {
            assertTrue(runCatching { KnownKnowledgeSelection().select(it, emptySet()) }.isFailure)
        }
    }
}
