package com.reallanguage.app.feature.recording.data

import android.content.Context
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.recording.domain.RecordingEndReason
import com.reallanguage.app.feature.recording.domain.RecordingPlaybackResult
import com.reallanguage.app.feature.recording.domain.RecordingTranscript
import com.reallanguage.app.feature.recording.domain.RecordingTranscriptEvidence
import com.reallanguage.app.feature.recording.domain.RecordingTranscriptSegment
import com.reallanguage.app.feature.recording.domain.RecordingTranscriptionRepository
import com.reallanguage.app.feature.recording.domain.RepairChange
import com.reallanguage.app.feature.recording.domain.RepairReason
import com.reallanguage.app.feature.recording.domain.TranscriptConfirmResult
import com.reallanguage.app.feature.recording.domain.TranscriptLoadResult
import com.reallanguage.app.feature.recording.domain.TranscriptRepairResult
import com.reallanguage.app.feature.recording.domain.TranscriptDraftSaveResult
import com.reallanguage.app.feature.recording.domain.TranscriptResultRepository
import com.reallanguage.app.feature.recording.domain.TranscriptSegmentChange
import com.reallanguage.app.feature.recording.domain.TranscriptSegmentReview
import com.reallanguage.app.feature.recording.domain.TranscriptSegmentReviewRules
import com.reallanguage.app.feature.recording.domain.TranscriptSegmentReviewStatus
import com.reallanguage.app.feature.recording.domain.TranscriptSegmentTextOrigin
import com.reallanguage.app.feature.recording.domain.TranscriptSegmentUpdateResult
import com.reallanguage.app.feature.recording.domain.TranscriptionRequest
import com.reallanguage.app.feature.recording.domain.TranscriptionResult
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

