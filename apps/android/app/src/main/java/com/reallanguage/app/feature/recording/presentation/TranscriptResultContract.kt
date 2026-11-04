package com.reallanguage.app.feature.recording.presentation

import com.reallanguage.app.feature.recording.domain.RecordingTranscript
import com.reallanguage.app.feature.recording.domain.TranscriptRepairResult
import com.reallanguage.app.feature.recording.domain.TranscriptSegmentChange
import com.reallanguage.app.feature.recording.domain.TranscriptSegmentReview

data class TranscriptResultUiState(
    val transcript: RecordingTranscript? = null,
    val currentText: String = "",
    val loading: Boolean = true,
    val manuallyEdited: Boolean = false,
    val playing: Boolean = false,
    val playbackBusy: Boolean = false,
    val repairing: Boolean = false,
    val repairComparison: TranscriptRepairResult.Proposed? = null,
    val repairNotice: TranscriptRepairNotice? = null,
    val confirming: Boolean = false,
    val confirmed: Boolean = false,
    val draftSaving: Boolean = false,
    val draftSaved: Boolean = false,
    val draftSaveFailed: Boolean = false,
    val error: TranscriptResultError? = null,
    val segmentReviews: List<TranscriptSegmentReview> = emptyList(),
    val activeSegmentId: String? = null,
    val segmentRevision: Int = 0,
    val segmentSaving: Boolean = false,
    val segmentError: TranscriptSegmentError? = null,
    val segmentDraftText: String? = null,
    val segmentDraftDirty: Boolean = false,
)

enum class TranscriptRepairNotice { NO_CHANGE }
enum class TranscriptResultError { LOAD_FAILED, PLAYBACK_FAILED, REPAIR_FAILED, CONFIRM_FAILED }
enum class TranscriptSegmentError { CONFIRMATION_REQUIRED, SAVE_FAILED, REPAIR_MAPPING_FAILED }

sealed interface TranscriptResultAction {
    data class SegmentTextEdited(val segmentId: String, val expectedRevision: Int, val text: String) : TranscriptResultAction
    data class SegmentSelected(val segmentId: String, val expectedRevision: Int) : TranscriptResultAction
    data class SegmentChanged(val segmentId: String, val expectedRevision: Int, val change: TranscriptSegmentChange) : TranscriptResultAction
    data class TextChanged(val text: String) : TranscriptResultAction
    data object PreviewRequested : TranscriptResultAction
    data object RepairRequested : TranscriptResultAction
    data object AcceptRepairRequested : TranscriptResultAction
    data object KeepCurrentRequested : TranscriptResultAction
    data object ConfirmRequested : TranscriptResultAction
    data object RetryLoadRequested : TranscriptResultAction
    data object RetryDraftSaveRequested : TranscriptResultAction
}

sealed interface TranscriptResultEffect {
    data class OpenSafetyCheck(val sourceId: String, val confirmedText: String) : TranscriptResultEffect
}
