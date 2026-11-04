package com.reallanguage.app.feature.knowledge.presentation

import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.VocabularyBookRepository
import com.reallanguage.app.feature.knowledge.domain.VocabularyExample
import com.reallanguage.app.feature.knowledge.domain.VocabularyLoadResult
import com.reallanguage.app.feature.knowledge.domain.VocabularySenseRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VocabularyMeaningDetailTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun selectedMeaningExposesFullInlineDetailWithExactlyThreeExamples() = runTest(dispatcher) {
        val viewModel = VocabularyBookViewModel(Repository)
        advanceUntilIdle()
        viewModel.onAction(VocabularyBookAction.SenseSelected("run-business"))
        val detail = viewModel.uiState.value.expandedSense!!
        assertEquals("/rʌn/", detail.phonetic)
        assertEquals("经营一家社区咖啡店", detail.representativeScene)
        assertEquals(3, detail.examples.size)
        assertEquals(4, detail.focusOccurrenceCount)
        assertEquals(7, detail.contextOccurrenceCount)
    }

    @Test fun pronunciationUsesOnlyTheSelectedMeaningReliableAudio() = runTest(dispatcher) {
        val viewModel = VocabularyBookViewModel(Repository)
        advanceUntilIdle()
        viewModel.onAction(VocabularyBookAction.SenseSelected("run-business"))
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(VocabularyBookAction.PronunciationRequested)
        advanceUntilIdle()
        assertEquals(VocabularyBookEffect.PlayPronunciation("run"), effect.await())
    }

    @Test fun collapseRemovesDetailWithoutRemovingVocabularyRecord() = runTest(dispatcher) {
        val viewModel = VocabularyBookViewModel(Repository)
        advanceUntilIdle()
        viewModel.onAction(VocabularyBookAction.SenseSelected("run-business"))
        viewModel.onAction(VocabularyBookAction.CollapseRequested)
        assertNull(viewModel.uiState.value.expandedSense)
        assertEquals(1, viewModel.uiState.value.entries.size)
    }

    private object Repository : VocabularyBookRepository {
        override suspend fun loadAllRecords() = VocabularyLoadResult.Ready(setOf("CET4"), listOf(detail))
    }

    companion object {
        private val detail = VocabularySenseRecord(
            "run-business", "run", "经营业务", KnowledgeStatus.MASTERED, 2,
            setOf("CET4"), 1, "/rʌn/", "run", "经营一家社区咖啡店",
            listOf(
                VocabularyExample("She runs a cafe.", "她经营一家咖啡店。", "e1"),
                VocabularyExample("They run the shop together.", "他们一起经营这家店。", "e2"),
                VocabularyExample("He runs a small business.", "他经营一家小企业。", "e3"),
            ), 4, 7,
        )
    }
}
