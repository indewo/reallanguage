package com.reallanguage.app.core.app

import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.home.domain.*
import com.reallanguage.app.feature.safety.domain.SourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FormationIncompleteLearningRepositoryTest {
    private val ordinary = IncompleteLearningEntry("chapter", OrdinarySourceType.DIARY, "已固定日记", 1, "LR-02", "基础讲解与三轮朗读", ResumeLearningKind.ORDINARY)
    private val item = FormationRecoveryItem("forming-source", 4, FormationRecoveryPhase.LEARNING_TARGET_SELECTION, SourceType.DIARY, "已确认但未固定日记")
    private fun formation(load: suspend () -> FormationRecoveryList) = object : FormationRecoveryRepository {
        override suspend fun list() = load()
        override suspend fun load(sourceEntryId: String): FormationRecoveryLoad = error("Listing must not start a formation task")
        override suspend fun save(snapshot: FormationRecoverySnapshot, expectedRevision: Long?): FormationRecoverySave = error("Listing must not save or generate")
    }
    @Test fun confirmedFormationAppearsAlongsideLearningWithoutInventingAFormalChapter() = runTest {
        val repo = FormationIncompleteLearningRepository({ IncompleteLearningLoadResult.Ready(listOf(ordinary)) }, formation { FormationRecoveryList.Listed(listOf(item)) })
        val rows = (repo.loadOrdinaryLearning() as IncompleteLearningLoadResult.Ready).entries
        assertEquals(2, rows.size); assertEquals(ordinary, rows.first { it.learningId == "chapter" })
        val forming = rows.single { it.kind == ResumeLearningKind.FORMATION }
        assertEquals(item.sourceEntryId, forming.learningId); assertNull(forming.chapterNumber)
        assertEquals(item.title, forming.sourceLabelZh); assertEquals("选择学习目标", forming.savedStepLabelZh)
        assertFalse(forming.savedStepId.startsWith("LR-"))
    }
    @Test fun anUnreadableFormationIndexCannotHideValidChapterProgress() = runTest {
        val repo = FormationIncompleteLearningRepository({ IncompleteLearningLoadResult.Ready(listOf(ordinary)) }, formation { FormationRecoveryList.Unavailable })
        assertEquals(IncompleteLearningLoadResult.Ready(listOf(ordinary)), repo.loadOrdinaryLearning())
    }
    @Test fun oneUnavailableFormationStatusCannotBlankOtherRecoverableProgress() = runTest {
        val unavailable = item.copy(sourceEntryId = "unavailable")
        val repo = FormationIncompleteLearningRepository(
            { IncompleteLearningLoadResult.Ready(listOf(ordinary)) },
            formation { FormationRecoveryList.Listed(listOf(unavailable, item)) },
            { source -> if (source == unavailable) FixedFormationStatus.Unavailable else FixedFormationStatus.NotFixed },
        )
        val rows = (repo.loadOrdinaryLearning() as IncompleteLearningLoadResult.Ready).entries
        assertEquals(listOf("chapter", "forming-source"), rows.map { it.learningId })
    }
    @Test fun ordinaryFailureDoesNotExposeOnlyFormationAsIfAllRecordsLoaded() = runTest {
        val repo = FormationIncompleteLearningRepository({ IncompleteLearningLoadResult.Failed }, formation { FormationRecoveryList.Listed(listOf(item)) })
        assertEquals(IncompleteLearningLoadResult.Failed, repo.loadOrdinaryLearning())
    }
    @Test fun sourceTypesAndRealFormationPhasesAreNotConvertedIntoDiaryOrLearningProgress() = runTest {
        val types = listOf(SourceType.DIARY, SourceType.RECORDING, SourceType.AI_SCENE)
        val items = FormationRecoveryPhase.entries.mapIndexed { index, phase -> item.copy(sourceEntryId = "source-$index", phase = phase, sourceType = types[index % types.size]) }
        val repo = FormationIncompleteLearningRepository({ IncompleteLearningLoadResult.Ready(emptyList()) }, formation { FormationRecoveryList.Listed(items) })
        val rows = (repo.loadOrdinaryLearning() as IncompleteLearningLoadResult.Ready).entries
        assertEquals(items.size, rows.size)
        assertEquals(setOf(OrdinarySourceType.DIARY, OrdinarySourceType.QUICK_RECORDING, OrdinarySourceType.AI_SCENE), rows.map { it.sourceType }.toSet())
        assertTrue(rows.all { it.chapterNumber == null && it.kind == ResumeLearningKind.FORMATION && it.savedStepLabelZh.isNotBlank() })
    }
    @Test fun cancelledListReadDoesNotBecomeEmptyOrRetryInTheBackground() = runTest {
        val repo = FormationIncompleteLearningRepository({ IncompleteLearningLoadResult.Ready(emptyList()) }, formation { throw CancellationException() })
        var propagated = false
        try { repo.loadOrdinaryLearning() } catch (_: CancellationException) { propagated = true }
        assertTrue(propagated)
    }
}
