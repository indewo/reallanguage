package com.reallanguage.app.core.app

import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.safety.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FormationJournalLeaseAndPositionTest {
    private class Store(snapshot: FormationRecoverySnapshot) : FormationRecoveryRepository {
        var row = FormationRecoveryLoad.Loaded(4, snapshot)
        var reads = 0
        var saves = 0
        var onRead: suspend (Int) -> Unit = {}
        override suspend fun load(sourceEntryId: String): FormationRecoveryLoad {
            reads++; onRead(reads); return row
        }
        override suspend fun save(snapshot: FormationRecoverySnapshot, expectedRevision: Long?): FormationRecoverySave {
            saves++
            if (expectedRevision != row.revision) return FormationRecoverySave.Conflict
            row = FormationRecoveryLoad.Loaded(row.revision + 1, snapshot)
            return FormationRecoverySave.Saved(row.revision)
        }
        override suspend fun list(): FormationRecoveryList = error("The journal does not scan accounts")
    }

    @Test fun staleFocusObserverCannotDowngradeAnAlreadySavedFinalStageOrEraseItsTargets() = runTest {
        val final = selectedSnapshot().copy(phase = FormationRecoveryPhase.FINAL_FIX, fixEntryId = "fix-entry")
        val store = Store(final)
        val stale = HomeDiaryRoute.FocusSelection(final.source.text, final.units,
            initialSelectedUnitIds = setOf("u1"), sourceEntryId = final.sourceEntryId)
        assertNull(FormationProgressJournal(store) { true }.prepare(stale, canApply = { false }))
        assertEquals(final, store.row.snapshot)
        assertEquals(0, store.saves)
        assertEquals(0, store.reads)
    }

    @Test fun entryLeaseIsCheckedAfterWaitingForAnotherSaveToReleaseTheJournalLock() = runTest {
        val before = selectedSnapshot()
        val store = Store(before)
        val loadGate = CompletableDeferred<Unit>()
        store.onRead = { if (it == 1) loadGate.await() }
        val journal = FormationProgressJournal(store) { true }
        val first = async { journal.prepare(finalRoute(before), 1) }
        runCurrent()
        assertEquals(1, store.reads)
        var oldLease = true
        val oldPage = HomeDiaryRoute.FocusSelection(before.source.text, before.units,
            initialSelectedUnitIds = setOf("u1"), sourceEntryId = before.sourceEntryId)
        val late = async { journal.prepare(oldPage, canApply = { oldLease }) }
        runCurrent()
        oldLease = false
        loadGate.complete(Unit)
        assertNotNull(first.await())
        assertNull(late.await())
        assertEquals(FormationRecoveryPhase.FINAL_FIX, store.row.snapshot.phase)
        assertEquals(before.targets, store.row.snapshot.targets)
        assertEquals(1, store.saves)
        assertEquals(2, store.reads)
    }

    @Test fun leaseRevokedDuringReadCannotReachTheSaveEvenWithAValidAccount() = runTest {
        val before = selectedSnapshot()
        val store = Store(before)
        var lease = true
        store.onRead = { lease = false }
        assertNull(FormationProgressJournal(store) { true }.prepare(finalRoute(before), 1) { lease })
        assertEquals(0, store.saves)
        assertEquals(before, store.row.snapshot)
    }

    @Test fun aLeaseLostDuringVerifiedReadbackCannotAnnounceAReadyPage() = runTest {
        val before = selectedSnapshot()
        val store = Store(before)
        var lease = true
        store.onRead = { if (it == 2) lease = false }
        assertNull(FormationProgressJournal(store) { true }.prepare(finalRoute(before), 1) { lease })
        assertEquals(1, store.saves)
        // A completed save stays saved; losing the UI lease is not authority to roll it back.
        assertEquals(FormationRecoveryPhase.FINAL_FIX, store.row.snapshot.phase)
        assertEquals(2, store.reads)
    }

    @Test fun finalFixWithoutAnExplicitPositionKeepsTheMatchedSelectionPagePosition() = runTest {
        val before = selectedSnapshot()
        val store = Store(before)
        val result = FormationProgressJournal(store) { true }.prepare(finalRoute(before))
        assertNotNull(result)
        assertEquals(1, result!!.targetSentenceIndex)
        assertEquals(1, store.row.snapshot.targetSentenceIndex)
        assertEquals(before.targetSelectionEntryId, result.targetSelectionEntryId)
    }

    @Test fun targetSelectionUsesTheRestoredRoutePositionRatherThanAnImplicitZero() = runTest {
        val before = selectedSnapshot()
        val store = Store(before.copy(targetSentenceIndex = 0))
        val route = HomeDiaryRoute.LearningTargetSelection(before.source.text, before.units, before.englishChapters,
            before.selectedCandidateIds, before.targets, before.queryableExpressions, before.selectedTargetIds,
            entryId = before.targetSelectionEntryId!!, sourceEntryId = before.sourceEntryId, initialSentenceIndex = 1)
        val result = FormationProgressJournal(store) { true }.prepare(route)
        assertNotNull(result)
        assertEquals(1, result!!.targetSentenceIndex)
    }

    private fun finalRoute(before: FormationRecoverySnapshot) = HomeDiaryRoute.FinalFix(before.source.text, before.units,
        before.englishChapters, before.selectedCandidateIds, before.targets, before.selectedTargetIds,
        entryId = "fix-entry", sourceEntryId = before.sourceEntryId)

    private fun selectedSnapshot(): FormationRecoverySnapshot {
        val units = listOf(SemanticUnit("u1", 0, "我喝茶。"), SemanticUnit("u2", 1, "她工作。"))
        return FormationRecoverySnapshot("accepted-source", 0, FormationRecoveryPhase.LEARNING_TARGET_SELECTION,
            ConfirmedSource("我喝茶。\n她工作。", SourceType.DIARY, SourceContentShape.ARTICLE), "隔离日记", FormationSourceOrigin.Diary,
            units, listOf("u1", "u2"), listOf(ChapterDraft("c1", listOf("u1", "u2"))),
            listOf(EnglishChapterDraft("c1", listOf(
                EnglishSentenceDraft("u1", units[0].textZh, true, listOf(EnglishCandidate("e1", "I drink tea."))),
                EnglishSentenceDraft("u2", units[1].textZh, true, listOf(EnglishCandidate("e2", "She works."))),
            ))), mapOf("u1" to "e1", "u2" to "e2"),
            listOf(LearningTarget("tea", "u1", "tea", "茶", LearningTargetType.WORD)), emptyList(), setOf("tea"),
            targetSelectionEntryId = "targets-entry", targetSentenceIndex = 1)
    }
}
