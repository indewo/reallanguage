package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.feature.learning.domain.PatternPracticeExample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure identity projection only: no database, coroutine scheduler, repository, provider or generated mastery. */
class PatternSpeakingRemovedProgressTest {
    private val a = LockedSpeakingPattern("a", "s", "used to + verb", "过去习惯")
    private val b = LockedSpeakingPattern("b", "s", "prefer to + verb", "现在的偏好")
    private val c = LockedSpeakingPattern("c", "s", "would rather + verb", "更愿意")
    private val removed = LockedSpeakingPattern("removed", "s", "for the first time", "第一次")
    private val chapter = FixedLearningChapter("chapter", 1, listOf(FixedLearningSentence(
        "s", "I used to walk, prefer to read, and would rather stay home for the first time.",
        "隔离恢复夹具。", true,
    )))

    @Test fun legacySurvivingCurrentKeepsDeliveredResponsesAndTheOriginalProcessedPrefix() {
        val oldOrder = listOf(removed, b, c, a)
        val saved = saved(version = 1, order = oldOrder, currentId = c.patternId)
        val current = current(listOf(a, c, b))

        val result = projectWithoutMutatingInputs(current, saved, listOf(removed))

        assertPreservedPractice(result, current, saved, c)
        assertEquals(setOf(b.patternId), result.processedPatternIds)
        assertFalse("A before the new current index is not automatically processed", a.patternId in result.processedPatternIds)
    }

    @Test fun versionTwoSurvivingCurrentKeepsPartialReadingAndNonPrefixProcessedIds() {
        val saved = saved(2, listOf(a, c, removed, b), c.patternId,
            processed = setOf(removed.patternId, b.patternId), stage = PatternSpeakingStage.READING)
        val current = current(listOf(a, b, c))

        val result = projectWithoutMutatingInputs(current, saved, listOf(removed))

        assertPreservedPractice(result, current, saved, c)
        assertEquals(setOf(b.patternId), result.processedPatternIds)
        assertEquals(setOf("${c.patternId}-accepted-0"), result.completedReadingExampleIds)
        assertFalse(result.completed)
    }

    @Test fun removedCurrentMovesToFirstUnprocessedLiveTargetWithoutBorrowingItsExamples() {
        for (version in listOf(1, 2)) {
            val saved = saved(version, listOf(b, removed, a, c), removed.patternId, processed = setOf(b.patternId))
            val current = current(listOf(a, b, c))

            val result = projectWithoutMutatingInputs(current, saved, listOf(removed))

            assertEquals(a, result.currentPattern)
            assertEquals(0, result.patternIndex)
            assertEquals(setOf(b.patternId), result.processedPatternIds)
            assertFalse(result.completed)
            assertDiscardedPractice(result)
            assertEquals(PatternSpeakingStage.LOADING, result.stage)
            assertTrue("Existing RetryLoadingRequested must remain actionable; projection never starts generation", result.failureMessageVisible)
            assertTrue("Audio preference belongs to the user, not to the removed examples", result.audioMuted)
        }
    }

    @Test fun removingCurrentCompletesOnlyWhenEverySurvivingTargetWasAlreadyProcessed() {
        for (version in listOf(1, 2)) {
            val order = if (version == 1) listOf(a, b, removed) else listOf(a, removed, b)
            val saved = saved(version, order, removed.patternId, processed = setOf(a.patternId, b.patternId))

            val result = projectWithoutMutatingInputs(current(listOf(b, a)), saved, listOf(removed))

            assertTrue(result.completed)
            assertEquals(setOf(a.patternId, b.patternId), result.processedPatternIds)
            assertFalse("Removal does not create a processed or mastered result for the removed identity",
                removed.patternId in result.processedPatternIds)
            assertDiscardedPractice(result)
        }
    }

    @Test fun allRemovedCreatesACompletedEmptyPlanThatCanBeReopenedWithoutRevivingAnything() {
        for (version in listOf(1, 2)) {
            val current = current(emptyList())
            val old = saved(version, listOf(removed), removed.patternId)

            val result = projectWithoutMutatingInputs(current, old, listOf(removed))

            assertTrue(result.completed)
            assertEquals(emptyList<LockedSpeakingPattern>(), result.lockedPatterns)
            assertNull(result.currentPattern)
            assertEquals(emptySet<String>(), result.processedPatternIds)
            assertDiscardedPractice(result)
            val projected = encodePatternProgress(result)
            assertEquals("2", projected["version"])
            assertEquals("0", projected["patternCount"])
            assertEquals("0", projected["processedPatternCount"])
            assertEquals(result, projectWithoutMutatingInputs(current, projected, listOf(removed)))
        }
    }

    @Test fun reentryAcceptsAlreadyProjectedStateWhileTheDatabaseStillSuppliesTheSameRemovalReceipt() {
        val current = current(listOf(a, b))
        val old = saved(2, listOf(removed, a, b), b.patternId, processed = setOf(removed.patternId, a.patternId))
        val first = projectWithoutMutatingInputs(current, old, listOf(removed))
        val projected = encodePatternProgress(first)

        assertFalse(projected.values.contains(removed.patternId))
        assertEquals(first, projectWithoutMutatingInputs(current, projected, listOf(removed)))
        assertEquals(first, projectWithoutMutatingInputs(current, projected, emptyList()))
    }

