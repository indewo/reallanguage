package com.reallanguage.app.feature.recording.domain

data class RecordingTranscript(
    val transcriptId: String,
    val recordingId: String,
    val fullText: String,
    val evidence: RecordingTranscriptEvidence? = null,
    val manuallyEdited: Boolean = false,
    val draftRevision: Int = 0,
    val hasUnconfirmedEdits: Boolean = false,
    val segmentReviews: List<TranscriptSegmentReview> = emptyList(),
    val recoveredRepair: TranscriptRepairResult? = null,
)

data class RecordingTranscriptEvidence(
    val originalText: String,
    val language: String,
    val durationSeconds: Int,
    val endReason: RecordingEndReason,
    val segments: List<RecordingTranscriptSegment>,
    val acceptedEndMs: Int,
    val droppedIncompleteTail: Boolean,
)

data class RecordingTranscriptSegment(
    val segmentId: String,
    val startMs: Int,
    val endMs: Int,
    val text: String,
    val speakerLabel: String,
    val confidence: Double?,
    val alternatives: List<String>,
    val mostLikelyText: String?,
    val mostLikelyIsInference: Boolean,
    val needsConfirmation: Boolean? = null,
)

enum class TranscriptSegmentReviewStatus { PENDING, UNABLE_TO_CONFIRM, CONFIRMED }
enum class TranscriptSegmentTextOrigin { ORIGINAL, ALTERNATIVE, INFERENCE, MANUAL, AI_REPAIR }
data class TranscriptSegmentReview(
    val segmentId: String,
    val text: String,
    val status: TranscriptSegmentReviewStatus = TranscriptSegmentReviewStatus.PENDING,
    val origin: TranscriptSegmentTextOrigin = TranscriptSegmentTextOrigin.ORIGINAL,
    val unableToConfirm: Boolean = false,
    val inferenceText: String? = null,
)
sealed interface TranscriptSegmentChange {
    data object CannotConfirm : TranscriptSegmentChange
    data object ChooseOriginal : TranscriptSegmentChange
    data class ChooseAlternative(val index: Int) : TranscriptSegmentChange
    data object ChooseInference : TranscriptSegmentChange
    data class Edit(val text: String) : TranscriptSegmentChange
    data object Confirm : TranscriptSegmentChange
}
sealed interface TranscriptSegmentUpdateResult {
    data class Saved(val transcript: RecordingTranscript) : TranscriptSegmentUpdateResult
    data object Failed : TranscriptSegmentUpdateResult
}

enum class RepairReason { CONFIRMED_OMISSION, CONFIRMED_DISFLUENCY }

data class RepairChange(
    val originalText: String,
    val proposedText: String,
    val reason: RepairReason,
    val evidenceSegmentIds: List<String> = emptyList(),
)

sealed interface TranscriptLoadResult {
    data class Ready(val transcript: RecordingTranscript) : TranscriptLoadResult
    data object Failed : TranscriptLoadResult
}

sealed interface TranscriptRepairResult {
    data object NoChange : TranscriptRepairResult
    data class Proposed(
        val repairedText: String,
        val changes: List<RepairChange>,
    ) : TranscriptRepairResult
    data object Failed : TranscriptRepairResult
}

sealed interface TranscriptConfirmResult {
    data class Confirmed(val sourceId: String) : TranscriptConfirmResult
    data object Failed : TranscriptConfirmResult
}

sealed interface TranscriptDraftSaveResult {
    data class Saved(val revision: Int) : TranscriptDraftSaveResult
    data object Failed : TranscriptDraftSaveResult
}

interface TranscriptResultRepository {
    val playbackState: kotlinx.coroutines.flow.StateFlow<RecordingPlaybackResult>? get() = null
    fun stopPlayback() {}
    suspend fun load(transcriptId: String): TranscriptLoadResult
    suspend fun saveDraft(transcriptId: String, text: String, manuallyEdited: Boolean, expectedRevision: Int): TranscriptDraftSaveResult =
        TranscriptDraftSaveResult.Failed
    suspend fun updateSegment(transcriptId: String, segmentId: String, expectedRevision: Int, change: TranscriptSegmentChange): TranscriptSegmentUpdateResult =
        TranscriptSegmentUpdateResult.Failed
    suspend fun confirmReviewed(transcriptId: String, confirmedText: String, expectedRevision: Int, commandId: String): TranscriptConfirmResult =
        TranscriptConfirmResult.Failed
    suspend fun applyReviewedRepair(transcriptId: String, expectedRevision: Int, proposal: TranscriptRepairResult.Proposed): TranscriptSegmentUpdateResult =
        TranscriptSegmentUpdateResult.Failed
    suspend fun togglePlayback(recordingId: String): RecordingPlaybackResult
    suspend fun dismissRepair(transcriptId: String): Boolean = false
    suspend fun repair(
        transcriptId: String,
        currentText: String,
        manuallyEdited: Boolean,
        commandId: String,
    ): TranscriptRepairResult
    suspend fun confirm(
        transcriptId: String,
        confirmedText: String,
        commandId: String,
    ): TranscriptConfirmResult
}
