package com.reallanguage.app.core.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.knowledge.domain.*
import com.reallanguage.app.feature.knowledge.presentation.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class KnowledgeBookEntryTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun vocabularyReentryReadsCurrentScopeAndLatestRecords() {
        val visible = mutableStateOf(true)
        var loads = 0
        val repository = object : VocabularyBookRepository {
            override suspend fun loadAllRecords(): VocabularyLoadResult {
                loads++
                return VocabularyLoadResult.Ready(setOf("CET4"), listOf(VocabularySenseRecord(
                    "sense-$loads", "word", "词义 $loads", KnowledgeStatus.LEARNING, 0, setOf("CET4"), 1)))
            }
        }
        val factory = factory { VocabularyBookViewModel(repository) }
        composeRule.setContent { RlTheme {
            if (visible.value) VocabularyBookEntry(factory, {}, { true })
        } }
        composeRule.onNodeWithTag("vocabulary-sense-sense-1").assertIsDisplayed()
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { visible.value = true }
        composeRule.waitForIdle()
        assertEquals(2, loads)
        composeRule.onNodeWithTag("vocabulary-sense-sense-2").assertIsDisplayed()
    }

    @Test fun patternReentryDoesNotKeepPreviousVisitSnapshot() {
        val visible = mutableStateOf(true)
        var loads = 0
        val repository = object : PatternBookRepository {
            override suspend fun loadAllPatterns(): PatternLoadResult {
                loads++
                return PatternLoadResult.Ready(listOf(PatternRecord("pattern-$loads", "used to", "过去习惯",
                    KnowledgeStatus.LEARNING, KnowledgeStatus.LEARNING, 0, 1)))
            }
        }
        val factory = factory { PatternBookViewModel(repository) }
        composeRule.setContent { RlTheme {
            if (visible.value) PatternBookEntry(factory, { _, _ -> error("No removal requested") }, {}, { true })
        } }
        composeRule.onNodeWithTag("pattern-row-pattern-1").assertIsDisplayed()
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { visible.value = true }
        composeRule.waitForIdle()
        assertEquals(2, loads)
        composeRule.onNodeWithTag("pattern-row-pattern-2").assertIsDisplayed()
    }

    private fun factory(create: () -> ViewModel) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }

    @Test fun laterRemovalUsesANewConfirmationSessionAndCommand() {
        val visible = mutableStateOf(true)
        var available = true
        val commands = mutableListOf<String>()
        val repository = object : PatternBookRepository {
            override suspend fun loadAllPatterns(): PatternLoadResult = PatternLoadResult.Ready(
                if (available) listOf(PatternRecord("legacy-pattern", "used to", "过去习惯",
                    KnowledgeStatus.LEARNING, KnowledgeStatus.LEARNING, 0, 1)) else emptyList())
        }
        val removal = object : PatternRemovalRepository {
            override suspend fun removeFromPatternBook(patternId: String, commandId: String): PatternRemovalResult {
                commands += commandId
                available = false
                return PatternRemovalResult.Removed
            }
        }
        val factory = factory { PatternBookViewModel(repository) }
        composeRule.setContent { RlTheme {
            if (visible.value) PatternBookEntry(factory,
                { id, command -> factory { PatternRemovalViewModel(id, command, removal) } }, {}, { true })
        } }
        fun confirmRemoval() {
            composeRule.onNodeWithTag("pattern-row-legacy-pattern").performClick()
            composeRule.onNodeWithTag("pattern-detail-remove").performClick()
            composeRule.onNodeWithTag("pattern-removal-confirm").performClick()
        }
        confirmRemoval()
        composeRule.waitUntil { commands.size == 1 }
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { available = true; visible.value = true }
        confirmRemoval()
        composeRule.waitUntil { commands.size == 2 }
        org.junit.Assert.assertNotEquals(commands[0], commands[1])
    }
}
