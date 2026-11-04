package com.reallanguage.app.core.app

import com.reallanguage.app.feature.formation.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FormationProgressJournalTest {
    @Test fun leadingBlankLinesDoNotPreventSavingAcceptedTextAndItsReadableTitle() = runTest {
        val source = "\n\r\n  今天虽然下雨，但我还是按约定去了图书馆。"
        val saved = FormationProgressJournal(Store()) { true }.prepare(
            HomeDiaryRoute.SemanticSplit(source, entryId = "leading-lines"))
        assertNotNull(saved)
        assertEquals(source, saved!!.source.text)
        assertTrue(saved.sourceTitle.isNotBlank())
    }

    @Test fun derivedTitleNeverCutsASupplementaryCharacterInHalf() = runTest {
        val source = "这".repeat(31) + "😀然后继续。"
        val saved = FormationProgressJournal(Store()) { true }.prepare(
            HomeDiaryRoute.SemanticSplit(source, entryId = "unicode-title"))!!
        assertEquals(source, saved.source.text)
        val encoder = Charsets.UTF_8.newEncoder()
        assertTrue("A generated title must remain encodable by the strict recovery codec", encoder.canEncode(saved.sourceTitle))
    }
    private val route = HomeDiaryRoute.SemanticSplit("完整且已确认的日记。\n还有后面的内容。", entryId = "accepted-source")
    private class Store : FormationRecoveryRepository {
        var row: FormationRecoveryLoad = FormationRecoveryLoad.Missing
        var saves = 0
        var reads = 0
        var fail = false
        var conflictOnce = false
        var conflictAfterWrite = false
        var corruptReadback = false
        var afterSave: () -> Unit = {}
        var afterLoad: suspend (Int) -> Unit = {}
        override suspend fun load(sourceEntryId: String): FormationRecoveryLoad { reads++; afterLoad(reads); return row }
        override suspend fun save(snapshot: FormationRecoverySnapshot, expectedRevision: Long?): FormationRecoverySave {
            saves++
            if (fail) return FormationRecoverySave.Unavailable
            if (conflictOnce) {
                conflictOnce = false
                return FormationRecoverySave.Conflict
            }
            val revision = expectedRevision?.plus(1) ?: 0
            row = FormationRecoveryLoad.Loaded(revision, if (corruptReadback) snapshot.copy(sourceTitle = "不一致") else snapshot)
            afterSave()
            if (conflictAfterWrite) return FormationRecoverySave.Conflict
            return FormationRecoverySave.Saved(revision)
        }
        override suspend fun list(): FormationRecoveryList = error("Journal does not poll all sources")
    }
    @Test fun acceptedSourceMustBeSavedAndReadBackBeforeTheNextStageMayRun() = runTest {
        val store = Store()
        val saved = FormationProgressJournal(store) { true }.prepare(route)
        assertNotNull(saved); assertEquals(route.text, saved!!.source.text)
        assertEquals(route.entryId, saved.sourceEntryId); assertEquals(FormationRecoveryPhase.SEMANTIC_SPLIT, saved.phase)
        assertEquals(1, store.saves); assertEquals(2, store.reads)
    }
    @Test fun reopeningAnUnchangedStageReusesSavedDataWithoutAnotherWrite() = runTest {
        val store = Store()
        val first = FormationProgressJournal(store) { true }.prepare(route)
        assertNotNull(first)
        val reopened = FormationProgressJournal(store) { true }.prepare(route)
        assertEquals(first, reopened); assertEquals(1, store.saves)
    }
    @Test fun mismatchedReadBackNeverReportsTheNextStageReady() = runTest {
        val store = Store().apply { corruptReadback = true }
        assertNull(FormationProgressJournal(store) { true }.prepare(route))
        assertEquals(1, store.saves); assertEquals(2, store.reads)
    }
    @Test fun failedSaveHasNoAutomaticRetryAndCanBeRetriedWithoutLosingSource() = runTest {
        val store = Store().apply { fail = true }
        val journal = FormationProgressJournal(store) { true }
        assertNull(journal.prepare(route)); assertEquals(1, store.saves)
        store.fail = false
        assertEquals(route.text, journal.prepare(route)?.source?.text)
        assertEquals(2, store.saves)
    }
    @Test fun oneRevisionConflictReloadsAndRetriesWithoutShowingFailure() = runTest {
        val store = Store().apply { conflictOnce = true }
        val reasons = mutableListOf<FormationProgressFailure>()
        val journal = FormationProgressJournal(store, reasons::add) { true }
        val saved = journal.prepare(route)
        assertNotNull(saved)
        assertEquals(route.text, saved!!.source.text)
        assertEquals(2, store.saves)
        assertTrue(reasons.isEmpty())
    }
    @Test fun unreadablePriorRecordMayBeRecoveredByRepositoryInsteadOfBlockingTheRoute() = runTest {
        val store = Store().apply { row = FormationRecoveryLoad.Unavailable }
        val saved = FormationProgressJournal(store) { true }.prepare(route)
        assertNotNull(saved)
        assertEquals(route.text, saved!!.source.text)
        assertEquals(1, store.saves)
    }
    @Test fun exactDurableConflictReplayStillSucceedsForTheCurrentAccountAndScreen() = runTest {
        val store = Store().apply { conflictAfterWrite = true }
        val saved = FormationProgressJournal(store) { true }.prepare(route)
        assertNotNull(saved)
        assertEquals(route.text, saved!!.source.text)
        assertEquals(1, store.saves)
        assertEquals(2, store.reads)
    }
    @Test fun accountChangeDuringConflictReadbackCannotPublishTheSavedSnapshot() = runTest {
        var activeAccount = true
        val store = Store().apply {
            conflictAfterWrite = true
            afterLoad = { read -> if (read == 2) activeAccount = false }
        }
        val reasons = mutableListOf<FormationProgressFailure>()
        assertNull(FormationProgressJournal(store, reasons::add) { activeAccount }.prepare(route))
        assertEquals(listOf(FormationProgressFailure.ACCESS_CHANGED), reasons)
        assertEquals(1, store.saves)
    }
    @Test fun screenChangeDuringConflictReadbackCannotPublishTheSavedSnapshot() = runTest {
        var activeScreen = true
        val store = Store().apply {
            conflictAfterWrite = true
            afterLoad = { read -> if (read == 2) activeScreen = false }
        }
        val reasons = mutableListOf<FormationProgressFailure>()
        assertNull(FormationProgressJournal(store, reasons::add) { true }.prepare(route, canApply = { activeScreen }))
        assertEquals(listOf(FormationProgressFailure.ACCESS_CHANGED), reasons)
        assertEquals(1, store.saves)
    }
    @Test fun cancellationDuringConflictReadbackPropagatesBeforeReturningAcceptedProgress() = runTest {
        val store = Store().apply {
            conflictAfterWrite = true
            afterLoad = { read -> if (read == 2) currentCoroutineContext().cancel() }
        }
        var returned = false
        val job = launch {
            FormationProgressJournal(store) { true }.prepare(route)
            returned = true
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(returned)
        assertEquals(1, store.saves)
    }
    @Test fun failureReportsTechnicalStageWithoutSourceContent() = runTest {
        val store = Store().apply { fail = true }
        val reasons = mutableListOf<FormationProgressFailure>()
        assertNull(FormationProgressJournal(store, reasons::add) { true }.prepare(route))
        assertEquals(listOf(FormationProgressFailure.STORAGE_UNAVAILABLE), reasons)
    }
    @Test fun aLateAccountChangeCannotOpenOrReadAPreviousOwnersAcceptedContent() = runTest {
        var active = true
        val store = Store().apply { afterSave = { active = false } }
        val journal = FormationProgressJournal(store) { active }
        assertNull(journal.prepare(route)); assertEquals(1, store.saves)
        assertNull(journal.read(route.entryId))
    }
    @Test fun cancellationPropagatesRatherThanStartingBackgroundRetry() = runTest {
        val store = Store().apply { afterSave = { throw CancellationException() } }
        var propagated = false
        try { FormationProgressJournal(store) { true }.prepare(route) } catch (_: CancellationException) { propagated = true }
        assertTrue(propagated); assertEquals(1, store.saves)
    }
}
