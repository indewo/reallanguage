package com.reallanguage.app.core.vocabulary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VocabularyCatalogTest {
    @Test fun normalizesInputDeterministicallyWithoutNetwork() {
        assertEquals("studied at the library", normalizeVocabularyForm("  Studied\u00a0At  The Library. "))
    }

    @Test fun resolvesOnlyOneSupportedSenseAndDoesNotGuessAmbiguity() {
        val senses = listOf(
            sense("s1", "学习;研究"),
            sense("s2", "书房"),
        )
        assertEquals("s1", senses.bestMeaningMatch("学习、研究")?.senseId)
        assertNull(listOf(sense("s1", "学习;研究"), sense("s2", "学习、研究")).bestMeaningMatch("学习研究"))
    }

    private fun sense(id: String, meaning: String) = VocabularyCatalogSense(
        senseId = id,
        wordId = "word",
        display = "study",
        normalizedWord = "study",
        initial = "S",
        itemType = "单词",
        partOfSpeech = "v.",
        meaningZh = meaning,
        phonetic = null,
        pools = setOf(VocabularyPool.CET_4),
        matchKind = VocabularyMatchKind.EXACT,
    )
}
