package com.reallanguage.app.core.app

import com.reallanguage.app.feature.formation.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class FixedFormationStatus { NotFixed, Fixed, Unavailable }

/** Cleanup is separate from the already committed fix; none of these results requests AI work. */
enum class FixedFormationCleanup { Cleaned, FixedCleanupPending, Unconfirmed }

class FixedFormationRecoveryCompletion(
    private val formation: FormationRecoveryRepository,
    private val fixedSources: FixedSourceRepository,
    private val discard: suspend (sourceEntryId: String, expectedRevision: Long) -> Boolean,
    private val canAccess: () -> Boolean,
) {
    suspend fun complete(expected: FixedSourceProjection): FixedFormationCleanup {
        var fixedConfirmed = false
        return try {
            if (!canAccess() || !expected.isValidFixedSource()) return FixedFormationCleanup.Unconfirmed
            val fixed = fixedSources.load(expected.formationFlowId)
            currentCoroutineContext().ensureActive()
            if (!canAccess() || fixed != FixedSourceLoad.Loaded(expected)) return FixedFormationCleanup.Unconfirmed
            fixedConfirmed = true

            val current = formation.load(expected.sourceEntryId)
            currentCoroutineContext().ensureActive()
            if (!canAccess()) return FixedFormationCleanup.Unconfirmed
            when (current) {
                // An earlier verified cleanup may already have removed this source.
                FormationRecoveryLoad.Missing -> FixedFormationCleanup.Cleaned
                FormationRecoveryLoad.Unavailable -> FixedFormationCleanup.FixedCleanupPending
                is FormationRecoveryLoad.Loaded -> {
                    // A later edit or another fixed-flow identity must never be deleted
                    // just because an older version of the same source was committed.
                    if (current.snapshot.fixedProjection() != expected) return FixedFormationCleanup.FixedCleanupPending
                    if (!canAccess()) return FixedFormationCleanup.Unconfirmed
                    val removed = discard(expected.sourceEntryId, current.revision)
                    currentCoroutineContext().ensureActive()
                    if (!canAccess()) return FixedFormationCleanup.Unconfirmed
                    if (!removed) return FixedFormationCleanup.FixedCleanupPending
                    val readback = formation.load(expected.sourceEntryId)
                    currentCoroutineContext().ensureActive()
                    when {
                        !canAccess() -> FixedFormationCleanup.Unconfirmed
                        readback == FormationRecoveryLoad.Missing -> FixedFormationCleanup.Cleaned
                        else -> FixedFormationCleanup.FixedCleanupPending
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (fixedConfirmed && canAccess()) FixedFormationCleanup.FixedCleanupPending else FixedFormationCleanup.Unconfirmed
        }
    }

    /** No deletion, backfill, chapter creation or AI generation occurs while listing entries. */
    suspend fun status(item: FormationRecoveryItem): FixedFormationStatus {
        return try {
        if (!canAccess()) FixedFormationStatus.Unavailable else {
            val current = formation.load(item.sourceEntryId) as? FormationRecoveryLoad.Loaded
                ?: return FixedFormationStatus.Unavailable
            currentCoroutineContext().ensureActive()
            if (!canAccess() || current.revision != item.revision || current.snapshot.sourceEntryId != item.sourceEntryId ||
                current.snapshot.phase != item.phase || current.snapshot.source.sourceType != item.sourceType ||
                current.snapshot.sourceTitle != item.title || !current.snapshot.isValidForRecovery()) return FixedFormationStatus.Unavailable
            if (current.snapshot.fixEntryId == null) return FixedFormationStatus.NotFixed
            val expected = current.snapshot.fixedProjection() ?: return FixedFormationStatus.Unavailable
            val fixed = fixedSources.load(expected.formationFlowId)
            currentCoroutineContext().ensureActive()
            when {
                !canAccess() -> FixedFormationStatus.Unavailable
                fixed == FixedSourceLoad.Missing -> FixedFormationStatus.NotFixed
                fixed == FixedSourceLoad.Loaded(expected) -> FixedFormationStatus.Fixed
                else -> FixedFormationStatus.Unavailable
            }
        }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { FixedFormationStatus.Unavailable }
    }
}

private fun FormationRecoverySnapshot.fixedProjection(): FixedSourceProjection? {
    val flow = fixEntryId ?: return null
    // Earlier pages may retain an unchanged complete target context after a
    // legitimate Back action. The immutable confirmed data still has to match.
    return copy(phase = FormationRecoveryPhase.FINAL_FIX).toFixedSourceProjection(flow)
}
