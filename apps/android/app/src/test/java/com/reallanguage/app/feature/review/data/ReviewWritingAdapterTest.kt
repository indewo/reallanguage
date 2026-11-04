package com.reallanguage.app.feature.review.data

import com.reallanguage.app.feature.learning.domain.FinalWritingBatchResult
import com.reallanguage.app.feature.learning.domain.SentenceWritingResult
import com.reallanguage.app.feature.learning.domain.WritingTargetResult
import com.reallanguage.app.feature.learning.domain.WritingTargetType
import com.reallanguage.app.feature.review.domain.ReviewAbility
import com.reallanguage.app.feature.review.domain.ReviewAbilityModule
import com.reallanguage.app.feature.review.domain.ReviewAbilityStatus
import com.reallanguage.app.feature.review.domain.ReviewResultQualification
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReviewWritingAdapterTest {
    private fun ability(module: ReviewAbilityModule, target: String = "t1") =
        ReviewAbility("a1", module, ReviewAbilityStatus.LEARNING, "s1", target)

    @Test fun supportedWritingCarriesItsQualificationRatherThanIndependentFailure() = runTest {
        val adapter = ReviewWritingAdapter()
        adapter.saveFinalBatch("c1", FinalWritingBatchResult.SupportedLearningOnly)
        listOf(ReviewAbilityModule.COMPLETE_WRITING, ReviewAbilityModule.WORD_MEANING, ReviewAbilityModule.PATTERN_WRITING).forEach { module ->
            val draft = requireNotNull(adapter.resultDraft(ability(module), "supported-evidence"))
            assertEquals(ReviewResultQualification.SUPPORTED, draft.qualification)
            assertFalse(draft.passed)
        }
    }

    @Test fun noSavedResultIsNotInventedAsAnIndependentFailure() {
        assertNull(ReviewWritingAdapter().resultDraft(ability(ReviewAbilityModule.COMPLETE_WRITING), "evidence"))
    }

    @Test fun independentTargetsKeepTheirOwnResultEvenWhenTheWholeSentenceFails() = runTest {
        val adapter = ReviewWritingAdapter()
        adapter.saveFinalBatch("c1", FinalWritingBatchResult.Valid(listOf(SentenceWritingResult("s1", false,
            listOf(WritingTargetResult("t1", WritingTargetType.MEANING, true), WritingTargetResult("p1", WritingTargetType.PATTERN, false))))))
        assertFalse(requireNotNull(adapter.resultDraft(ability(ReviewAbilityModule.COMPLETE_WRITING), "e")).passed)
        val meaning = requireNotNull(adapter.resultDraft(ability(ReviewAbilityModule.WORD_MEANING), "e"))
        assertTrue(meaning.passed)
        assertEquals(ReviewResultQualification.INDEPENDENT, meaning.qualification)
        assertFalse(requireNotNull(adapter.resultDraft(ability(ReviewAbilityModule.PATTERN_WRITING, "p1"), "e")).passed)
        assertNull(adapter.resultDraft(ability(ReviewAbilityModule.PATTERN_WRITING, "missing"), "e"))
        assertNull(adapter.resultDraft(ability(ReviewAbilityModule.PATTERN_WRITING, "t1"), "e"))
    }

    @Test fun wrongSentenceNonWritingModuleAndBlankEvidenceCannotBecomeResults() = runTest {
        val adapter = ReviewWritingAdapter()
        adapter.saveFinalBatch("c1", FinalWritingBatchResult.Valid(listOf(SentenceWritingResult("s1", true, emptyList()))))
        assertNull(adapter.resultDraft(ability(ReviewAbilityModule.COMPLETE_WRITING).copy(sentenceId = "other"), "e"))
        assertNull(adapter.resultDraft(ability(ReviewAbilityModule.FULL_SENTENCE_SPEAKING), "e"))
        assertNull(adapter.resultDraft(ability(ReviewAbilityModule.COMPLETE_WRITING), " "))
    }
}
