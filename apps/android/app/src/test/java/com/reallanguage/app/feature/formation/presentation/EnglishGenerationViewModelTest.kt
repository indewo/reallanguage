package com.reallanguage.app.feature.formation.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.formation.domain.ChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishCandidate
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishGenerationRepository
import com.reallanguage.app.feature.formation.domain.EnglishGenerationResult
import com.reallanguage.app.feature.formation.domain.EnglishSentenceDraft
import com.reallanguage.app.feature.formation.domain.SemanticUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class EnglishGenerationViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private val units = listOf(SemanticUnit("u1", 0, "今天很好。"))
    private val chapterDrafts = listOf(ChapterDraft("c1", listOf("u1")))
    private val generated = listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", "今天很好。", true, listOf(EnglishCandidate("e1", "Today was good."))))))

    @Test fun `starts generation and opens confirmation only on success`() = runTest {
        val effect = async { EnglishGenerationViewModel(units, listOf("u1"), chapterDrafts, EnglishGenerationRepository { _, _, _ -> EnglishGenerationResult.Generated(generated) }).effects.first() }
        assertEquals(EnglishGenerationEffect.OpenConfirmation(generated), effect.await())
    }
    @Test fun `failure keeps source and allows stable retry`() = runTest {
        var calls = 0
        val viewModel = EnglishGenerationViewModel(units, listOf("u1"), chapterDrafts, EnglishGenerationRepository { _, _, _ -> calls++; EnglishGenerationResult.Failed })
        dispatcher.dispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.processing)
        assertEquals(true, viewModel.uiState.value.failed)
        viewModel.onAction(EnglishGenerationAction.RetryRequested)
        dispatcher.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, calls)
    }
}