    @Test fun noDeletionKeepsTheExistingStrictRestoreContractForBothVersions() {
        val current = current(listOf(b, a, c))
        for (version in listOf(1, 2)) {
            val saved = saved(version, listOf(a, b, c), b.patternId, processed = setOf(a.patternId))
            assertEquals(restorePatternProgress(current, saved), projectWithoutMutatingInputs(current, saved, emptyList()))
        }
    }

    @Test fun aMissingTargetCannotAuthorizeItsOwnRemovalThroughTheSavedPayload() {
        val saved = saved(2, listOf(a, removed, b), b.patternId, processed = setOf(a.patternId, removed.patternId))
        assertRejected(current(listOf(a, b)), saved, emptyList())
        assertRejected(current(listOf(a)), saved, listOf(removed))
        // Even an apparently complete old plan cannot turn a different unproven missing target into a deletion.
        val completed = saved + mapOf("completed" to "true", "processedPatternCount" to "3", "processedPattern.2.id" to b.patternId)
        assertRejected(current(listOf(a)), completed, listOf(removed))
    }

    @Test fun removedAndSurvivingSavedIdentitiesMustMatchTheTrustedCompleteIdentityExactly() {
        val original = saved(2, listOf(a, removed, b), b.patternId, processed = setOf(a.patternId, removed.patternId))
        val initial = current(listOf(a, b))
        for (key in listOf("pattern.1.source", "pattern.1.structure", "pattern.1.explanation",
            "pattern.0.source", "pattern.0.structure", "pattern.0.explanation")) {
            assertRejected(initial, original + (key to "changed:${original.getValue(key)}"), listOf(removed))
        }
        for (wrong in listOf(removed.copy(sourceSentenceId = "other-sentence"),
            removed.copy(structure = removed.structure + " "), removed.copy(explanationZh = "另一个词义"))) {
            assertRejected(initial, original, listOf(wrong))
        }
        assertRejected(current(listOf(a, b.copy(explanationZh = "更改的幸存词义"))), original, listOf(removed))
    }

    @Test fun duplicateRemovedIdentityIsRejectedEvenWhenThePayloadHasAlreadyBeenProjected() {
        val saved = saved(2, listOf(a, b), b.patternId, processed = setOf(a.patternId))
        assertRejected(current(listOf(a, b)), saved, listOf(removed, removed))
    }

    @Test fun oneIdentityCannotBeBothCurrentAndRemovedEvenForAnOtherwiseValidSnapshot() {
        val saved = saved(2, listOf(a, b), b.patternId, processed = setOf(a.patternId))
        assertRejected(current(listOf(a, b)), saved, listOf(a))
    }

    @Test fun trustedRemovalIdentityMustStillBeCompleteAndBelongToThisFocus() {
        val saved = saved(2, listOf(a, b), b.patternId, processed = setOf(a.patternId))
        for (invalid in listOf(removed.copy(sourceSentenceId = "another-focus"),
            removed.copy(patternId = " "), removed.copy(structure = ""), removed.copy(explanationZh = " "))) {
            assertRejected(current(listOf(a, b)), saved, listOf(invalid))
        }
    }

    @Test fun deletingCurrentCannotWashAwayCorruptProcessedIdsOrInventedCompletion() {
        val valid = saved(2, listOf(a, removed, b), removed.patternId, processed = setOf(a.patternId))
        val invalid = listOf(
            valid + ("processedPattern.0.id" to removed.patternId),
            valid + ("processedPattern.0.id" to "unknown"),
            valid + mapOf("processedPatternCount" to "2", "processedPattern.1.id" to a.patternId),
            valid + ("completed" to "true"),
            valid - "processedPatternCount",
            valid + ("version" to "999"),
            valid + ("pattern.2.id" to a.patternId),
        )
        invalid.forEach { assertRejected(current(listOf(a, b)), it, listOf(removed)) }
    }

    @Test fun allRemovedStillValidatesTheOldSnapshotInsteadOfBlindlyReturningComplete() {
        val valid = saved(2, listOf(removed), removed.patternId)
        for (invalid in listOf(valid + ("pattern.0.explanation" to "未确认的说明"),
            valid + ("example.0.id" to valid.getValue("example.1.id")),
            valid + ("example.0.en" to ""), valid + ("responseIndex" to "99"))) {
            assertRejected(current(emptyList()), invalid, listOf(removed))
        }
        val empty = encodePatternProgress(current(emptyList()).copy(completed = true))
        assertRejected(current(emptyList()), empty + ("completed" to "false"), listOf(removed))
        assertRejected(current(emptyList()), empty + ("processedPatternCount" to "1") + ("processedPattern.0.id" to removed.patternId), listOf(removed))
    }

    private fun current(patterns: List<LockedSpeakingPattern>) = PatternSpeakingUiState(chapter, "s", patterns)

