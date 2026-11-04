package com.reallanguage.app.feature.knowledge.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.VocabularyBookRepository
import com.reallanguage.app.feature.knowledge.domain.VocabularyExample
import com.reallanguage.app.feature.knowledge.domain.VocabularyLoadResult
import com.reallanguage.app.feature.knowledge.domain.VocabularySenseRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A narrow unavailable-playback guard, not an assessment of any engine's sound.
 * Same word + distinct saved IPA + identical native text cannot select between
 * those stored pronunciations. Reuse the existing failure/retry state; do not
 * invent a phonetic spelling, play an example instead, or regenerate knowledge.
 * Repository values and completion callbacks are isolated JVM fixtures only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VocabularyAmbiguousPronunciationViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()

    @Test
    fun conflictingLeadSensesFailAndRetryWithoutNativeTextOrKnowledgeChanges() = runTest {
        val fixture = observed(leadRecords)
        val vm = fixture.vm

        // Both precise sense requests must be protected, including expanded UI.
        leadRecords.forEachIndexed { index, record ->
            if (index == 1) vm.onAction(VocabularyBookAction.SenseSelected(record.senseId))
            vm.onAction(VocabularyBookAction.SensePronunciationRequested(record.senseId))
            advanceUntilIdle()

            assertTrue("A text-only engine cannot choose between the two saved lead pronunciations",
                fixture.plays.isEmpty())
            assertTrue("Use the existing explicit playback failure, not silent rejection", vm.uiState.value.pronunciationFailed)
            assertFalse("An unavailable pronunciation must not remain busy", vm.uiState.value.pronunciationBusy)
            fixture.assertKnowledgeUnchanged()

            repeat(2) { vm.onAction(VocabularyBookAction.RetryPronunciationRequested) }
            advanceUntilIdle()
            assertTrue("Retry cannot guess a pronunciation, substitute an example, or queue the same ambiguous text",
                fixture.plays.isEmpty())
            assertTrue(vm.uiState.value.pronunciationFailed)
            assertFalse(vm.uiState.value.pronunciationBusy)
            fixture.assertKnowledgeUnchanged()
        }

        vm.onAction(VocabularyBookAction.ReturnHomeRequested)
        advanceUntilIdle()
        assertEquals(1, fixture.effects.count { it == VocabularyBookEffect.ReturnHome })
        assertTrue(fixture.plays.isEmpty())
        assertFalse(vm.uiState.value.pronunciationBusy)
        assertFalse(vm.uiState.value.pronunciationFailed)
        fixture.assertKnowledgeUnchanged()
    }

    @Test
    fun sameRunPhoneticKeepsStoredWordPlaybackAndItsExistingFailureRetry() = runTest {
        val records = listOf(
            sense("run-motion", "run", "跑步", "/rʌn/", 1),
            sense("run-business", "run", "经营", "/rʌn/", 2),
        )
        val fixture = observed(records)
        val vm = fixture.vm
        assertEquals(1, vm.uiState.value.entries.size)

        // One shared word speaker may resolve to either exact saved sense.
        records.forEach { record ->
            vm.onAction(VocabularyBookAction.SensePronunciationRequested(record.senseId))
            advanceUntilIdle()
            assertTrue(vm.uiState.value.pronunciationBusy)
            assertFalse(vm.uiState.value.pronunciationFailed)
            val initial = fixture.plays.last()
            assertEquals("run", initial.text)
            vm.onAction(VocabularyBookAction.PronunciationFinished(initial.requestId, false))
            assertTrue(vm.uiState.value.pronunciationFailed)
            assertFalse(vm.uiState.value.pronunciationBusy)

            val count = fixture.plays.size
            repeat(2) { vm.onAction(VocabularyBookAction.RetryPronunciationRequested) }
            advanceUntilIdle()
            assertEquals("Busy retry remains single-flight", count + 1, fixture.plays.size)
            val retried = fixture.plays.last()
            assertEquals(initial.text, retried.text)
            assertTrue(retried.requestId != initial.requestId)
            vm.onAction(VocabularyBookAction.PronunciationFinished(retried.requestId, true))
            assertFalse(vm.uiState.value.pronunciationBusy)
            assertFalse(vm.uiState.value.pronunciationFailed)
            fixture.assertKnowledgeUnchanged()
        }
        assertEquals(listOf("run", "run", "run", "run"), fixture.plays.map { it.text })
    }

    @Test
    fun missingPhoneticDoesNotInventAConflictingReadingOrDisableLegacyText() = runTest {
        // Unknown + known and entirely unknown are controls, not proof of sound.
        listOf(listOf(null, "/lɛd/"), listOf(null, null), listOf(" \t ", "/lɛd/")).forEach { phonetics ->
            val records = leadRecords.mapIndexed { index, record -> record.copy(phonetic = phonetics[index]) }
            val fixture = observed(records)
            records.forEachIndexed { index, record ->
                fixture.vm.onAction(VocabularyBookAction.SensePronunciationRequested(record.senseId))
                advanceUntilIdle()
                assertEquals(index + 1, fixture.plays.size)
                assertEquals("lead", fixture.plays.last().text)
                assertTrue(fixture.vm.uiState.value.pronunciationBusy)
                assertFalse("Missing IPA is not evidence of a different pronunciation", fixture.vm.uiState.value.pronunciationFailed)
                fixture.vm.onAction(VocabularyBookAction.PronunciationFinished(fixture.plays.last().requestId, true))
                assertFalse(fixture.vm.uiState.value.pronunciationBusy)
                assertFalse(fixture.vm.uiState.value.pronunciationFailed)
                fixture.assertKnowledgeUnchanged()
            }
        }
    }

    @Test
    fun eachLeadSenseExampleRetainsItsExactStoredTextAndRetryWithoutWordSubstitution() = runTest {
        val fixture = observed(leadRecords)
        val vm = fixture.vm
        val expected = mutableListOf<String>()
        leadRecords.forEachIndexed { senseIndex, record ->
            vm.onAction(VocabularyBookAction.SenseSelected(record.senseId))
            val exampleIndex = senseIndex + 1
            val example = record.examples[exampleIndex]
            repeat(2) { vm.onAction(VocabularyBookAction.ExamplePronunciationRequested(record.senseId, exampleIndex)) }
            advanceUntilIdle()
            expected += example.pronunciationText
            assertEquals(expected, fixture.plays.map { it.text })
            assertEquals(record, vm.uiState.value.expandedSense)
            val initial = fixture.plays.last()
            vm.onAction(VocabularyBookAction.PronunciationFinished(initial.requestId, false))
            assertTrue(vm.uiState.value.pronunciationFailed)
            repeat(2) { vm.onAction(VocabularyBookAction.RetryPronunciationRequested) }
            advanceUntilIdle()
            expected += example.pronunciationText
            assertEquals(expected, fixture.plays.map { it.text })
            val retried = fixture.plays.last()
            vm.onAction(VocabularyBookAction.PronunciationFinished(retried.requestId, true))
            assertFalse(vm.uiState.value.pronunciationFailed)
            assertFalse(vm.uiState.value.pronunciationBusy)
            fixture.assertKnowledgeUnchanged()
        }
        assertTrue("An explicit example action stays an example; it does not become a bare word",
            fixture.plays.none { it.text == "lead" })
    }

    @Test
    fun loadedConflictOutsideCurrentScopeStillBlocksWordWhileTheVisibleListStaysFiltered() = runTest {
        val visible = leadRecords[0]
        val hidden = leadRecords[1].copy(learningScopes = setOf("CET_6"))
        val allRecords = listOf(visible, hidden)
        val fixture = observed(allRecords, visibleRecords = listOf(visible))
        val vm = fixture.vm
        assertEquals("四级单词", vm.uiState.value.currentScope)
        assertEquals("The repository delivered both saved pronunciations", allRecords, fixture.repository.records)
        assertEquals(listOf(visible.senseId), vm.uiState.value.entries.flatMap { entry -> entry.senses.map { it.senseId } })

        vm.onAction(VocabularyBookAction.SensePronunciationRequested(visible.senseId))
        advanceUntilIdle()
        assertTrue("Scope filtering must not erase already loaded conflicting pronunciation evidence", fixture.plays.isEmpty())
        assertTrue(vm.uiState.value.pronunciationFailed)
        assertFalse(vm.uiState.value.pronunciationBusy)
        repeat(2) { vm.onAction(VocabularyBookAction.RetryPronunciationRequested) }
        advanceUntilIdle()
        assertTrue(fixture.plays.isEmpty())
        assertTrue(vm.uiState.value.pronunciationFailed)
        assertFalse(vm.uiState.value.pronunciationBusy)
        assertEquals(allRecords, fixture.repository.records)
        fixture.assertKnowledgeUnchanged()
    }

    @Test
    fun conflictingWordStopsAnActiveWordOrExampleAndOldSuccessCannotClearItsFailure() = runTest {
        listOf(false, true).forEach { startWithExample ->
            val run = sense("run-business", "run", "经营", "/rʌn/", 3)
            val fixture = observed(leadRecords + run)
            val vm = fixture.vm
            if (startWithExample) {
                vm.onAction(VocabularyBookAction.SenseSelected(leadRecords[0].senseId))
                vm.onAction(VocabularyBookAction.ExamplePronunciationRequested(leadRecords[0].senseId, 1))
            } else {
                vm.onAction(VocabularyBookAction.SensePronunciationRequested(run.senseId))
            }
            advanceUntilIdle()
            val oldRequest = fixture.plays.single()
            assertEquals(if (startWithExample) leadRecords[0].examples[1].pronunciationText else "run", oldRequest.text)
            assertTrue(vm.uiState.value.pronunciationBusy)

            vm.onAction(VocabularyBookAction.SensePronunciationRequested(leadRecords[1].senseId))
            advanceUntilIdle()
            assertEquals("Rejecting the new ambiguous word must stop the actual prior playback",
                listOf(oldRequest, VocabularyBookEffect.StopPronunciation), fixture.effects)
            assertTrue(vm.uiState.value.pronunciationFailed)
            assertFalse(vm.uiState.value.pronunciationBusy)
            val failedState = vm.uiState.value

            // A delayed callback belongs to the old normal word/example only.
            vm.onAction(VocabularyBookAction.PronunciationFinished(oldRequest.requestId, true))
            advanceUntilIdle()
            assertEquals("Old success must not clear the new word's unavailable state", failedState, vm.uiState.value)
            repeat(2) { vm.onAction(VocabularyBookAction.RetryPronunciationRequested) }
            advanceUntilIdle()
            assertEquals("Retry retains the rejected word identity, never the previously playing object",
                listOf(oldRequest), fixture.plays)
            assertTrue(vm.uiState.value.pronunciationFailed)
            assertFalse(vm.uiState.value.pronunciationBusy)
            fixture.assertKnowledgeUnchanged()
        }
    }

    private suspend fun TestScope.observed(
        records: List<VocabularySenseRecord>,
        visibleRecords: List<VocabularySenseRecord> = records,
    ): Fixture {
        val repository = FixtureRepository(records)
        val vm = VocabularyBookViewModel(repository)
        val effects = mutableListOf<VocabularyBookEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect(effects::add) }
        advanceUntilIdle()
        assertFalse(vm.uiState.value.loading)
        assertFalse(vm.uiState.value.loadFailed)
        assertTrue(effects.isEmpty())
        return Fixture(vm, effects, repository, visibleRecords).also { it.assertKnowledgeUnchanged() }
    }

    private class FixtureRepository(val records: List<VocabularySenseRecord>) : VocabularyBookRepository {
        var reads = 0
        override suspend fun loadAllRecords(): VocabularyLoadResult {
            reads++
            return VocabularyLoadResult.Ready(setOf("CET_4"), records)
        }
    }

    private data class Fixture(
        val vm: VocabularyBookViewModel,
        val effects: MutableList<VocabularyBookEffect>,
        val repository: FixtureRepository,
        val visibleRecords: List<VocabularySenseRecord>,
    ) {
        val plays get() = effects.filterIsInstance<VocabularyBookEffect.PlayPronunciation>()
        fun assertKnowledgeUnchanged() {
            assertEquals("Preserve sense IDs, meanings, IPA, examples, statuses, counts and scopes",
                visibleRecords, vm.uiState.value.entries.flatMap { it.senses })
            assertEquals("Pronunciation and its retry do not reload or regenerate the knowledge", 1, repository.reads)
        }
    }

    companion object {
        private fun sense(id: String, english: String, meaning: String, ipa: String?, order: Long) =
            VocabularySenseRecord(
                senseId = id,
                englishForm = english,
                meaningZh = meaning,
                status = if (order == 1L) KnowledgeStatus.MASTERED else KnowledgeStatus.LEARNING,
                effectiveLearnedCount = if (order == 1L) 2 else 0,
                learningScopes = setOf("CET_4"),
                enteredOrder = order,
                phonetic = ipa,
                pronunciationText = english,
                representativeScene = "隔离词义显示夹具：$meaning",
                focusOccurrenceCount = 3,
                contextOccurrenceCount = 4,
            )

        private val leadRecords = listOf(
            sense("lead-guide", "lead", "带领", "/liːd/", 1).copy(examples = listOf(
                VocabularyExample("I lead the team.", "我带领团队。", "I lead the team."),
                VocabularyExample("We lead the visitors inside.", "我们带游客进入室内。", "We lead the visitors inside."),
                VocabularyExample("They lead the way.", "他们带路。", "They lead the way."),
            )),
            sense("lead-metal", "lead", "铅", "/lɛd/", 2).copy(examples = listOf(
                VocabularyExample("This sample contains lead.", "这份样品含铅。", "This sample contains lead."),
                VocabularyExample("The worker handles lead carefully.", "工人小心处理铅。", "The worker handles lead carefully."),
                VocabularyExample("Lead is a heavy metal.", "铅是一种重金属。", "Lead is a heavy metal."),
            )),
        )
    }
}
