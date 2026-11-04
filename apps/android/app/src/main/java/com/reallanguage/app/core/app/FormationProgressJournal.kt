package com.reallanguage.app.core.app

import com.reallanguage.app.feature.formation.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Account-owned accepted progress. Returning Ready requires a saved, identical read-back. */
class FormationProgressJournal(
    private val repository: FormationRecoveryRepository,
    private val reportFailure: (FormationProgressFailure) -> Unit = {},
    private val canAccess: () -> Boolean,
) {
    private val mutex = Mutex()

    suspend fun prepare(
        route: HomeDiaryRoute,
        targetSentenceIndex: Int? = null,
        canApply: () -> Boolean = { true },
    ): FormationRecoverySnapshot? = mutex.withLock {
        // The mutex serializes writes, but only the live entry may advance or
        // revise its accepted state. A queued observer can outlive its screen.
        if (!canAccess() || !canApply()) return@withLock failure(FormationProgressFailure.ACCESS_CHANGED)
        val sourceId = formationSourceEntryId(route) ?: return@withLock failure(FormationProgressFailure.INVALID_ROUTE)
        try {
            val loaded = repository.load(sourceId)
            currentCoroutineContext().ensureActive()
            if (!canAccess() || !canApply()) return@withLock failure(FormationProgressFailure.ACCESS_CHANGED)
            var prior = loaded as? FormationRecoveryLoad.Loaded
            val text = when (route) {
                is HomeDiaryRoute.SemanticSplit -> route.text
                is HomeDiaryRoute.FocusSelection -> route.text
                is HomeDiaryRoute.ChapterFormation -> route.text
                is HomeDiaryRoute.EnglishGeneration -> route.text
                is HomeDiaryRoute.EnglishConfirmation -> route.text
                is HomeDiaryRoute.LearningTargets -> route.text
                is HomeDiaryRoute.LearningTargetSelection -> route.text
                is HomeDiaryRoute.FinalFix -> route.text
                else -> return@withLock failure(FormationProgressFailure.INVALID_ROUTE)
            }
            repeat(2) { attempt ->
                // A derived list title must not invalidate accepted source text: leading
                // blank lines are legal, and UTF-16 slicing must not split an emoji.
                val title = prior?.snapshot?.sourceTitle ?: text.lineSequence().firstOrNull { it.isNotBlank() }
                    ?.let { line -> line.substring(0, line.offsetByCodePoints(0, minOf(32, line.codePointCount(0, line.length)))) }.orEmpty()
                val position = targetSentenceIndex ?: when (route) {
                    is HomeDiaryRoute.LearningTargetSelection -> route.initialSentenceIndex
                    // The mapper rejects a FinalFix whose accepted target context
                    // no longer matches; never infer a position for changed inputs.
                    is HomeDiaryRoute.FinalFix -> prior?.snapshot?.targetSentenceIndex ?: 0
                    else -> 0
                }
                var snapshot = route.toFormationRecoverySnapshot(prior?.snapshot?.sourceRevision ?: 0,
                    title, prior?.snapshot, position)
                    ?: return@withLock failure(FormationProgressFailure.INVALID_SNAPSHOT)
                val savedPrior = prior
                if (savedPrior != null && snapshot.source != savedPrior.snapshot.source) {
                    if (savedPrior.snapshot.sourceRevision == Long.MAX_VALUE)
                        return@withLock failure(FormationProgressFailure.INVALID_SNAPSHOT)
                    snapshot = snapshot.copy(sourceRevision = savedPrior.snapshot.sourceRevision + 1)
                }
                if (!canAccess() || !canApply()) return@withLock failure(FormationProgressFailure.ACCESS_CHANGED)
                if (snapshot == prior?.snapshot) return@withLock snapshot
                when (val saved = repository.save(snapshot, prior?.revision)) {
                    is FormationRecoverySave.Saved -> {
                        currentCoroutineContext().ensureActive()
                        if (!canAccess() || !canApply()) return@withLock failure(FormationProgressFailure.ACCESS_CHANGED)
                        val verified = repository.load(sourceId) as? FormationRecoveryLoad.Loaded
                            ?: return@withLock failure(FormationProgressFailure.READBACK_MISMATCH)
                        currentCoroutineContext().ensureActive()
                        return@withLock snapshot.takeIf {
                            canAccess() && canApply() && verified.revision == saved.revision && verified.snapshot == snapshot
                        } ?: failure(FormationProgressFailure.READBACK_MISMATCH)
                    }
                    FormationRecoverySave.Conflict -> {
                        if (attempt == 1) return@withLock failure(FormationProgressFailure.REVISION_CONFLICT)
                        currentCoroutineContext().ensureActive()
                        if (!canAccess() || !canApply()) return@withLock failure(FormationProgressFailure.ACCESS_CHANGED)
                        val refreshed = repository.load(sourceId)
                        currentCoroutineContext().ensureActive()
                        if (!canAccess() || !canApply()) return@withLock failure(FormationProgressFailure.ACCESS_CHANGED)
                        when (refreshed) {
                            is FormationRecoveryLoad.Loaded -> {
                                // A lost response may report conflict even though the exact bytes
                                // are already durable. Never overwrite a different concurrent edit.
                                if (refreshed.snapshot == snapshot) return@withLock snapshot
                                return@withLock failure(FormationProgressFailure.REVISION_CONFLICT)
                            }
                            FormationRecoveryLoad.Missing -> prior = null
                            FormationRecoveryLoad.Unavailable ->
                                return@withLock failure(FormationProgressFailure.STORAGE_UNAVAILABLE)
                        }
                    }
                    FormationRecoverySave.Unavailable -> return@withLock failure(FormationProgressFailure.STORAGE_UNAVAILABLE)
                }
            }
            failure(FormationProgressFailure.REVISION_CONFLICT)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure(FormationProgressFailure.STORAGE_UNAVAILABLE) }
    }

    private fun failure(reason: FormationProgressFailure): FormationRecoverySnapshot? {
        reportFailure(reason)
        return null
    }

    suspend fun read(sourceEntryId: String): FormationRecoverySnapshot? = mutex.withLock {
        if (!canAccess()) return@withLock null
        try {
            val loaded = repository.load(sourceEntryId) as? FormationRecoveryLoad.Loaded ?: return@withLock null
            currentCoroutineContext().ensureActive()
            loaded.snapshot.takeIf { canAccess() && it.sourceEntryId == sourceEntryId && it.isValidForRecovery() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
}

enum class FormationProgressFailure {
    ACCESS_CHANGED,
    INVALID_ROUTE,
    INVALID_SNAPSHOT,
    STORAGE_UNAVAILABLE,
    REVISION_CONFLICT,
    READBACK_MISMATCH,
}
