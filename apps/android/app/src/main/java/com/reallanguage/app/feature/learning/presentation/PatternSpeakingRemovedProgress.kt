package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern

/**
 * Pure restoration seam. The caller must obtain removed identities from exact same-account
 * removal receipts and fixed targets, never infer that authority from this saved payload.
 */
internal fun projectPatternProgress(
    current: PatternSpeakingUiState,
    saved: Map<String, String>,
    removedPatterns: List<LockedSpeakingPattern>,
): PatternSpeakingUiState {
    val active = current.lockedPatterns
    val trusted = active + removedPatterns
    require(current.currentFocusSentenceId.isNotBlank())
    require(trusted.all {
        it.patternId.isNotBlank() && it.sourceSentenceId == current.currentFocusSentenceId &&
            it.structure.isNotBlank() && it.explanationZh.isNotBlank()
    })
    // This also rejects duplicate removal receipts and an identity classified as both active and removed.
    val trustedById = trusted.associateBy { it.patternId }
    require(trustedById.size == trusted.size)
    val activeIds = active.mapTo(mutableSetOf()) { it.patternId }
    val savedCount = saved.getValue("patternCount").toInt()
    require(savedCount in 0..trustedById.size)

    if (savedCount == 0) {
        require(active.isEmpty())
        // The old decoder cannot address an index in an empty plan. Accept only our canonical
        // completed v2 empty state, not an arbitrary damaged payload with its count changed to zero.
        val empty = clearedPatternPractice(current.copy(audioMuted = saved.getValue("muted").toBooleanStrict()),
            active, emptySet(), nextIndex = -1)
        require(saved == encodePatternProgress(empty))
        return empty
    }

    val savedIds = List(savedCount) { saved.getValue("pattern.$it.id") }
    require(savedIds.toSet().size == savedIds.size)
    require(savedIds.toSet().containsAll(activeIds))
    val originalPlan = savedIds.map { id ->
        // Saved data may identify an old target, but only the supplied fixed identities authorize it.
        requireNotNull(trustedById[id]) { "Saved target has no current or confirmed-removal identity" }
    }
    // Validate ALL original identity, v1-prefix/v2-processed and practice fields before removing
    // anything. In particular, a removed current target cannot wash away malformed old examples.
    val restored = restorePatternProgress(current.copy(lockedPatterns = originalPlan), saved)
    val processed = restored.processedPatternIds.filterTo(mutableSetOf()) { it in activeIds }
    val survivingCurrentIndex = active.indexOfFirst { it.patternId == restored.currentPattern?.patternId }
    if (survivingCurrentIndex >= 0) {
        return restored.copy(
            lockedPatterns = active,
            patternIndex = survivingCurrentIndex,
            processedPatternIds = processed,
            // A previously projected completed plan has no practice to load or retry.
            failureMessageVisible = restored.failureMessageVisible && !restored.completed,
        )
    }

    val nextIndex = active.indexOfFirst { it.patternId !in processed }
    return clearedPatternPractice(restored, active, processed, nextIndex)
}

private fun clearedPatternPractice(
    restored: PatternSpeakingUiState,
    active: List<LockedSpeakingPattern>,
    processed: Set<String>,
    nextIndex: Int,
): PatternSpeakingUiState = restored.copy(
    lockedPatterns = active,
    patternIndex = nextIndex.coerceAtLeast(0),
    stage = PatternSpeakingStage.LOADING,
    examples = emptyList(),
    completedReadingExampleIds = emptySet(),
    readingExampleId = null,
    responseIndex = 0,
    recording = false,
    submitting = false,
    transcript = "",
    feedbackZh = null,
    repeatRecommended = false,
    currentResponseCompleted = false,
    // LOADING remains explicitly retryable for an unprocessed survivor. This function performs
    // no generation, and never assigns the removed target's examples or feedback to that survivor.
    failureMessageVisible = nextIndex >= 0,
    progressFailed = false,
    completed = nextIndex < 0,
    processedPatternIds = processed,
)