    /** Construct the historical wire map explicitly, independently of the current v2 encoder. */
    private fun saved(
        version: Int,
        order: List<LockedSpeakingPattern>,
        currentId: String,
        processed: Set<String> = emptySet(),
        stage: PatternSpeakingStage = PatternSpeakingStage.RESPONDING,
    ): Map<String, String> = buildMap {
        put("version", version.toString())
        put("patternCount", order.size.toString())
        order.forEachIndexed { index, pattern ->
            put("pattern.$index.id", pattern.patternId)
            put("pattern.$index.source", pattern.sourceSentenceId)
            put("pattern.$index.structure", pattern.structure)
            put("pattern.$index.explanation", pattern.explanationZh)
        }
        put("patternIndex", order.indexOfFirst { it.patternId == currentId }.also { require(it >= 0) }.toString())
        if (version == 2) {
            val ordered = order.map { it.patternId }.filter { it in processed }
            require(ordered.toSet() == processed)
            put("processedPatternCount", ordered.size.toString())
            ordered.forEachIndexed { index, id -> put("processedPattern.$index.id", id) }
        }
        put("stage", stage.name)
        put("exampleCount", "2")
        acceptedExamples(currentId).forEachIndexed { index, example ->
            put("example.$index.id", example.exampleId)
            put("example.$index.scene", example.sceneZh)
            put("example.$index.prompt", example.aiPromptEn)
            put("example.$index.en", example.targetResponseEn)
            put("example.$index.zh", example.targetResponseZh)
            put("example.$index.read", (stage == PatternSpeakingStage.RESPONDING || index == 0).toString())
        }
        put("responseIndex", if (stage == PatternSpeakingStage.RESPONDING) "1" else "0")
        put("transcript", "已保存且属于 $currentId 的回应")
        put("feedback", "已保存且属于 $currentId 的反馈")
        put("repeat", "true")
        put("responseCompleted", (stage == PatternSpeakingStage.RESPONDING).toString())
        put("muted", "true")
        put("completed", "false")
    }

    private fun acceptedExamples(id: String) = List(2) { index -> PatternPracticeExample(
        "$id-accepted-$index", "$id 已交付场景 $index", "What do you prefer?",
        "I prefer to read.", "我更喜欢阅读。",
    ) }

    private fun projectWithoutMutatingInputs(
        current: PatternSpeakingUiState,
        saved: Map<String, String>,
        removed: List<LockedSpeakingPattern>,
    ): PatternSpeakingUiState {
        val oldSaved = saved.toMap()
        val oldCurrent = current.copy(lockedPatterns = current.lockedPatterns.toList())
        val oldRemoved = removed.toList()
        return try { projectPatternProgress(current, saved, removed) } finally {
            assertEquals("Never rewrite accepted progress in place", oldSaved, saved)
            assertEquals(oldCurrent, current)
            assertEquals(oldRemoved, removed)
        }
    }

    private fun assertPreservedPractice(
        result: PatternSpeakingUiState,
        current: PatternSpeakingUiState,
        saved: Map<String, String>,
        target: LockedSpeakingPattern,
    ) {
        assertEquals(current.lockedPatterns, result.lockedPatterns)
        assertEquals(target, result.currentPattern)
        assertEquals(current.lockedPatterns.indexOf(target), result.patternIndex)
        assertEquals(PatternSpeakingStage.valueOf(saved.getValue("stage")), result.stage)
        assertEquals(acceptedExamples(target.patternId), result.examples)
        assertEquals(result.examples.filterIndexed { index, _ -> saved.getValue("example.$index.read").toBooleanStrict() }
            .map { it.exampleId }.toSet(), result.completedReadingExampleIds)
        assertEquals(saved.getValue("responseIndex").toInt(), result.responseIndex)
        assertEquals(saved["transcript"], result.transcript)
        assertEquals(saved["feedback"], result.feedbackZh)
        assertEquals(saved.getValue("responseCompleted").toBooleanStrict(), result.currentResponseCompleted)
        assertTrue(result.repeatRecommended)
        assertTrue(result.audioMuted)
        assertFalse(result.completed)
        assertFalse(result.recording)
        assertNull(result.readingExampleId)
        assertFalse(result.submitting)
        assertFalse(result.progressFailed)
    }

    private fun assertDiscardedPractice(result: PatternSpeakingUiState) {
        assertTrue(result.examples.isEmpty())
        assertTrue(result.completedReadingExampleIds.isEmpty())
        assertEquals(0, result.responseIndex)
        assertEquals("", result.transcript)
        assertNull(result.feedbackZh)
        assertFalse(result.repeatRecommended)
        assertFalse(result.currentResponseCompleted)
        assertFalse(result.recording)
        assertNull(result.readingExampleId)
        assertFalse(result.submitting)
        assertFalse(result.progressFailed)
    }

    private fun assertRejected(current: PatternSpeakingUiState, saved: Map<String, String>, removed: List<LockedSpeakingPattern>) {
        val failure = runCatching { projectWithoutMutatingInputs(current, saved, removed) }.exceptionOrNull()
        assertTrue("Invalid authority or saved identity must be rejected, actual=${failure?.javaClass?.simpleName}",
            failure is IllegalArgumentException || failure is NoSuchElementException)
    }
}
