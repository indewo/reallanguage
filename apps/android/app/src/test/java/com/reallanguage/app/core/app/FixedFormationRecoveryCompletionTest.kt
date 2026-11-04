package com.reallanguage.app.core.app

import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.home.domain.*
import com.reallanguage.app.feature.safety.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FixedFormationRecoveryCompletionTest {
    private class Store(val saved: FormationRecoverySnapshot) : FormationRecoveryRepository {
        var row: FormationRecoveryLoad = FormationRecoveryLoad.Loaded(4, saved)
        var reads = 0
        override suspend fun load(sourceEntryId: String): FormationRecoveryLoad { reads++; return row }
        override suspend fun save(snapshot: FormationRecoverySnapshot, expectedRevision: Long?): FormationRecoverySave = error("Cleanup never re-fixes content")
        override suspend fun list(): FormationRecoveryList = FormationRecoveryList.Listed(if (row is FormationRecoveryLoad.Loaded)
            listOf(saved.item()) else emptyList())
    }

    @Test fun exactCommittedSourceIsReadBeforeRevisionBoundCleanupAndMissingReadback() = runTest {
        val snapshot = snapshot()
        val expected = requireNotNull(snapshot.toFixedSourceProjection("fixed-flow"))
        val store = Store(snapshot)
        val calls = mutableListOf<String>()
        val completion = FixedFormationRecoveryCompletion(store, {
            calls += "sql:$it"; FixedSourceLoad.Loaded(expected)
        }, { id, revision ->
            assertEquals(1, store.reads)
            calls += "discard:$id:$revision"; store.row = FormationRecoveryLoad.Missing; true
        }) { true }
        assertEquals(FixedFormationCleanup.Cleaned, completion.complete(expected))
        assertEquals(listOf("sql:fixed-flow", "discard:accepted-source:4"), calls)
        assertEquals(2, store.reads)
        assertEquals(FixedFormationCleanup.Cleaned, completion.complete(expected))
        assertEquals(1, calls.count { it.startsWith("discard:") })
    }

    @Test fun absentUnreadableOrDifferentFormalSourceCannotAuthorizeDeletingADraft() = runTest {
        val snapshot = snapshot()
        val expected = requireNotNull(snapshot.toFixedSourceProjection("fixed-flow"))
        listOf(FixedSourceLoad.Missing, FixedSourceLoad.Unavailable,
            FixedSourceLoad.Loaded(expected.copy(source = expected.source.copy(text = "不同的完整来源。")))).forEach { result ->
            val store = Store(snapshot)
            var removals = 0
            val completion = FixedFormationRecoveryCompletion(store, { result }, { _, _ -> removals++; true }) { true }
            assertEquals(FixedFormationCleanup.Unconfirmed, completion.complete(expected))
            assertEquals(0, removals)
            assertEquals(FormationRecoveryLoad.Loaded(4, snapshot), store.row)
        }
    }

    @Test fun committedFixIsNotReportedAsFailureWhenLocalRemovalNeedsRetry() = runTest {
        val snapshot = snapshot()
        val expected = requireNotNull(snapshot.toFixedSourceProjection("fixed-flow"))
        val store = Store(snapshot)
        var removals = 0
        val completion = FixedFormationRecoveryCompletion(store, { FixedSourceLoad.Loaded(expected) }, { _, _ ->
            removals++
            if (removals == 1) false else { store.row = FormationRecoveryLoad.Missing; true }
        }) { true }
        assertEquals(FixedFormationCleanup.FixedCleanupPending, completion.complete(expected))
        assertEquals(FixedFormationStatus.Fixed, completion.status(snapshot.item()))
        assertEquals(FixedFormationCleanup.Cleaned, completion.complete(expected))
        assertEquals(2, removals)
    }

    @Test fun cleanupNeverDeletesANewerOrDifferentAcceptedProjection() = runTest {
        val original = snapshot()
        val expected = requireNotNull(original.toFixedSourceProjection("fixed-flow"))
        val changed = original.copy(sourceRevision = 1, source = original.source.copy(text = "新的已确认原文。"))
        val store = Store(changed)
        var removals = 0
        val completion = FixedFormationRecoveryCompletion(store, { FixedSourceLoad.Loaded(expected) }, { _, _ -> removals++; true }) { true }
        assertEquals(FixedFormationCleanup.FixedCleanupPending, completion.complete(expected))
        assertEquals(0, removals)
        assertEquals(changed, (store.row as FormationRecoveryLoad.Loaded).snapshot)
    }

    @Test fun ownerRevocationAfterSqlReadCannotRemoveOrExposeTheOldOwnersDraft() = runTest {
        val snapshot = snapshot()
        val expected = requireNotNull(snapshot.toFixedSourceProjection("fixed-flow"))
        var active = true
        var removals = 0
        val completion = FixedFormationRecoveryCompletion(Store(snapshot), {
            active = false; FixedSourceLoad.Loaded(expected)
        }, { _, _ -> removals++; true }) { active }
        assertEquals(FixedFormationCleanup.Unconfirmed, completion.complete(expected))
        assertEquals(FixedFormationStatus.Unavailable, completion.status(snapshot.item()))
        assertEquals(0, removals)
    }

    @Test fun cancellationDuringRemovalPropagatesWithoutStartingAnotherCleanupOrFix() = runTest {
        val snapshot = snapshot()
        val expected = requireNotNull(snapshot.toFixedSourceProjection("fixed-flow"))
        var removals = 0
        val completion = FixedFormationRecoveryCompletion(Store(snapshot), { FixedSourceLoad.Loaded(expected) },
            { _, _ -> removals++; throw CancellationException("isolated cancellation") }) { true }
        var propagated = false
        try { completion.complete(expected) } catch (_: CancellationException) { propagated = true }
        assertTrue(propagated)
        assertEquals(1, removals)
    }

    @Test fun unreadableCompletionEvidenceCannotBlankIndependentUnfinishedProgress() = runTest {
        val snapshot = snapshot()
        val store = Store(snapshot)
        val completion = FixedFormationRecoveryCompletion(store, { FixedSourceLoad.Unavailable }, { _, _ -> error("Read only") }) { true }
        assertEquals(FixedFormationStatus.Unavailable, completion.status(snapshot.item()))
        val chapter = IncompleteLearningEntry("formal-chapter", OrdinarySourceType.DIARY, "正式日记", 1,
            "LR-01", "开始学习", ResumeLearningKind.ORDINARY)
        val listing = FormationIncompleteLearningRepository({ IncompleteLearningLoadResult.Ready(listOf(chapter)) }, store, completion::status)
        assertEquals(IncompleteLearningLoadResult.Ready(listOf(chapter)), listing.loadOrdinaryLearning())
        assertTrue("Unreadable encrypted evidence must be preserved for later recovery", store.row is FormationRecoveryLoad.Loaded)
    }

    @Test fun formallyFixedDraftIsExcludedWhileRealChapterRemainsVisibleEvenBeforeCleanup() = runTest {
        val snapshot = snapshot()
        val expected = requireNotNull(snapshot.toFixedSourceProjection("fixed-flow"))
        val store = Store(snapshot)
        val completion = FixedFormationRecoveryCompletion(store, { FixedSourceLoad.Loaded(expected) }, { _, _ -> false }) { true }
        val chapter = IncompleteLearningEntry("formal-chapter", OrdinarySourceType.DIARY, "正式日记", 1, "LR-01", "开始学习", ResumeLearningKind.ORDINARY)
        val listing = FormationIncompleteLearningRepository({ IncompleteLearningLoadResult.Ready(listOf(chapter)) }, store, completion::status)
        val listed = listing.loadOrdinaryLearning() as? IncompleteLearningLoadResult.Ready
        assertNotNull(listed)
        assertEquals(listOf(chapter), listed!!.entries)
        assertTrue(store.row is FormationRecoveryLoad.Loaded)
    }

    private fun snapshot(): FormationRecoverySnapshot {
        val unit = SemanticUnit("u1", 0, "我喝茶。")
        return FormationRecoverySnapshot("accepted-source", 0, FormationRecoveryPhase.FINAL_FIX,
            ConfirmedSource(unit.textZh, SourceType.DIARY, SourceContentShape.ARTICLE), "正式日记", FormationSourceOrigin.Diary,
            listOf(unit), listOf("u1"), listOf(ChapterDraft("c1", listOf("u1"))),
            listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", unit.textZh, true,
                listOf(EnglishCandidate("e1", "I drink tea.")))))), mapOf("u1" to "e1"),
            emptyList(), emptyList(), emptySet(), "targets-entry", "fixed-flow")
    }
}

private fun FormationRecoverySnapshot.item() = FormationRecoveryItem(sourceEntryId, 4, phase, source.sourceType, sourceTitle)
