package com.reallanguage.app.feature.recording.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.recording.domain.RecordingPlaybackResult
import com.reallanguage.app.feature.recording.domain.TranscriptConfirmResult
import com.reallanguage.app.feature.recording.domain.TranscriptDraftSaveResult
import com.reallanguage.app.feature.recording.domain.TranscriptLoadResult
import com.reallanguage.app.feature.recording.domain.TranscriptRepairResult
import com.reallanguage.app.feature.recording.domain.TranscriptResultRepository
import com.reallanguage.app.feature.recording.domain.RecordingTranscript
import com.reallanguage.app.feature.recording.domain.TranscriptSegmentChange
import com.reallanguage.app.feature.recording.domain.TranscriptSegmentReviewRules
import com.reallanguage.app.feature.recording.domain.TranscriptSegmentReviewStatus
import com.reallanguage.app.feature.recording.domain.TranscriptSegmentUpdateResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TranscriptResultViewModel(
    private val recordingId: String,
    private val transcriptId: String,
    private val repairCommandId: String,
    private val confirmCommandId: String,
    private val repository: TranscriptResultRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(TranscriptResultUiState())
    val uiState: StateFlow<TranscriptResultUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<TranscriptResultEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var playbackJob: Job? = null
    private var draftSaveJob: Job? = null
    private var draftRevision = 0
    private var draftGeneration = 0L
    private data class PendingSegmentEdit(val segmentId: String, val text: String, val generation: Long)
    private var pendingSegmentEdit: PendingSegmentEdit? = null
    private var segmentEditJob: Job? = null
    private var segmentMutationInFlight = false
    private var segmentEditGeneration = 0L

    /** Reopening after confirmation reloads saved facts; ordinary back navigation keeps the in-memory draft. */
    fun onEntered() {
        if (mutableUiState.value.confirmed) load()
    }

    fun canLeave(): Boolean {
        val state = mutableUiState.value
        if (state.confirming || state.repairing || state.draftSaving || state.draftSaveFailed) return false
        if (!state.segmentSaving && !state.segmentDraftDirty) return true
        if (!state.segmentSaving) mutableUiState.update { it.copy(segmentError = TranscriptSegmentError.SAVE_FAILED) }
        return false
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        repository.stopPlayback()
        mutableUiState.update { it.copy(playing = false, playbackBusy = false) }
    }

    override fun onCleared() { stopPlayback(); super.onCleared() }

    init {
        repository.playbackState?.let { events -> viewModelScope.launch { events.collect { playbackChanged(it) } } }
        load()
    }

    fun onAction(action: TranscriptResultAction) {
        val current = mutableUiState.value
        if (current.confirmed) return
        if (action == TranscriptResultAction.RetryLoadRequested && (current.draftSaving || current.draftSaveFailed)) return
        if ((current.confirming || current.repairing || current.segmentSaving || current.segmentDraftDirty) && action in setOf(
                TranscriptResultAction.RetryLoadRequested,
                TranscriptResultAction.AcceptRepairRequested,
                TranscriptResultAction.KeepCurrentRequested,
            )) return
        if (action is TranscriptResultAction.TextChanged && (current.loading || current.confirming || current.repairing)) return
        when (action) {
            is TranscriptResultAction.SegmentTextEdited -> editSegmentText(action)
            is TranscriptResultAction.SegmentSelected -> if (!current.loading && !current.confirming && !current.repairing && !current.segmentSaving && !current.segmentDraftDirty &&
                action.expectedRevision == current.segmentRevision && current.segmentReviews.any { it.segmentId == action.segmentId }) {
                mutableUiState.update { it.copy(activeSegmentId = action.segmentId, segmentError = null) }
            }
            is TranscriptResultAction.SegmentChanged -> changeSegment(action)
            is TranscriptResultAction.TextChanged -> {
                if (TranscriptSegmentReviewRules.required(current.transcript?.evidence)) {
                    mutableUiState.update { it.copy(segmentError = TranscriptSegmentError.CONFIRMATION_REQUIRED) }
                    return
                }
                mutableUiState.update {
                it.copy(
                    currentText = action.text,
                    manuallyEdited = true,
                    repairComparison = null,
                    repairNotice = null,
                    error = null,
                )
                }
                saveDraft()
            }
            TranscriptResultAction.PreviewRequested -> preview()
            TranscriptResultAction.RepairRequested -> repair()
            TranscriptResultAction.AcceptRepairRequested -> acceptRepair()
            TranscriptResultAction.KeepCurrentRequested -> dismissRepair()
            TranscriptResultAction.ConfirmRequested -> confirm()
            TranscriptResultAction.RetryLoadRequested -> load()
            TranscriptResultAction.RetryDraftSaveRequested -> if (current.segmentDraftDirty) saveSegmentEdits() else saveDraft()
        }
    }

    private fun load() {
        mutableUiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = try { repository.load(transcriptId) }
            catch (cancellation: CancellationException) { throw cancellation }
            catch (_: Exception) { TranscriptLoadResult.Failed }
            if (result is TranscriptLoadResult.Ready) draftRevision = result.transcript.draftRevision
            mutableUiState.value = when (result) {
                is TranscriptLoadResult.Ready -> if (result.transcript.recordingId != recordingId || result.transcript.transcriptId != transcriptId) {
                    TranscriptResultUiState(loading = false, error = TranscriptResultError.LOAD_FAILED)
                } else TranscriptResultUiState(
                    transcript = result.transcript,
                    currentText = result.transcript.fullText,
                    manuallyEdited = result.transcript.manuallyEdited,
                    loading = false,
                    repairComparison = result.transcript.recoveredRepair as? TranscriptRepairResult.Proposed,
                    repairNotice = if (result.transcript.recoveredRepair == TranscriptRepairResult.NoChange) TranscriptRepairNotice.NO_CHANGE else null,
                    draftSaved = result.transcript.hasUnconfirmedEdits,
                    segmentReviews = result.transcript.segmentReviews,
                    activeSegmentId = nextSegment(result.transcript),
                    segmentRevision = result.transcript.draftRevision,
                    segmentError = if (TranscriptSegmentReviewRules.required(result.transcript.evidence) && result.transcript.segmentReviews.isEmpty())
                        TranscriptSegmentError.CONFIRMATION_REQUIRED else null,
                )
                TranscriptLoadResult.Failed -> TranscriptResultUiState(
                    loading = false,
                    error = TranscriptResultError.LOAD_FAILED,
                )
            }
        }
    }

    private fun preview() {
        if (mutableUiState.value.playbackBusy || mutableUiState.value.transcript == null) return
        mutableUiState.update { it.copy(playbackBusy = true, error = null) }
        playbackJob = viewModelScope.launch {
            val result = try { repository.togglePlayback(recordingId) }
            catch (cancellation: CancellationException) { throw cancellation }
            catch (_: Exception) { RecordingPlaybackResult.Failed }
            playbackChanged(
                if (result == RecordingPlaybackResult.Failed) result else repository.playbackState?.value ?: result,
                requestFinished = true,
            )
        }
    }

    private fun playbackChanged(result: RecordingPlaybackResult, requestFinished: Boolean = false) {
            mutableUiState.update {
                val busy = it.playbackBusy && !requestFinished
                when (result) {
                    RecordingPlaybackResult.Playing -> it.copy(playing = true, playbackBusy = busy)
                    RecordingPlaybackResult.Stopped -> it.copy(playing = false, playbackBusy = busy)
                    RecordingPlaybackResult.Failed -> it.copy(
                        playing = false,
                        playbackBusy = busy,
                        error = TranscriptResultError.PLAYBACK_FAILED,
                    )
                }
            }
    }

    private fun repair() {
        val state = mutableUiState.value
        if (state.repairing || state.confirming || state.draftSaving || state.draftSaveFailed || state.segmentSaving || state.segmentDraftDirty || state.transcript == null) return
        mutableUiState.update { it.copy(repairing = true, repairNotice = null, error = null) }
        viewModelScope.launch {
            val result = try {
                repository.repair(transcriptId, state.currentText, state.manuallyEdited,
                    java.util.UUID.nameUUIDFromBytes("$repairCommandId:$transcriptId:${state.manuallyEdited}:${state.currentText}".toByteArray()).toString())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                TranscriptRepairResult.Failed
            }
            mutableUiState.update {
                when (result) {
                    TranscriptRepairResult.NoChange -> it.copy(
                        repairing = false,
                        repairComparison = null,
                        repairNotice = TranscriptRepairNotice.NO_CHANGE,
                    )
                    is TranscriptRepairResult.Proposed -> it.copy(
                        repairing = false,
                        repairComparison = result,
                    )
                    TranscriptRepairResult.Failed -> it.copy(
                        repairing = false,
                        error = TranscriptResultError.REPAIR_FAILED,
                    )
                }
            }
        }
    }

    private fun dismissRepair() {
        if (mutableUiState.value.repairComparison == null) return
        mutableUiState.update { it.copy(repairing = true, error = null) }
        viewModelScope.launch {
            val saved = try { repository.dismissRepair(transcriptId) }
            catch (cancellation: CancellationException) { throw cancellation }
            catch (_: Exception) { false }
            mutableUiState.update {
                if (saved) it.copy(repairing = false, repairComparison = null)
                else it.copy(repairing = false, error = TranscriptResultError.REPAIR_FAILED)
            }
        }
    }

    private fun acceptRepair() {
        val state = mutableUiState.value
        val proposed = state.repairComparison ?: return
        if (TranscriptSegmentReviewRules.required(state.transcript?.evidence)) {
            if (state.segmentSaving || state.confirming || state.repairing) return
            segmentMutationInFlight = true
            mutableUiState.update { it.copy(segmentSaving = true, segmentError = null) }
            viewModelScope.launch {
                val result = try { repository.applyReviewedRepair(transcriptId, state.segmentRevision, proposed) }
                catch (cancellation: CancellationException) { throw cancellation }
                catch (_: Exception) { TranscriptSegmentUpdateResult.Failed }
                finishSegmentMutation(result, state.segmentRevision, null, TranscriptSegmentError.REPAIR_MAPPING_FAILED)
            }
            return
        }
        mutableUiState.update {
            it.copy(
                currentText = proposed.repairedText,
                manuallyEdited = true,
                repairComparison = null,
            )
        }
        saveDraft()
    }

    private fun nextSegment(transcript: RecordingTranscript): String? = transcript.segmentReviews.firstOrNull { review ->
        review.status != TranscriptSegmentReviewStatus.CONFIRMED && transcript.evidence?.segments?.any {
            it.segmentId == review.segmentId && it.needsConfirmation == true
        } == true
    }?.segmentId ?: transcript.segmentReviews.firstOrNull()?.segmentId

    private fun validSavedSegment(transcript: RecordingTranscript, previousRevision: Int): Boolean =
        transcript.transcriptId == transcriptId && transcript.recordingId == recordingId &&
            transcript.draftRevision == previousRevision + 1 && transcript.evidence != null &&
            transcript.evidence == mutableUiState.value.transcript?.evidence &&
            TranscriptSegmentReviewRules.valid(transcript.evidence, transcript.segmentReviews) &&
            runCatching { TranscriptSegmentReviewRules.render(transcript.evidence, transcript.segmentReviews) == transcript.fullText }.getOrDefault(false)

    private fun showSavedSegments(transcript: RecordingTranscript, selected: String?) {
        draftRevision = transcript.draftRevision
        mutableUiState.update { it.copy(
            transcript = transcript, currentText = transcript.fullText, manuallyEdited = transcript.manuallyEdited,
            segmentReviews = transcript.segmentReviews, segmentRevision = transcript.draftRevision,
            activeSegmentId = selected, segmentSaving = false, segmentError = null,
            segmentDraftText = null, segmentDraftDirty = false,
            draftSaving = false, draftSaved = true, draftSaveFailed = false, repairComparison = null, repairNotice = null, error = null,
        ) }
    }

    private fun changeSegment(action: TranscriptResultAction.SegmentChanged) {
        val state = mutableUiState.value
        if (state.loading || state.confirming || state.repairing || state.segmentSaving || state.segmentDraftDirty || state.transcript == null ||
            action.expectedRevision != state.segmentRevision || action.segmentId != state.activeSegmentId ||
            !TranscriptSegmentReviewRules.required(state.transcript.evidence)) return
        val review = state.segmentReviews.firstOrNull { it.segmentId == action.segmentId } ?: return
        if (action.change == TranscriptSegmentChange.ChooseInference && !review.unableToConfirm) return
        segmentMutationInFlight = true
        mutableUiState.update { it.copy(segmentSaving = true, segmentError = null) }
        viewModelScope.launch {
            val result = try { repository.updateSegment(transcriptId, action.segmentId, action.expectedRevision, action.change) }
            catch (cancellation: CancellationException) { throw cancellation }
            catch (_: Exception) { TranscriptSegmentUpdateResult.Failed }
            finishSegmentMutation(result, action.expectedRevision,
                if (action.change == TranscriptSegmentChange.Confirm) null else action.segmentId,
                TranscriptSegmentError.SAVE_FAILED)
        }
    }

    /** An atomic choice finishes first; later typing is rebased as an unconfirmed manual edit, never replayed as a choice. */
    private fun finishSegmentMutation(
        result: TranscriptSegmentUpdateResult,
        previousRevision: Int,
        selected: String?,
        failureError: TranscriptSegmentError,
    ) {
        segmentMutationInFlight = false
        val pending = pendingSegmentEdit
        if (result !is TranscriptSegmentUpdateResult.Saved || !validSavedSegment(result.transcript, previousRevision)) {
            mutableUiState.update { it.copy(
                segmentSaving = false, draftSaving = false,
                draftSaved = if (pending != null) false else it.draftSaved,
                draftSaveFailed = pending != null || it.draftSaveFailed,
                segmentError = if (pending != null) TranscriptSegmentError.SAVE_FAILED else failureError,
            ) }
            return
        }
        if (pending == null) {
            showSavedSegments(result.transcript, selected ?: nextSegment(result.transcript))
            return
        }
        val saved = result.transcript
        val evidence = requireNotNull(saved.evidence)
        val reviews = runCatching {
            TranscriptSegmentReviewRules.change(evidence, saved.segmentReviews, pending.segmentId, TranscriptSegmentChange.Edit(pending.text))
        }.getOrNull()
        if (reviews == null) {
            // Keep the user's visible text. A malformed result must not silently clear its retryable draft.
            mutableUiState.update { it.copy(segmentSaving = false, draftSaving = false, draftSaveFailed = true, segmentError = TranscriptSegmentError.SAVE_FAILED) }
            return
        }
        draftRevision = saved.draftRevision
        mutableUiState.update { it.copy(
            transcript = saved, currentText = TranscriptSegmentReviewRules.render(evidence, reviews),
            segmentReviews = reviews, segmentRevision = saved.draftRevision, activeSegmentId = pending.segmentId,
            segmentSaving = false, segmentDraftText = pending.text, segmentDraftDirty = true,
            manuallyEdited = true, draftSaved = false, draftSaveFailed = false, segmentError = null,
            repairComparison = null, repairNotice = null,
        ) }
        saveSegmentEdits()
    }

    private fun editSegmentText(action: TranscriptResultAction.SegmentTextEdited) {
        val state = mutableUiState.value
        if (state.loading || state.confirming || state.repairing || state.confirmed ||
            action.segmentId != state.activeSegmentId ||
            action.expectedRevision != state.segmentRevision || !TranscriptSegmentReviewRules.required(state.transcript?.evidence)) return
        val evidence = state.transcript?.evidence ?: return
        val reviews = runCatching { TranscriptSegmentReviewRules.change(evidence, state.segmentReviews, action.segmentId, TranscriptSegmentChange.Edit(action.text)) }.getOrNull() ?: return
        val text = runCatching { TranscriptSegmentReviewRules.render(evidence, reviews) }.getOrNull() ?: return
        segmentEditGeneration++
        pendingSegmentEdit = PendingSegmentEdit(action.segmentId, action.text, segmentEditGeneration)
        mutableUiState.update { it.copy(
            currentText = text, segmentReviews = reviews, segmentDraftText = action.text, segmentDraftDirty = true,
            manuallyEdited = true, draftSaved = false, draftSaveFailed = false, segmentError = null,
            repairComparison = null, repairNotice = null, error = null,
        ) }
        saveSegmentEdits()
    }

    /** Only one disk revision is in flight; later keystrokes replace the pending snapshot, never the visible text. */
    private fun saveSegmentEdits() {
        val state = mutableUiState.value
        if (pendingSegmentEdit == null || state.loading || state.confirming || state.repairing || state.confirmed ||
            segmentMutationInFlight || segmentEditJob?.isActive == true) return
        mutableUiState.update { it.copy(segmentSaving = true, draftSaving = true, draftSaveFailed = false, segmentError = null) }
        segmentEditJob = viewModelScope.launch {
            while (true) {
                val pending = pendingSegmentEdit ?: break
                val expectedRevision = mutableUiState.value.segmentRevision
                val result = try { repository.updateSegment(transcriptId, pending.segmentId, expectedRevision, TranscriptSegmentChange.Edit(pending.text)) }
                catch (cancellation: CancellationException) { throw cancellation }
                catch (_: Exception) { TranscriptSegmentUpdateResult.Failed }
                if (result !is TranscriptSegmentUpdateResult.Saved || !validSavedSegment(result.transcript, expectedRevision)) {
                    mutableUiState.update { it.copy(segmentSaving = false, draftSaving = false, draftSaved = false,
                        draftSaveFailed = true, segmentError = TranscriptSegmentError.SAVE_FAILED) }
                    break
                }
                draftRevision = result.transcript.draftRevision
                if (pendingSegmentEdit?.generation != pending.generation) {
                    // The saved prefix is acknowledged without overwriting newer unsaved characters.
                    mutableUiState.update { it.copy(transcript = result.transcript, segmentRevision = result.transcript.draftRevision) }
                    continue
                }
                pendingSegmentEdit = null
                showSavedSegments(result.transcript, pending.segmentId)
                break
            }
        }
    }

    private fun saveDraft() {
        val state = mutableUiState.value
        if (state.loading || state.confirming || state.confirmed || state.repairing || state.transcript == null) return
        draftGeneration++
        mutableUiState.update { it.copy(draftSaving = true, draftSaved = false, draftSaveFailed = false) }
        if (draftSaveJob?.isActive == true) return
        draftSaveJob = viewModelScope.launch {
            while (true) {
                val generation = draftGeneration
                val current = mutableUiState.value
                val expectedRevision = draftRevision
                val result = try { repository.saveDraft(transcriptId, current.currentText, current.manuallyEdited, expectedRevision) }
                catch (cancellation: CancellationException) { throw cancellation }
                catch (_: Exception) { TranscriptDraftSaveResult.Failed }
                if (mutableUiState.value.confirming || mutableUiState.value.confirmed) return@launch
                if (result is TranscriptDraftSaveResult.Saved && result.revision == expectedRevision + 1) {
                    draftRevision = result.revision
                    if (generation != draftGeneration) continue
                    mutableUiState.update { it.copy(draftSaving = false, draftSaved = true, draftSaveFailed = false) }
                } else {
                    mutableUiState.update { it.copy(draftSaving = false, draftSaved = false, draftSaveFailed = true) }
                }
                break
            }
        }
    }

    private fun confirm() {
        val state = mutableUiState.value
        if (state.confirming || state.repairing || state.segmentSaving || state.confirmed || state.transcript == null || state.currentText.isBlank()) return
        if (state.segmentDraftDirty) {
            mutableUiState.update { it.copy(segmentError = TranscriptSegmentError.SAVE_FAILED) }
            return
        }
        val requiresReview = TranscriptSegmentReviewRules.required(state.transcript.evidence)
        if (requiresReview && !TranscriptSegmentReviewRules.canConfirm(requireNotNull(state.transcript.evidence), state.segmentReviews)) {
            mutableUiState.update { it.copy(segmentError = TranscriptSegmentError.CONFIRMATION_REQUIRED, activeSegmentId = nextSegment(state.transcript)) }
            return
        }
        stopPlayback()
        draftSaveJob?.cancel()
        mutableUiState.update { it.copy(confirming = true, draftSaving = false, error = null) }
        viewModelScope.launch {
            val result = try {
                val commandId = java.util.UUID.nameUUIDFromBytes(
                    ("$confirmCommandId:$transcriptId:${state.currentText}" + if (requiresReview) ":review:${state.segmentRevision}" else "").toByteArray()).toString()
                if (requiresReview) repository.confirmReviewed(transcriptId, state.currentText, state.segmentRevision, commandId)
                else repository.confirm(transcriptId, state.currentText, commandId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                TranscriptConfirmResult.Failed
            }
            when (result) {
                is TranscriptConfirmResult.Confirmed -> {
                    mutableUiState.update { it.copy(confirming = false, confirmed = true) }
                    effectChannel.send(TranscriptResultEffect.OpenSafetyCheck(result.sourceId, state.currentText))
                }
                TranscriptConfirmResult.Failed -> mutableUiState.update {
                    it.copy(confirming = false, error = TranscriptResultError.CONFIRM_FAILED)
                }
            }
        }
    }
}
