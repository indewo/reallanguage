package com.reallanguage.app.core.vocabulary

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.knowledge.data.CurrentVocabularyScopeSource
import com.reallanguage.app.feature.knowledge.data.LocalFirstVocabularyScopeSource
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidEmbeddedVocabularyCatalogTest {
    @Test fun embeddedCatalogOpensAndFindsExactAndInflectedFormsOffline() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = AndroidEmbeddedVocabularyCatalog(context)

        assertEquals(13_689, catalog.metadata().wordCount)
        assertEquals(28_578, catalog.metadata().senseCount)
        assertTrue(catalog.find("library", VocabularyPool.TOEFL).any { it.meaningZh == "图书馆" })
        assertTrue(catalog.find("studied", VocabularyPool.CET_4).any { it.normalizedWord == "study" })
        assertTrue(catalog.find("went", VocabularyPool.JUNIOR_HIGH).any { it.normalizedWord == "go" })
        assertTrue(catalog.find("Can you hear what I'm saying right now?").isEmpty())
    }

    @Test fun poolBrowseUsesStableAlphabeticalPageBoundaries() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = AndroidEmbeddedVocabularyCatalog(context)
        val first = catalog.browse(VocabularyPool.CET_6, 'S', limit = 25)
        val second = catalog.browse(VocabularyPool.CET_6, 'S', limit = 25, offset = 25)

        assertEquals(25, first.size)
        assertEquals(25, second.size)
        assertTrue(first.map { it.senseId }.toSet().intersect(second.map { it.senseId }.toSet()).isEmpty())
        assertTrue((first + second).zipWithNext().all { (left, right) ->
            left.normalizedWord <= right.normalizedWord
        })
    }

    @Test fun fullPoolPagesKeepAllMeaningsTogetherAndSearchByIndexedPrefix() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = AndroidEmbeddedVocabularyCatalog(context)
        val firstPage = catalog.browsePage(VocabularyPool.CET_4, limit = 200)
        val secondPage = catalog.browsePage(VocabularyPool.CET_4, limit = 200, offset = 200)

        assertEquals(200, firstPage.map { it.normalizedWord }.distinct().size)
        assertEquals(200, secondPage.map { it.normalizedWord }.distinct().size)
        assertTrue(firstPage.last().normalizedWord < secondPage.first().normalizedWord)
        val study = catalog.browsePage(VocabularyPool.CET_4, query = "stud", limit = 200)
        assertTrue(study.isNotEmpty())
        assertTrue(study.all { it.normalizedWord.startsWith("stud") })
        assertEquals(setOf("n.", "vi.", "vt."), study.filter { it.normalizedWord == "study" }.mapTo(linkedSetOf()) { it.partOfSpeech })
    }

    @Test fun initialJumpStartsAtRequestedLetterAndKeepsLaterLettersSorted() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = AndroidEmbeddedVocabularyCatalog(context)
        val page = catalog.browsePageFromInitial(VocabularyPool.CET_6, 'R', limit = 200)

        assertTrue(page.isNotEmpty())
        assertTrue(page.all { it.initial >= "R" })
        assertTrue(page.zipWithNext().all { (left, right) -> left.normalizedWord <= right.normalizedWord })
        assertTrue(page.map { it.normalizedWord }.distinct().size <= 200)
    }

    @Test fun selectedPoolIsAvailableLocallyWithoutCallingTheNetworkFallback() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accountId = "catalog-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("local-test", accountId) }
        val preference = AndroidVocabularyPoolPreference(context)
        var fallbackCalls = 0
        val source = LocalFirstVocabularyScopeSource(
            runtime,
            preference,
            CurrentVocabularyScopeSource {
                fallbackCalls += 1
                setOf(VocabularyPool.GRE.name)
            },
        )
        try {
            assertTrue(preference.write(accountId, VocabularyPool.CET_6.name))
            assertEquals(setOf(VocabularyPool.CET_6.name), source.load())
            assertEquals(0, fallbackCalls)

            assertTrue(preference.clear(accountId))
            assertEquals(setOf(VocabularyPool.GRE.name), source.load())
            assertEquals(1, fallbackCalls)
            assertEquals(VocabularyPool.GRE, preference.read(accountId))
        } finally {
            preference.clear(accountId)
        }
    }
}
