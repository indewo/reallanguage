package com.reallanguage.app.core.app

import com.reallanguage.app.feature.learning.domain.LockedWritingTarget
import com.reallanguage.app.feature.learning.domain.WritingTargetType
import com.reallanguage.app.feature.review.domain.ReviewAbilityModule
import com.reallanguage.app.feature.review.domain.ReviewAbility
import com.reallanguage.app.feature.review.domain.ReviewAbilityStatus
import com.reallanguage.app.feature.review.domain.ReviewChapterLoadResult
import com.reallanguage.app.feature.review.domain.ReviewChapterSnapshot
import com.reallanguage.app.feature.review.domain.ReviewSourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReviewWritingTargetsTest {
    private val target = LockedWritingTarget("v1", "s1", WritingTargetType.MEANING, listOf("calm"))
    @Test fun missingReadMissingTargetOrWrongSentenceCannotTurnIntoAnEmptyExercise() {
        listOf(null, emptyList(), listOf(target.copy(sentenceId = "other")), listOf(target.copy(type = WritingTargetType.PATTERN))).forEach {
            assertNull(reviewWritingTargetSelection(ReviewAbilityModule.WORD_MEANING, "s1", "v1", it))
        }
    }
    @Test fun validSelectionRetainsOnlyTheRequestedAbilityAndSentence() {
        assertEquals(listOf(target), reviewWritingTargetSelection(ReviewAbilityModule.WORD_MEANING, "s1", "v1", listOf(target, target.copy(targetId = "v2"))))
    }
    @Test fun wholeSentenceReviewDoesNotRequireOptionalTargets() {
        assertEquals(emptyList<LockedWritingTarget>(), reviewWritingTargetSelection(ReviewAbilityModule.COMPLETE_WRITING, "s1", "", null))
    }

    private val ability = ReviewAbility("q1", ReviewAbilityModule.WORD_MEANING, ReviewAbilityStatus.LEARNING, "s1", "v1")
    private fun chapter(abilities: List<ReviewAbility> = listOf(ability)) = ReviewChapterSnapshot(
        "review", "original", ReviewSourceType.DIARY, "日记", listOf("Context.", "I felt calm."), "I felt calm.", abilities,
    )

    @Test fun entryReadsOnlyTheExactTargetAfterRefreshingTheActualQueue() = runBlocking {
        val calls = mutableListOf<String>()
        assertEquals(listOf(target), loadReviewWritingTargets("review", "original", ability,
            loadChapter = { calls += "queue:$it"; ReviewChapterLoadResult.Ready(chapter()) },
            readTarget = { original, sentence, id -> calls += "target:$original:$sentence:$id"; listOf(target) },
        ))
        assertEquals(listOf("queue:review", "target:original:s1:v1"), calls)
    }

    @Test fun staleOrMismatchedQueueCannotLoadEvenAnOtherwiseValidTarget() = runBlocking {
        val invalid = listOf(
            ReviewChapterLoadResult.Failed,
            ReviewChapterLoadResult.Ready(chapter().copy(reviewChapterId = "other-review")),
            ReviewChapterLoadResult.Ready(chapter().copy(originalChapterId = "other-original")),
            ReviewChapterLoadResult.Ready(chapter(emptyList())),
            ReviewChapterLoadResult.Ready(chapter(listOf(ability.copy(abilityId = "other-queue")))),
            ReviewChapterLoadResult.Ready(chapter(listOf(ability.copy(sentenceId = "other-sentence")))),
            ReviewChapterLoadResult.Ready(chapter(listOf(ability.copy(targetId = "other-target")))),
            ReviewChapterLoadResult.Ready(chapter(listOf(ability.copy(module = ReviewAbilityModule.PATTERN_WRITING)))),
            ReviewChapterLoadResult.Ready(chapter(listOf(ability.copy(requiresReview = false)))),
            ReviewChapterLoadResult.Ready(chapter(listOf(ability, ability))),
        )
        var targetReads = 0
        invalid.forEach { result ->
            assertNull(loadReviewWritingTargets("review", "original", ability, { result }, { _, _, _ -> targetReads++; listOf(target) }))
        }
        assertEquals(0, targetReads)
    }

    @Test fun wholeSentenceEntryStillRequiresTheRealPendingQueueButNeverReadsOptionalTargets() = runBlocking {
        val whole = ability.copy(module = ReviewAbilityModule.COMPLETE_WRITING, targetId = "")
        var targetReads = 0
        val read: suspend (String, String, String) -> List<LockedWritingTarget>? = { _, _, _ -> targetReads++; null }
        assertNull(loadReviewWritingTargets("review", "original", whole, { ReviewChapterLoadResult.Failed }, read))
        assertEquals(emptyList<LockedWritingTarget>(), loadReviewWritingTargets("review", "original", whole,
            { ReviewChapterLoadResult.Ready(chapter(listOf(whole))) }, read))
        assertEquals(0, targetReads)
    }

    @Test fun optionalTargetFailureOrWrongBindingNeverBecomesAnEmptySuccessfulExercise() = runBlocking {
        listOf(null, emptyList(), listOf(target.copy(sentenceId = "other")), listOf(target.copy(type = WritingTargetType.PATTERN)))
            .forEach { locked ->
                assertNull(loadReviewWritingTargets("review", "original", ability,
                    { ReviewChapterLoadResult.Ready(chapter()) }, { _, _, _ -> locked }))
            }
    }
}