class HttpRecordingRepositories internal constructor(
    private val recordingsDirectory: File,
    private val transport: HttpTransport,
    private val runtime: SessionRuntime,
    private val playback: suspend (String) -> RecordingPlaybackResult,
) : RecordingTranscriptionRepository, TranscriptResultRepository {
    override val playbackState: kotlinx.coroutines.flow.StateFlow<RecordingPlaybackResult>? get() = nativePlaybackState
    private var nativePlaybackState: kotlinx.coroutines.flow.StateFlow<RecordingPlaybackResult>? = null
    private var stopPlaybackAction: () -> Unit = {}
    private val ownerAccountId = runtime.accountId
    private val ownerAccountEpoch = runtime.accountEpoch
    private fun ownsSession() = ownerAccountId != null && runtime.accountId == ownerAccountId &&
        runtime.accountEpoch == ownerAccountEpoch && runtime.accessToken != null
    private fun validId(value: String) = value.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
    constructor(context: Context, transport: HttpTransport, runtime: SessionRuntime,
        isPlaybackAllowed: () -> Boolean = { true },
    ) : this(
        runtime.accountId?.let { accountRecordingsDirectory(context.applicationContext.filesDir, it) }
            ?: File(context.applicationContext.filesDir, "recordings-unavailable"),
        transport,
        runtime,
        AndroidRecordingConfirmationRepository(context, runtime, isPlaybackAllowed),
    )

    private constructor(directory: File, transport: HttpTransport, runtime: SessionRuntime, player: AndroidRecordingConfirmationRepository) : this(
        directory, transport, runtime, player::togglePlayback,
    ) {
        stopPlaybackAction = player::stopPlayback
        nativePlaybackState = player.playbackState
    }

    override fun stopPlayback() = stopPlaybackAction()

    override suspend fun transcribe(request: TranscriptionRequest, commandId: String): TranscriptionResult =
        withContext(Dispatchers.IO) {
            if (!ownsSession() || !validId(request.recordingId) || commandId.isBlank()) return@withContext TranscriptionResult.Failed(null)
            val token = runtime.accessToken ?: return@withContext TranscriptionResult.Failed(null)
            val accountId = runtime.accountId ?: return@withContext TranscriptionResult.Failed(null)
            val audio = recordingFile(request.recordingId)
            if (!audio.isFile || request.durationSeconds !in 1..120 || request.audioFormat != AUDIO_FORMAT ||
                request.languageHint != LANGUAGE_HINT || audio.length() !in 1..MAX_AUDIO_BYTES
            ) return@withContext TranscriptionResult.Failed(null)
            existingTranscript(request.recordingId, accountId, commandId)?.let {
                return@withContext TranscriptionResult.Completed(it)
            }
            runCatching {
                val body = JSONObject()
                    .put("recordingId", request.recordingId)
                    .put("durationSeconds", request.durationSeconds)
                    .put("audioFormat", request.audioFormat)
                    .put("languageHint", request.languageHint)
                    .put("endReason", when (request.endReason) {
                        RecordingEndReason.USER_STOPPED -> "USER_STOPPED"
                        RecordingEndReason.MAX_DURATION -> "MAX_DURATION"
                    })
                    .put("audioBase64", Base64.getEncoder().encodeToString(audio.readBytes()))
                    .put("commandId", commandId)
                    .toString()
                if (!ownsSession()) return@runCatching TranscriptionResult.Failed(null)
                val response = transport.execute(HttpRequest(
                    "POST", "/api/v1/media/recording-transcriptions", body,
                    mapOf("Authorization" to "Bearer $token"),
                ))
                if (!ownsSession() || response.statusCode != 200) return@runCatching TranscriptionResult.Failed(null)
                val result = JSONObject(response.body)
                val transcriptId = result.getString("transcriptId")
                val fullText = result.getString("fullText")
                if (!validId(transcriptId) || fullText.isBlank()) return@runCatching TranscriptionResult.Failed(null)
                result.put("durationSeconds", request.durationSeconds).put("endReason", request.endReason.name)
                requireNotNull(result.transcriptEvidence())
                val local = JSONObject()
                    .put("accountId", accountId)
                    .put("recordingId", request.recordingId)
                    .put("transcriptId", transcriptId)
                    .put("fullText", fullText)
                    .put("language", result.getString("language"))
                    .put("durationSeconds", request.durationSeconds)
                    .put("endReason", request.endReason.name)
                    .put("segments", result.getJSONArray("segments"))
                    .put("finalBoundary", result.getJSONObject("finalBoundary"))
                    .put("transcriptionCommandId", commandId)
                    .put("confirmedText", JSONObject.NULL)
                    .put("confirmedSourceId", JSONObject.NULL)
                transcriptWrites.withLock {
                    if (!ownsSession()) return@withLock TranscriptionResult.Failed(null)
                    val previousId = existingTranscript(request.recordingId, accountId, commandId)
                    if (previousId != null && previousId != transcriptId) return@withLock TranscriptionResult.Failed(null)
                    val previous = loadOwnedTranscript(transcriptId)
                    if (previous != null) {
                        if (previous.getString("recordingId") != request.recordingId ||
                            previous.optString("transcriptionCommandId") != commandId ||
                            previous.getString("fullText") != fullText) return@withLock TranscriptionResult.Failed(null)
                        // Replayed recognition is not permission to erase a saved draft or confirmation.
                        return@withLock if (ownsSession()) TranscriptionResult.Completed(transcriptId) else TranscriptionResult.Failed(null)
                    }
                    if (transcriptFile(transcriptId).exists()) return@withLock TranscriptionResult.Failed(null)
                    writeAtomically(transcriptFile(transcriptId), local.toString())
                    TranscriptionResult.Completed(transcriptId)
                }
            }.getOrElse { if (it is CancellationException) throw it; TranscriptionResult.Failed(null) }
        }

    override suspend fun load(transcriptId: String): TranscriptLoadResult = withContext(Dispatchers.IO) {
        val local = loadOwnedTranscript(transcriptId) ?: return@withContext TranscriptLoadResult.Failed
        runCatching<TranscriptLoadResult> {
            val transcript = local.toTranscript()
            val recovered = recoveredRepair(local, transcript)
            if (ownsSession()) TranscriptLoadResult.Ready(transcript.copy(recoveredRepair = recovered)) else TranscriptLoadResult.Failed
        }.getOrDefault(TranscriptLoadResult.Failed)
    }

    private fun JSONObject.toTranscript(): RecordingTranscript {
        val local = this
        val hasDraft = local.has("draftText") && !local.isNull("draftText")
        val text = if (hasDraft) local.getString("draftText") else local.optionalText("confirmedText") ?: local.getString("fullText")
        val evidence = local.transcriptEvidence()
        val reviewField = if (hasDraft) "draftSegmentReviews" else "confirmedSegmentReviews"
        val reviews = if (!TranscriptSegmentReviewRules.required(evidence)) emptyList() else {
            requireNotNull(evidence)
            if (local.has(reviewField)) {
                require(local.strictInt("segmentReviewSchemaVersion") == 1)
                local.getJSONArray(reviewField).toSegmentReviews().also {
                    require(TranscriptSegmentReviewRules.valid(evidence, it))
                    require(TranscriptSegmentReviewRules.render(evidence, it) == text)
                }
            } else if (text == evidence.originalText) TranscriptSegmentReviewRules.initial(evidence)
            else emptyList() // Preserve an old client's unmapped draft; never assign it to an arbitrary segment.
        }
        return RecordingTranscript(
            transcriptId = local.getString("transcriptId"),
            recordingId = local.getString("recordingId"),
            fullText = text,
            evidence = evidence,
            manuallyEdited = if (hasDraft) local.getBoolean("draftManuallyEdited") else local.optionalText("confirmedText")?.let { it != local.getString("fullText") } ?: false,
            draftRevision = local.draftRevision(),
            hasUnconfirmedEdits = hasDraft,
            segmentReviews = reviews,
        )
    }

    override suspend fun saveDraft(transcriptId: String, text: String, manuallyEdited: Boolean, expectedRevision: Int): TranscriptDraftSaveResult =
        withContext(Dispatchers.IO) { transcriptWrites.withLock {
            if (!ownsSession() || expectedRevision < 0 || expectedRevision == Int.MAX_VALUE) return@withLock TranscriptDraftSaveResult.Failed
            val local = loadOwnedTranscript(transcriptId) ?: return@withLock TranscriptDraftSaveResult.Failed
            if (TranscriptSegmentReviewRules.required(local.transcriptEvidence())) return@withLock TranscriptDraftSaveResult.Failed
            val revision = local.draftRevision()
            if (revision != expectedRevision) {
                return@withLock if (revision == expectedRevision + 1 && local.has("draftText") &&
                    local.getString("draftText") == text && local.getBoolean("draftManuallyEdited") == manuallyEdited)
                    TranscriptDraftSaveResult.Saved(revision) else TranscriptDraftSaveResult.Failed
            }
            runCatching {
                local.put("draftText", text).put("draftManuallyEdited", manuallyEdited).put("draftRevision", revision + 1)
                writeAtomically(transcriptFile(transcriptId), local.toString())
                TranscriptDraftSaveResult.Saved(revision + 1)
            }.getOrDefault(TranscriptDraftSaveResult.Failed)
        } }

    override suspend fun updateSegment(transcriptId: String, segmentId: String, expectedRevision: Int, change: TranscriptSegmentChange): TranscriptSegmentUpdateResult {
        val identity = JSONArray().put(segmentId).put(expectedRevision).put(when (change) {
            TranscriptSegmentChange.CannotConfirm -> "CANNOT_CONFIRM"
            TranscriptSegmentChange.ChooseOriginal -> "ORIGINAL"
            is TranscriptSegmentChange.ChooseAlternative -> "ALTERNATIVE:${change.index}"
            TranscriptSegmentChange.ChooseInference -> "INFERENCE"
            is TranscriptSegmentChange.Edit -> "EDIT"
            TranscriptSegmentChange.Confirm -> "CONFIRM"
        }).apply { if (change is TranscriptSegmentChange.Edit) put(change.text) }.toString()
        return mutateSegments(transcriptId, expectedRevision, identity) { current ->
            TranscriptSegmentReviewRules.change(requireNotNull(current.evidence), current.segmentReviews, segmentId, change)
        }
    }

    override suspend fun applyReviewedRepair(transcriptId: String, expectedRevision: Int, proposal: TranscriptRepairResult.Proposed): TranscriptSegmentUpdateResult {
        val identity = JSONArray().put("REPAIR").put(expectedRevision).put(proposal.repairedText)
            .put(JSONArray().apply { proposal.changes.forEach { change -> put(JSONArray().put(change.originalText).put(change.proposedText)
                .put(change.reason.name).put(JSONArray(change.evidenceSegmentIds))) } }).toString()
        return mutateSegments(transcriptId, expectedRevision, identity) { current ->
            TranscriptSegmentReviewRules.repair(requireNotNull(current.evidence), current.segmentReviews, proposal)
        }
    }

    private suspend fun mutateSegments(transcriptId: String, expectedRevision: Int, identity: String,
        transform: (RecordingTranscript) -> List<TranscriptSegmentReview>,
    ): TranscriptSegmentUpdateResult = withContext(Dispatchers.IO) { transcriptWrites.withLock {
        if (!ownsSession() || expectedRevision < 0 || expectedRevision == Int.MAX_VALUE) return@withLock TranscriptSegmentUpdateResult.Failed
        val local = loadOwnedTranscript(transcriptId) ?: return@withLock TranscriptSegmentUpdateResult.Failed
        runCatching<TranscriptSegmentUpdateResult> {
            val current = local.toTranscript()
            require(TranscriptSegmentReviewRules.required(current.evidence))
            if (current.draftRevision != expectedRevision) {
                return@runCatching if (current.draftRevision == expectedRevision + 1 && local.optionalText("lastSegmentChange") == identity)
                    TranscriptSegmentUpdateResult.Saved(current) else TranscriptSegmentUpdateResult.Failed
            }
            val reviews = transform(current)
            val text = TranscriptSegmentReviewRules.render(requireNotNull(current.evidence), reviews)
            local.put("draftText", text).put("draftManuallyEdited", current.manuallyEdited || text != current.evidence.originalText)
                .put("draftRevision", expectedRevision + 1).put("segmentReviewSchemaVersion", 1)
                .put("draftSegmentReviews", reviews.toJson()).put("lastSegmentChange", identity)
            writeAtomically(transcriptFile(transcriptId), local.toString())
            val saved = loadOwnedTranscript(transcriptId)?.toTranscript() ?: error("Saved segment review is unavailable")
            require(saved.fullText == text && saved.segmentReviews == reviews && saved.draftRevision == expectedRevision + 1)
            TranscriptSegmentUpdateResult.Saved(saved)
        }.getOrDefault(TranscriptSegmentUpdateResult.Failed)
    } }

    override suspend fun togglePlayback(recordingId: String): RecordingPlaybackResult =
        if (ownsSession() && validId(recordingId)) playback(recordingId) else RecordingPlaybackResult.Failed

    override suspend fun repair(
        transcriptId: String,
        currentText: String,
        manuallyEdited: Boolean,
        commandId: String,
    ): TranscriptRepairResult = withContext(Dispatchers.IO) { repairRequests.withLock {
        runCatching {
            currentCoroutineContext().ensureActive()
            if (!ownsSession() || commandId.isBlank()) return@runCatching TranscriptRepairResult.Failed
            val token = runtime.accessToken ?: return@runCatching TranscriptRepairResult.Failed
            val local = loadOwnedTranscript(transcriptId) ?: return@runCatching TranscriptRepairResult.Failed
            val evidence = local.transcriptEvidence() ?: return@runCatching TranscriptRepairResult.Failed
            val input = repairInput(local, currentText, manuallyEdited)
            val hash = repairHash(input)
            val initial = local.toTranscript()
            val prepared = transcriptWrites.withLock {
                val fresh = loadOwnedTranscript(transcriptId) ?: error("Transcript unavailable")
                require(sameRepairSource(initial, fresh.toTranscript()))
                val stored = fresh.repairRecord()
                val pending = if (stored?.getString("inputHash") == hash) stored else JSONObject()
                    .put("schemaVersion", 1).put("inputHash", hash)
                    .put("commandId", UUID.nameUUIDFromBytes("$commandId:$hash".toByteArray(Charsets.UTF_8)).toString())
                    .put("dismissed", false)
                // Persist identity BEFORE the request. A lost reply must not create a second operation.
                fresh.put("repairRecovery", pending)
                writeAtomically(transcriptFile(transcriptId), fresh.toString())
                pending
            }
            prepared.optJSONObject("response")?.let { return@runCatching decodeRepair(it, local, currentText, evidence) }
            val body = input.put("commandId", prepared.getString("commandId")).toString()
            currentCoroutineContext().ensureActive()
            if (!ownsSession()) return@runCatching TranscriptRepairResult.Failed
            val response = transport.execute(HttpRequest(
                "POST", "/api/v1/media/transcript-repairs", body,
                mapOf("Authorization" to "Bearer $token"),
            ))
            currentCoroutineContext().ensureActive()
            if (!ownsSession() || response.statusCode != 200 || response.body.length > 256_000) return@runCatching TranscriptRepairResult.Failed
            val result = JSONObject(response.body)
            val decoded = decodeRepair(result, local, currentText, evidence)
            if (decoded == TranscriptRepairResult.Failed) return@runCatching decoded
            transcriptWrites.withLock {
                currentCoroutineContext().ensureActive()
                val fresh = loadOwnedTranscript(transcriptId) ?: error("Transcript unavailable")
                require(sameRepairSource(initial, fresh.toTranscript()))
                require(repairHash(repairInput(fresh, currentText, manuallyEdited)) == hash)
                val record = requireNotNull(fresh.repairRecord())
                require(record.getString("inputHash") == hash && record.getString("commandId") == prepared.getString("commandId"))
                record.put("response", result).put("dismissed", false)
                writeAtomically(transcriptFile(transcriptId), fresh.toString())
                val saved = loadOwnedTranscript(transcriptId)?.repairRecord() ?: error("Repair readback unavailable")
                require(saved.getJSONObject("response").toString() == result.toString())
                currentCoroutineContext().ensureActive()
                if (ownsSession()) decoded else TranscriptRepairResult.Failed
            }
        }.getOrElse { if (it is CancellationException) throw it; TranscriptRepairResult.Failed }
    } }

    override suspend fun dismissRepair(transcriptId: String): Boolean = withContext(Dispatchers.IO) {
        transcriptWrites.withLock { runCatching {
            currentCoroutineContext().ensureActive()
            val local = loadOwnedTranscript(transcriptId) ?: return@runCatching false
            val stored = local.repairRecord() ?: return@runCatching true
            stored.put("dismissed", true)
            writeAtomically(transcriptFile(transcriptId), local.toString())
            loadOwnedTranscript(transcriptId)?.repairRecord()?.getBoolean("dismissed") == true
        }.getOrElse { if (it is CancellationException) throw it; false } }
    }

    private fun sameRepairSource(first: RecordingTranscript, second: RecordingTranscript): Boolean =
        first == second // Includes the revision, original evidence and every independently saved segment review.

    private fun JSONObject.repairRecord(): JSONObject? {
        if (!has("repairRecovery")) return null
        return getJSONObject("repairRecovery").also {
            require(it.strictInt("schemaVersion") == 1)
            require(it.getString("inputHash").matches(Regex("[0-9a-f]{64}")))
            require(validId(it.getString("commandId")))
            require(it.get("dismissed") is Boolean)
            if (it.has("response")) it.getJSONObject("response")
        }
    }

    private fun repairHash(input: JSONObject): String = MessageDigest.getInstance("SHA-256")
        .digest(input.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun repairInput(local: JSONObject, currentText: String, manuallyEdited: Boolean): JSONObject {
        val evidence = requireNotNull(local.transcriptEvidence())
        val audio = recordingFile(local.getString("recordingId"))
        require(audio.isFile && currentText.isNotBlank() && audio.length() in 1..MAX_AUDIO_BYTES)
        return JSONObject()
                .put("transcriptId", local.getString("transcriptId"))
                .put("currentText", currentText)
                .put("manuallyEdited", manuallyEdited)
                .put("evidence", JSONObject()
                    .put("durationSeconds", evidence.durationSeconds)
                    .put("endReason", evidence.endReason.name)
                    .put("acceptedEndMs", evidence.acceptedEndMs)
                    .put("segments", JSONArray().apply { evidence.segments.forEach { segment ->
                        put(JSONObject().put("segmentId", segment.segmentId).put("startMs", segment.startMs)
                            .put("endMs", segment.endMs).put("text", segment.text))
                    } }))
                .put("audioFormat", AUDIO_FORMAT)
                .put("audioBase64", Base64.getEncoder().encodeToString(audio.readBytes()))
    }

    private fun recoveredRepair(local: JSONObject, transcript: RecordingTranscript): TranscriptRepairResult? = runCatching {
        val record = local.repairRecord() ?: return@runCatching null
        if (record.getBoolean("dismissed")) return@runCatching null
        val result = record.optJSONObject("response") ?: return@runCatching null
        if (record.getString("inputHash") != repairHash(repairInput(local, transcript.fullText, transcript.manuallyEdited))) return@runCatching null
        decodeRepair(result, local, transcript.fullText, requireNotNull(transcript.evidence)).takeUnless { it == TranscriptRepairResult.Failed }
    }.getOrNull()

    private fun decodeRepair(result: JSONObject, local: JSONObject, currentText: String, evidence: RecordingTranscriptEvidence): TranscriptRepairResult =
        when (result.getString("decision")) {
                "NO_CHANGE" -> TranscriptRepairResult.NoChange
                "PROPOSE_CHANGES" -> {
                    val changes = result.getJSONArray("changes").toRepairChanges()
                    val repairedText = result.getString("repairedText")
                    val proposal = TranscriptRepairResult.Proposed(repairedText, changes)
                    val mapped = if (TranscriptSegmentReviewRules.required(evidence)) runCatching {
                        val current = local.toTranscript()
                        require(current.fullText == currentText)
                        TranscriptSegmentReviewRules.repair(evidence, current.segmentReviews, proposal)
                    }.isSuccess else false
                    if (!mapped && !validRepair(currentText, repairedText, changes, evidence.segments.map { it.segmentId }.toSet())) TranscriptRepairResult.Failed
                    else proposal
                }
                else -> TranscriptRepairResult.Failed
            }

    private fun validRepair(currentText: String, repairedText: String, changes: List<RepairChange>, segmentIds: Set<String>): Boolean {
        if (repairedText.isBlank() || repairedText == currentText || changes.isEmpty()) return false
        var cursor = 0
        val expected = StringBuilder()
        for (change in changes) {
            if (change.originalText.isBlank() || change.proposedText.isBlank() || change.originalText == change.proposedText ||
                change.evidenceSegmentIds.isEmpty() || change.evidenceSegmentIds.any { it !in segmentIds }) return false
            val start = currentText.indexOf(change.originalText, cursor)
            if (start < 0 || currentText.indexOf(change.originalText, start + 1) >= 0) return false
            expected.append(currentText.substring(cursor, start)).append(change.proposedText)
            cursor = start + change.originalText.length
        }
        expected.append(currentText.substring(cursor))
        return expected.toString() == repairedText
    }

    override suspend fun confirm(
        transcriptId: String,
        confirmedText: String,
        commandId: String,
    ): TranscriptConfirmResult = confirmOwned(transcriptId, confirmedText, null, commandId)

    override suspend fun confirmReviewed(transcriptId: String, confirmedText: String, expectedRevision: Int, commandId: String): TranscriptConfirmResult =
        confirmOwned(transcriptId, confirmedText, expectedRevision, commandId)

    private suspend fun confirmOwned(transcriptId: String, confirmedText: String, expectedRevision: Int?, commandId: String): TranscriptConfirmResult = withContext(Dispatchers.IO) {
        transcriptWrites.withLock {
        if (!ownsSession() || confirmedText.isBlank() || commandId.isBlank()) return@withContext TranscriptConfirmResult.Failed
        val local = loadOwnedTranscript(transcriptId) ?: return@withContext TranscriptConfirmResult.Failed
        val current = runCatching { local.toTranscript() }.getOrNull() ?: return@withContext TranscriptConfirmResult.Failed
        val requiresReview = TranscriptSegmentReviewRules.required(current.evidence)
        val existing = local.optionalText("confirmedSourceId")?.takeIf { it != "null" }
        if (requiresReview) {
            if (expectedRevision == null || expectedRevision < 0) return@withContext TranscriptConfirmResult.Failed
            if (existing != null && local.optionalText("confirmationCommandId") == commandId) {
                return@withContext if (local.optionalText("confirmedText") == confirmedText &&
                    local.has("confirmedSegmentReviewRevision") && local.strictInt("confirmedSegmentReviewRevision") == expectedRevision)
                    TranscriptConfirmResult.Confirmed(existing) else TranscriptConfirmResult.Failed
            }
            if (current.draftRevision != expectedRevision || current.fullText != confirmedText ||
                !TranscriptSegmentReviewRules.canConfirm(requireNotNull(current.evidence), current.segmentReviews)) return@withContext TranscriptConfirmResult.Failed
        } else if (expectedRevision != null && current.draftRevision != expectedRevision) return@withContext TranscriptConfirmResult.Failed
        if (existing != null) return@withContext runCatching {
            if (local.optionalText("confirmationCommandId") == commandId && local.optionalText("confirmedText") != confirmedText) {
                return@runCatching TranscriptConfirmResult.Failed
            }
            if (local.optString("confirmedText") != confirmedText || local.has("draftText")) {
                local.put("confirmedText", confirmedText).put("confirmationCommandId", commandId)
                if (requiresReview) local.saveConfirmedReviews(current)
                local.clearDraftAfterConfirmation()
                writeAtomically(transcriptFile(transcriptId), local.toString())
            }
            TranscriptConfirmResult.Confirmed(existing)
        }.getOrDefault(TranscriptConfirmResult.Failed)
        runCatching {
            val sourceId = UUID.nameUUIDFromBytes(
                "$ownerAccountId:$transcriptId:$commandId".toByteArray(),
            ).toString()
            local.put("confirmedText", confirmedText).put("confirmedSourceId", sourceId)
                .put("confirmationCommandId", commandId)
            if (requiresReview) local.saveConfirmedReviews(current)
            local.clearDraftAfterConfirmation()
            writeAtomically(transcriptFile(transcriptId), local.toString())
            TranscriptConfirmResult.Confirmed(sourceId)
        }.getOrDefault(TranscriptConfirmResult.Failed)
        }
    }

    private fun JSONObject.draftRevision(): Int = if (has("draftRevision")) strictInt("draftRevision") else 0

    private fun JSONObject.saveConfirmedReviews(current: RecordingTranscript) {
        put("segmentReviewSchemaVersion", 1)
        put("confirmedSegmentReviews", current.segmentReviews.toJson())
        put("confirmedSegmentReviewRevision", current.draftRevision)
    }

    private fun JSONObject.clearDraftAfterConfirmation() {
        val revision = draftRevision()
        check(revision < Int.MAX_VALUE)
        remove("draftText")
        remove("draftManuallyEdited")
        remove("draftSegmentReviews")
        remove("lastSegmentChange")
        put("draftRevision", revision + 1)
    }

    private fun loadOwnedTranscript(transcriptId: String): JSONObject? = runCatching {
        if (!ownsSession() || !validId(transcriptId)) return null
        val accountId = runtime.accountId ?: return null
        val file = transcriptFile(transcriptId)
        if (!file.isFile) return null
        JSONObject(file.readText()).takeIf { it.getString("accountId") == accountId &&
            validId(it.getString("recordingId")) && it.getString("transcriptId") == transcriptId }
            ?.also {
                it.transcriptEvidence()
                it.draftRevision()
                if (it.has("draftText")) { require(it.get("draftText") is String); require(it.get("draftManuallyEdited") is Boolean) }
            }
            ?.takeIf { ownsSession() }
    }.getOrNull()

    private fun JSONObject.optionalText(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    private fun existingTranscript(recordingId: String, accountId: String, commandId: String): String? {
        if (!ownsSession()) return null
        val files = recordingsDirectory.listFiles { file -> file.name.endsWith(TRANSCRIPT_SUFFIX) }.orEmpty()
        return files.firstNotNullOfOrNull { file -> runCatching {
            JSONObject(file.readText()).takeIf {
                it.getString("accountId") == accountId && it.getString("recordingId") == recordingId &&
                    it.getString("transcriptionCommandId") == commandId
            }?.also { it.transcriptEvidence() }?.getString("transcriptId")?.takeIf(::validId)
        }.getOrNull() }?.takeIf { ownsSession() }
    }

    private fun JSONObject.transcriptEvidence(): RecordingTranscriptEvidence? {
        // Old local transcripts did not retain evidence. Do not manufacture it or discard their text.
        if (!has("segments") && !has("finalBoundary") && !has("durationSeconds")) return null
        val duration = strictInt("durationSeconds").also { require(it in 1..120) }
        val reason = RecordingEndReason.valueOf(getString("endReason"))
        val language = getString("language").also { require(it.isNotBlank()) }
        val original = getString("fullText").also { require(it.isNotBlank()) }
        val boundary = getJSONObject("finalBoundary")
        val acceptedEnd = boundary.strictInt("acceptedEndMs").also { require(it in 0..duration * 1000) }
        val dropped = boundary.get("droppedIncompleteTail") as Boolean
        require(!dropped || reason == RecordingEndReason.MAX_DURATION)
        var previousEnd = 0
        val ids = mutableSetOf<String>()
        val values = getJSONArray("segments").also { require(it.length() in 1..4000) }
        val segments = List(values.length()) { index ->
            val value = values.getJSONObject(index)
            val id = value.getString("segmentId").also { require(it.isNotBlank() && ids.add(it)) }
            val start = value.strictInt("startMs")
            val end = value.strictInt("endMs")
            require(start >= previousEnd && end >= start && end <= acceptedEnd)
            previousEnd = end
            val confidence = if (value.isNull("confidence")) null else (value.get("confidence") as Number).toDouble()
            require(confidence == null || (confidence.isFinite() && confidence in 0.0..1.0))
            val inference = value.get("mostLikelyIsInference") as Boolean
            val candidate = value.optionalText("mostLikelyText")
            require(!inference || candidate != null)
            val needsConfirmation = if (value.has("needsConfirmation")) value.get("needsConfirmation") as Boolean else null
            val alternatives = value.getJSONArray("alternatives").let { items -> List(items.length()) { items.get(it).let { text ->
                require(text is String && text.isNotBlank()); text
            } } }
            if (needsConfirmation != null) {
                val rawCandidate = value.get("mostLikelyText")
                require(rawCandidate === JSONObject.NULL || (rawCandidate is String && rawCandidate.isNotBlank()))
                require(alternatives.map(String::trim).distinct().size == alternatives.size)
                require((candidate != null) == inference)
                require(needsConfirmation || (alternatives.isEmpty() && candidate == null))
            }
            RecordingTranscriptSegment(
                id, start, end, value.getString("text").also { require(it.isNotBlank()) },
                value.getString("speakerLabel").also { require(it in setOf("USER", "OTHER", "UNKNOWN")) },
                confidence,
                alternatives,
                candidate, inference, needsConfirmation,
            )
        }
        require(original.filterNot(Char::isWhitespace) == segments.joinToString("") { it.text }.filterNot(Char::isWhitespace))
        return RecordingTranscriptEvidence(original, language, duration, reason, segments, acceptedEnd, dropped)
    }

    private fun JSONObject.strictInt(key: String): Int {
        val value = (get(key) as Number).toDouble()
        require(value.isFinite() && value >= 0 && value <= Int.MAX_VALUE && value == value.toInt().toDouble())
        return value.toInt()
    }

    private fun List<TranscriptSegmentReview>.toJson() = JSONArray().apply {
        forEach { review -> put(JSONObject().put("segmentId", review.segmentId).put("text", review.text)
            .put("status", review.status.name).put("origin", review.origin.name).put("unableToConfirm", review.unableToConfirm)
            .put("inferenceText", review.inferenceText ?: JSONObject.NULL)) }
    }

    private fun JSONArray.toSegmentReviews(): List<TranscriptSegmentReview> = List(length()) { index ->
        getJSONObject(index).let { value ->
            require(value.get("text") is String && value.get("segmentId") is String)
            val inference = value.get("inferenceText").let { if (it === JSONObject.NULL) null else (it as String).also { text -> require(text.isNotBlank()) } }
            TranscriptSegmentReview(value.getString("segmentId"), value.getString("text"),
                TranscriptSegmentReviewStatus.valueOf(value.getString("status")),
                TranscriptSegmentTextOrigin.valueOf(value.getString("origin")), value.get("unableToConfirm") as Boolean, inference)
        }
    }

    private fun JSONArray.toRepairChanges(): List<RepairChange> = List(length()) { index ->
        getJSONObject(index).let { value ->
            RepairChange(
                originalText = value.getString("originalText"),
                proposedText = value.getString("proposedText"),
                reason = when (value.getString("reason")) {
                    "CONFIRMED_OMISSION" -> RepairReason.CONFIRMED_OMISSION
                    "CONFIRMED_DISFLUENCY" -> RepairReason.CONFIRMED_DISFLUENCY
                    else -> error("Unsupported repair reason")
                },
                evidenceSegmentIds = value.getJSONArray("evidenceSegmentIds").let { ids ->
                    List(ids.length()) { ids.getString(it) }
                },
            )
        }
    }

    private fun writeAtomically(file: File, contents: String) {
        check(ownsSession())
        check(file.canonicalFile.parentFile == recordingsDirectory.canonicalFile)
        recordingsDirectory.mkdirs()
        val temporary = File.createTempFile("transcript-", ".tmp", recordingsDirectory)
        try {
            temporary.writeText(contents)
            check(ownsSession())
            check(temporary.renameTo(file))
            check(file.readText() == contents)
            check(ownsSession())
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun recordingFile(recordingId: String) = File(recordingsDirectory, "$recordingId.m4a")
    private fun transcriptFile(transcriptId: String) = File(recordingsDirectory, "$transcriptId$TRANSCRIPT_SUFFIX")

    private companion object {
        val transcriptWrites = Mutex()
        val repairRequests = Mutex()
        const val RECORDINGS_DIRECTORY = "recordings"
        const val TRANSCRIPT_SUFFIX = ".transcript.json"
        const val AUDIO_FORMAT = "audio/mp4"
        const val LANGUAGE_HINT = "zh-CN"
        const val MAX_AUDIO_BYTES = 6_000_000L
    }
}
