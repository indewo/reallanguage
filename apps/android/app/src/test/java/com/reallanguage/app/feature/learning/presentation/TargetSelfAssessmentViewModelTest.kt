package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class TargetSelfAssessmentViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val ability = SelfAssessmentAbility.MEANING_WRITING
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun loadsOnlyRepositoryTargetsAndSavedAssessment() = runTest(dispatcher) {
        val repository = Repository().apply { targets = targets.map { it.copy(assessedAbilities = setOf(ability)) } }
        val model = TargetSelfAssessmentViewModel(repository)
        advanceUntilIdle()
        assertEquals(repository.targets, model.uiState.value.targets)
        assertFalse(model.uiState.value.busy)
    }

    @Test fun markIsAcknowledgedAfterRereadAndRepeatedClicksOnlySaveOnce() = runTest(dispatcher) {
        val repository = Repository()
        val model = TargetSelfAssessmentViewModel(repository)
        advanceUntilIdle()
        repeat(3) { model.onAction(TargetSelfAssessmentAction.Mark("t", ability)) }
        advanceUntilIdle()
        assertEquals(1, repository.marks)
        assertEquals(setOf(ability), model.uiState.value.targets.single().assessedAbilities)
        model.onAction(TargetSelfAssessmentAction.Mark("t", ability))
        advanceUntilIdle()
        assertEquals(1, repository.marks)
    }

    @Test fun failedMarkIsNotShownAsSuccessAndRetrySavesTheSameIntent() = runTest(dispatcher) {
        val repository = Repository().apply { saveFails = true }
        val model = TargetSelfAssessmentViewModel(repository)
        advanceUntilIdle()
        model.onAction(TargetSelfAssessmentAction.Mark("t", ability))
        advanceUntilIdle()
        assertTrue(model.uiState.value.failed)
        assertTrue(model.uiState.value.targets.single().assessedAbilities.isEmpty())
        repository.saveFails = false
        model.onAction(TargetSelfAssessmentAction.Retry)
        advanceUntilIdle()
        assertFalse(model.uiState.value.failed)
        assertEquals(setOf(ability), model.uiState.value.targets.single().assessedAbilities)
    }

    @Test fun claimedSaveWithoutPersistedRowIsAnExplicitFailure() = runTest(dispatcher) {
        val repository = Repository().apply { omitSave = true }
        val model = TargetSelfAssessmentViewModel(repository)
        advanceUntilIdle()
        model.onAction(TargetSelfAssessmentAction.Mark("t", ability))
        advanceUntilIdle()
        assertTrue(model.uiState.value.failed)
        assertTrue(model.uiState.value.targets.single().assessedAbilities.isEmpty())
    }

    @Test fun loadExceptionStopsSpinnerAndRetryCanRecover() = runTest(dispatcher) {
        val repository = Repository().apply { loadFails = true }
        val model = TargetSelfAssessmentViewModel(repository)
        advanceUntilIdle()
        assertTrue(model.uiState.value.failed)
        assertFalse(model.uiState.value.busy)
        repository.loadFails = false
        model.onAction(TargetSelfAssessmentAction.Retry)
        advanceUntilIdle()
        assertEquals(1, model.uiState.value.targets.size)
        assertFalse(model.uiState.value.failed)
    }

    @Test fun unknownTargetAndWrongAbilityNeverReachRepository() = runTest(dispatcher) {
        val repository = Repository()
        val model = TargetSelfAssessmentViewModel(repository)
        advanceUntilIdle()
        model.onAction(TargetSelfAssessmentAction.Mark("other", ability))
        model.onAction(TargetSelfAssessmentAction.Mark("t", SelfAssessmentAbility.PATTERN_SPEAKING))
        advanceUntilIdle()
        assertEquals(0, repository.marks)
    }

    private class Repository : TargetSelfAssessmentRepository {
        var targets = listOf(SelfAssessmentTarget("t", "s", "calm", "平静的", setOf(SelfAssessmentAbility.MEANING_WRITING)))
        var marks = 0
        var saveFails = false
        var loadFails = false
        var omitSave = false
        override suspend fun load(): List<SelfAssessmentTarget> {
            if (loadFails) error("isolated load failure")
            return targets
        }
        override suspend fun mark(targetId: String, ability: SelfAssessmentAbility): Boolean {
            marks++
            if (saveFails) return false
            if (!omitSave) targets = targets.map { if (it.targetId == targetId) it.copy(assessedAbilities = it.assessedAbilities + ability) else it }
            return true
        }
    }
}
