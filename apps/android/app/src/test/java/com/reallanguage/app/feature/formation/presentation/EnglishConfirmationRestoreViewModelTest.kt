package com.reallanguage.app.feature.formation.presentation

import com.reallanguage.app.feature.formation.domain.*
import org.junit.Assert.*
import org.junit.Test

class EnglishConfirmationRestoreViewModelTest {
    @Test fun preservesTheUsersValidMultiCandidateChoiceWhenReturningFromTargets() {
        val state = EnglishConfirmationViewModel(chapters, mapOf("u1" to "option-b", "u2" to "single")).uiState.value
        assertEquals(mapOf("u1" to "option-b", "u2" to "single"), state.selectedCandidateIds)
        assertTrue(state.continueEnabled)
        assertFalse(state.submitting)
    }

    @Test fun neverGuessesADeletedOrForeignCandidateButStillUsesTheOnlyQualifiedExpression() {
        val state = EnglishConfirmationViewModel(chapters, mapOf("u1" to "deleted", "foreign" to "option-a")).uiState.value
        assertEquals(mapOf("u2" to "single"), state.selectedCandidateIds)
        assertFalse(state.continueEnabled)
    }

    private val chapters = listOf(EnglishChapterDraft("chapter", listOf(
        EnglishSentenceDraft("u1", "中文一。", true, listOf(EnglishCandidate("option-a", "Version A."), EnglishCandidate("option-b", "Version B."))),
        EnglishSentenceDraft("u2", "中文二。", false, listOf(EnglishCandidate("single", "Only version."))),
    )))
}
