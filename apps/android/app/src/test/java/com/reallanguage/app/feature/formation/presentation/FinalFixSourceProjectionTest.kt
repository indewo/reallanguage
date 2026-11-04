package com.reallanguage.app.feature.formation.presentation

import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.safety.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FinalFixSourceProjectionTest {
    private val dispatcher = StandardTestDispatcher()
    private val chapters = listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", "我喝茶。", true,
        listOf(EnglishCandidate("e1", "I drink tea."))))))
    private val source = FixedSourceProjection("flow-one", "source-one", 7,
        ConfirmedSource("我喝茶。\n这句原文没有入章。", SourceType.DIARY, SourceContentShape.ARTICLE), "隔离日记",
        listOf(FixedSourceUnit("u1", 0, "我喝茶。"), FixedSourceUnit("outside-chapter", 1, "这句原文没有入章。")),
        listOf("u1"), mapOf("u1" to "e1"), emptySet(), FixedSourceOrigin.Diary)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun completeOriginalSourceIsPassedUnchangedIncludingOutsideChapterUnitsAndRetries() = runTest(dispatcher) {
        val requests = mutableListOf<ContentFixRequest>()
        val viewModel = viewModel(source, ContentFixRepository { request ->
            requests += request
            if (requests.size == 1) ContentFixResult.Failed else ContentFixResult.Fixed("c1")
        })
        repeat(2) { viewModel.onAction(FinalFixAction.ConfirmRequested); advanceUntilIdle() }
        assertEquals(2, requests.size)
        assertEquals(source, requests.first().sourceProjection)
        assertEquals(requests.first(), requests.last())
        assertEquals(2, requests.last().sourceProjection!!.units.size)
        assertEquals("fix-one", requests.last().commandId)
    }

    @Test fun missingOriginalSourceIsNeverInventedByConcatenatingSelectedChapterText() = runTest(dispatcher) {
        var request: ContentFixRequest? = null
        val viewModel = viewModel(null, ContentFixRepository { request = it; ContentFixResult.Failed })
        viewModel.onAction(FinalFixAction.ConfirmRequested); advanceUntilIdle()
        assertNotNull(request)
        assertNull(request!!.sourceProjection)
        assertTrue(viewModel.uiState.value.fixFailed)
    }

    private fun viewModel(source: FixedSourceProjection?, repository: ContentFixRepository) = FinalFixViewModel(
        "flow-one", "fix-one", chapters, mapOf("u1" to "e1"), emptyList(), emptySet(), repository,
        sourceTitle = "隔离日记", sourceProjection = source,
    )
}
