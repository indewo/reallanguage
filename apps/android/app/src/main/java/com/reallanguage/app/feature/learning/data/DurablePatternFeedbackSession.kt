package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.feature.learning.domain.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

internal data class PatternFeedbackDelivery(val result: PatternPracticeFeedbackResult, val terminal: Boolean = false)

/** Ordinary LR-07 only; review restart policy is not a durable-resume policy. */
internal class DurablePatternFeedbackSession(
    private val capture: ReadSpeakAudioCapture,
    private val ownerActive: () -> Boolean,
    private val isCaptureAllowed: () -> Boolean,
    private val journalFactory: (LockedSpeakingPattern, PatternPracticeSet) -> PatternSpeakingRecoveryJournal,
    private val requestBody: (LockedSpeakingPattern, PatternPracticeExample, PatternFeedbackMode, CapturedReadSpeakAudio) -> String,
    private val execute: suspend (String) -> PatternFeedbackDelivery,
) {
    private val mutex = Mutex()
    private var generation = 0L
    private var authorized = false
    private var recording = false
    private var audio: CapturedReadSpeakAudio? = null
    private var bound: Bound? = null
    private class Bound(val pattern: LockedSpeakingPattern, val set: PatternPracticeSet, val journal: PatternSpeakingRecoveryJournal) {
        var stored: PatternSpeakingRecoveryLoad.Loaded? = null
        var working: PatternSpeakingRecoverySnapshot? = null
        var invalidated = false
    }

    suspend fun restore(pattern: LockedSpeakingPattern, set: PatternPracticeSet): PatternFeedbackRecovery = guarded(PatternFeedbackRecovery.Unavailable) {
        val stamp = generation
        val previous = bound
        val scope = if (previous?.pattern == pattern && previous.set == set) previous else {
            require(pattern.patternId == set.patternId && set.examples.size in 1..5 &&
                set.examples.map { it.exampleId }.distinct().size == set.examples.size)
            Bound(pattern, set.copy(examples = set.examples.toList()), journalFactory(pattern, set)).also {
                when (val loaded = it.journal.load()) {
                    PatternSpeakingRecoveryLoad.Unavailable -> return@guarded PatternFeedbackRecovery.Unavailable
                    PatternSpeakingRecoveryLoad.Missing -> Unit
                    is PatternSpeakingRecoveryLoad.Loaded -> { it.stored = loaded; it.working = loaded.snapshot }
                }
                if (generation != stamp || !ownerActive()) return@guarded PatternFeedbackRecovery.Unavailable
                bound = it
            }
        }
        if (!fresh(scope) || !current(scope, stamp)) return@guarded PatternFeedbackRecovery.Unavailable
        val snapshot = scope.working ?: return@guarded PatternFeedbackRecovery.None
        if (scope.set.examples.none { it.exampleId == snapshot.exampleId }) return@guarded PatternFeedbackRecovery.Unavailable
        val reading = snapshot.mode == PatternFeedbackMode.Reading
        when (snapshot.phase) {
            PatternRecoveryPhase.Pending -> PatternFeedbackRecovery.Pending(snapshot.exampleId, reading)
            PatternRecoveryPhase.Delivered -> PatternFeedbackRecovery.Delivered(snapshot.exampleId, reading, requireNotNull(snapshot.result))
            PatternRecoveryPhase.Blocked -> PatternFeedbackRecovery.Blocked(snapshot.exampleId, reading)
            PatternRecoveryPhase.Cleared -> PatternFeedbackRecovery.None
        }
    }

    suspend fun prepare(patternId: String, exampleId: String, reading: Boolean): Boolean = guarded(false) {
        val scope = bound?.takeIf { it.pattern.patternId == patternId && it.set.examples.any { e -> e.exampleId == exampleId } }
            ?: return@guarded false
        val stamp = generation
        if (!fresh(scope) || !current(scope, stamp) || scope.working?.phase == PatternRecoveryPhase.Pending) return@guarded false
        scope.working = PatternSpeakingRecoverySnapshot(UUID.randomUUID().toString(), exampleId,
            if (reading) PatternFeedbackMode.Reading else PatternFeedbackMode.Response, PatternRecoveryPhase.Cleared)
        if (!persist(scope)) return@guarded false
        synchronized(this) {
            if (!current(scope, stamp) || !isCaptureAllowed()) false else { authorized = true; true }
        }
    }

    @Synchronized fun start(): Boolean {
        if (!authorized || !ownerActive() || !isCaptureAllowed() || mutex.isLocked) return false
        authorized = false; generation++
        audio?.file?.delete(); audio = null
        if (!capture.start()) return false
        if (!ownerActive() || !isCaptureAllowed()) { capture.cancel(); return false }
        recording = true
        return true
    }

    @Synchronized fun cancel() {
        generation++; authorized = false; recording = false
        capture.cancel(); audio?.file?.delete(); audio = null
        // An already-submitted request is encrypted. Leaving only releases the live microphone/file.
    }

    @Synchronized fun pending(): Boolean {
        val scope = bound ?: return false
        return ownerActive() && !scope.invalidated && !mutex.isLocked &&
            (scope.working?.phase == PatternRecoveryPhase.Pending ||
                (scope.working?.phase == PatternRecoveryPhase.Delivered && scope.working != scope.stored?.snapshot))
    }
    @Synchronized fun blocked() = ownerActive() && bound?.working?.phase == PatternRecoveryPhase.Blocked
    suspend fun releaseSuperseded(): Boolean = guarded(false) {
        val scope = bound ?: return@guarded false
        if (!fresh(scope)) return@guarded false
        val snapshot = scope.working ?: return@guarded true
        scope.working = snapshot.copy(phase = PatternRecoveryPhase.Cleared, requestBody = null, result = null)
        if (persist(scope)) { releaseAudio(); true } else false
    }

    suspend fun feedback(patternId: String, exampleId: String, reading: Boolean): PatternPracticeFeedbackResult = guarded(PatternPracticeFeedbackResult.Failed) {
        val scope = bound?.takeIf { it.pattern.patternId == patternId } ?: return@guarded PatternPracticeFeedbackResult.Failed
        val stamp = generation
        val example = scope.set.examples.singleOrNull { it.exampleId == exampleId } ?: return@guarded PatternPracticeFeedbackResult.Failed
        if (!current(scope, stamp) || !fresh(scope)) return@guarded PatternPracticeFeedbackResult.Failed
        val snapshot = scope.working?.takeIf { it.exampleId == exampleId &&
            it.mode == if (reading) PatternFeedbackMode.Reading else PatternFeedbackMode.Response }
            ?: return@guarded PatternPracticeFeedbackResult.Failed
        if (snapshot.phase == PatternRecoveryPhase.Blocked) return@guarded PatternPracticeFeedbackResult.Failed
        if (snapshot.phase == PatternRecoveryPhase.Delivered) return@guarded if (persist(scope) && current(scope, stamp))
            requireNotNull(snapshot.result) else PatternPracticeFeedbackResult.Failed
        if (snapshot.phase == PatternRecoveryPhase.Cleared) {
            if (!recording) return@guarded PatternPracticeFeedbackResult.Failed
            // Retain the finished file if body construction itself fails; a retry never finishes twice.
            val captured = audio ?: capture.finish()?.also { audio = it } ?: return@guarded PatternPracticeFeedbackResult.Failed
            val body = requestBody(scope.pattern, example, snapshot.mode, captured)
            if (!current(scope, stamp)) return@guarded PatternPracticeFeedbackResult.Failed
            recording = false
            scope.working = snapshot.copy(phase = PatternRecoveryPhase.Pending, requestBody = body)
        }
        if (!persist(scope) || !current(scope, stamp)) return@guarded PatternPracticeFeedbackResult.Failed
        currentCoroutineContext().ensureActive()
        val delivery = execute(requireNotNull(scope.working?.requestBody))
        currentCoroutineContext().ensureActive()
        if (!current(scope, stamp)) return@guarded PatternPracticeFeedbackResult.Failed
        if (delivery.terminal) {
            scope.working = requireNotNull(scope.working).copy(phase = PatternRecoveryPhase.Blocked, requestBody = null, result = null)
            if (persist(scope)) releaseAudio()
            return@guarded PatternPracticeFeedbackResult.Failed
        }
        if (delivery.result == PatternPracticeFeedbackResult.Failed) return@guarded PatternPracticeFeedbackResult.Failed
        scope.working = requireNotNull(scope.working).copy(phase = PatternRecoveryPhase.Delivered, requestBody = null, result = delivery.result)
        if (!persist(scope) || !current(scope, stamp)) return@guarded PatternPracticeFeedbackResult.Failed
        releaseAudio()
        delivery.result
    }

    private suspend fun fresh(scope: Bound): Boolean {
        if (!ownerActive() || scope.invalidated) return false
        val loaded = scope.journal.load()
        currentCoroutineContext().ensureActive()
        if (!ownerActive()) return false
        return when (loaded) {
            PatternSpeakingRecoveryLoad.Unavailable -> false
            PatternSpeakingRecoveryLoad.Missing -> if (scope.stored == null) true else { scope.invalidated = true; false }
            is PatternSpeakingRecoveryLoad.Loaded -> when {
                loaded == scope.stored -> true
                loaded.snapshot == scope.working && loaded.revision == (scope.stored?.revision?.plus(1) ?: 0) -> {
                    scope.stored = loaded; true
                }
                else -> { scope.invalidated = true; false }
            }
        }
    }

    private suspend fun persist(scope: Bound): Boolean {
        if (!fresh(scope)) return false
        val snapshot = scope.working ?: return false
        if (scope.stored?.snapshot == snapshot) return true
        val revision = scope.stored?.revision
        val saved = scope.journal.save(revision, snapshot)
        val readBack = scope.journal.load()
        currentCoroutineContext().ensureActive()
        if (!ownerActive()) return false
        return if (readBack is PatternSpeakingRecoveryLoad.Loaded && readBack.snapshot == snapshot &&
            readBack.revision == (revision?.plus(1) ?: 0) && (saved == null || saved == readBack.revision)) {
            scope.stored = readBack; true
        } else false
    }

    @Synchronized private fun current(scope: Bound, stamp: Long) = bound === scope && generation == stamp && ownerActive() && !scope.invalidated
    @Synchronized private fun releaseAudio() { audio?.file?.delete(); audio = null }
    private suspend fun <T> guarded(failed: T, block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        if (!mutex.tryLock()) return failed
        return try { if (ownerActive()) block() else failed }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed }
        finally { mutex.unlock() }
    }
}
