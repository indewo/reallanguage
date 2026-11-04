package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.feature.formation.data.FormationPayloadOwner
import com.reallanguage.app.feature.formation.data.FormationPayloadLoad
import com.reallanguage.app.feature.formation.data.FormationPayloadSave
import com.reallanguage.app.feature.formation.data.FormationRecoveryFileStore
import com.reallanguage.app.feature.learning.domain.ReadingFeedback
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackAction
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackAspect
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackDetail
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackEvidence
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationCandidate
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationDecision
import com.reallanguage.app.feature.learning.domain.SpeakingTranscription
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class FullSpeakingRecoveryPhase {
    TrainingPending, TrainingDelivered, TranscriptionPending, TranscriptionDelivered,
    EvaluationPending, EvaluationDelivered, Blocked, Cleared,
}

data class FullSpeakingRecoverySnapshot(
    val recordingId: String,
    val phase: FullSpeakingRecoveryPhase,
    val requestBody: String? = null,
    val training: ReadingFeedback? = null,
    val transcription: SpeakingTranscription? = null,
    val evaluation: SpeakingEvaluationCandidate? = null,
    val confirmedTranscript: String? = null,
    val answerAudioPlayed: Boolean = false,
)

sealed interface FullSpeakingRecoveryLoad {
    data object Missing : FullSpeakingRecoveryLoad
    data object Unavailable : FullSpeakingRecoveryLoad
    data class Loaded(val revision: Long, val snapshot: FullSpeakingRecoverySnapshot) : FullSpeakingRecoveryLoad
}

interface FullSpeakingRecoveryJournal {
    suspend fun load(): FullSpeakingRecoveryLoad
    suspend fun save(expectedRevision: Long?, snapshot: FullSpeakingRecoverySnapshot): Long?
}

/** Ordinary LR-06 only. The factory supplies the exact account/sentence/round access capability. */
class EncryptedFullSpeakingRecoveryJournal(
    private val store: FormationRecoveryFileStore,
    private val owner: FormationPayloadOwner,
) : FullSpeakingRecoveryJournal {
    override suspend fun load(): FullSpeakingRecoveryLoad = withContext(Dispatchers.IO) {
        try {
            currentCoroutineContext().ensureActive()
            when (val stored = store.load(owner)) {
                FormationPayloadLoad.Missing -> if (store.hasCurrentAccess(owner)) FullSpeakingRecoveryLoad.Missing
                    else FullSpeakingRecoveryLoad.Unavailable
                FormationPayloadLoad.Unavailable -> FullSpeakingRecoveryLoad.Unavailable
                is FormationPayloadLoad.Loaded -> try {
                    require(stored.identity.owner == owner && stored.identity.payloadVersion == 1)
                    val snapshot = FullSpeakingRecoveryCodec.decode(stored.plaintext)
                    currentCoroutineContext().ensureActive()
                    if (store.hasCurrentAccess(owner)) FullSpeakingRecoveryLoad.Loaded(stored.identity.revision, snapshot)
                    else FullSpeakingRecoveryLoad.Unavailable
                } finally { stored.plaintext.fill(0) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { FullSpeakingRecoveryLoad.Unavailable }
    }

    override suspend fun save(expectedRevision: Long?, snapshot: FullSpeakingRecoverySnapshot): Long? = withContext(Dispatchers.IO) {
        try {
            // An authenticated envelope with an invalid DTO must not become an overwritable empty attempt.
            if (load() == FullSpeakingRecoveryLoad.Unavailable) return@withContext null
            val captured = snapshot.copy(transcription = snapshot.transcription?.let {
                require(it.lowConfidenceSegments.size <= 15_000_000 / 4)
                it.copy(lowConfidenceSegments = it.lowConfidenceSegments.toList())
            })
            val encoded = FullSpeakingRecoveryCodec.encode(captured)
            try {
                currentCoroutineContext().ensureActive()
                when (val saved = store.compareAndSet(owner, expectedRevision, 1, encoded)) {
                    FormationPayloadSave.Conflict, FormationPayloadSave.Failed -> null
                    is FormationPayloadSave.Saved -> {
                        val readBack = load()
                        currentCoroutineContext().ensureActive()
                        if (readBack == FullSpeakingRecoveryLoad.Loaded(saved.revision, captured) && store.hasCurrentAccess(owner))
                            saved.revision else null
                    }
                }
            } finally { encoded.fill(0) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
}

/** Independent RLR6/version 2; decoder remains compatible with version 1 attempts. */
private object FullSpeakingRecoveryCodec {
    private const val MAGIC = 0x524c5236
    private const val VERSION = 2
    private const val MAX_RECORDING_BYTES = 1024
    private const val MAX_BODY_BYTES = 12_000_000
    private const val MAX_PAYLOAD_BYTES = 15_000_000

    fun encode(snapshot: FullSpeakingRecoverySnapshot): ByteArray {
        validate(snapshot)
        // Bound the whole record before allocating UTF-8 arrays or creating a key/envelope.
        val size = encodedSize(snapshot)
        val bytes = WipingOutput(size)
        return try {
            val output = DataOutputStream(bytes)
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeInt(snapshot.phase.code())
            output.writeText(snapshot.recordingId)
            output.writeOptionalText(snapshot.requestBody)
            output.writeFlag(snapshot.training != null)
            snapshot.training?.let {
                output.writeInt(when (it.action) {
                    ReadingFeedbackAction.CONTINUE -> 1
                    ReadingFeedbackAction.REPEAT_FOR_CLARITY -> 2
                })
                output.writeText(it.feedbackZh)
                output.writeFlag(it.feedbackConfidence != null)
                it.feedbackConfidence?.let(output::writeDouble)
                output.writeInt(it.details.size)
                it.details.forEach { detail ->
                    output.writeInt(detail.aspect.code())
                    output.writeOptionalText(detail.targetText)
                    output.writeText(detail.observationZh)
                    output.writeText(detail.suggestionZh)
                    output.writeInt(detail.evidence.code())
                }
            }
            output.writeFlag(snapshot.transcription != null)
            snapshot.transcription?.let {
                output.writeText(it.transcript)
                output.writeInt(it.lowConfidenceSegments.size)
                it.lowConfidenceSegments.forEach { segment -> output.writeText(segment) }
                output.writeFlag(it.confidence != null)
                it.confidence?.let(output::writeDouble)
            }
            output.writeFlag(snapshot.evaluation != null)
            snapshot.evaluation?.let {
                output.writeInt(when (it.decision) {
                    SpeakingEvaluationDecision.PASS -> 1
                    SpeakingEvaluationDecision.FAIL -> 2
                    SpeakingEvaluationDecision.INCONCLUSIVE -> 3
                })
                output.writeInt(it.completenessScore)
                output.writeFlag(it.coreMeaningRecognizable)
                output.writeFlag(it.pronunciationIntelligible)
                output.writeText(it.feedbackZh)
            }
            output.writeOptionalText(snapshot.confirmedTranscript)
            output.writeFlag(snapshot.answerAudioPlayed)
            output.flush()
            require(bytes.size() == size)
            bytes.toByteArray()
        } finally { bytes.erase() }
    }

    fun decode(bytes: ByteArray): FullSpeakingRecoverySnapshot {
        require(bytes.size in 1..MAX_PAYLOAD_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC)
            val version = input.readInt()
            require(version in 1..VERSION)
            val phase = when (input.readInt()) {
                1 -> FullSpeakingRecoveryPhase.TrainingPending
                2 -> FullSpeakingRecoveryPhase.TrainingDelivered
                3 -> FullSpeakingRecoveryPhase.TranscriptionPending
                4 -> FullSpeakingRecoveryPhase.TranscriptionDelivered
                5 -> FullSpeakingRecoveryPhase.EvaluationPending
                6 -> FullSpeakingRecoveryPhase.EvaluationDelivered
                7 -> FullSpeakingRecoveryPhase.Blocked
                8 -> FullSpeakingRecoveryPhase.Cleared
                else -> error("Unknown LR06 recovery phase")
            }
            val recordingId = input.readText(MAX_RECORDING_BYTES)
            val body = input.readOptionalText(MAX_BODY_BYTES)
            val training = if (input.readFlag()) {
                val action = when (input.readInt()) {
                    1 -> ReadingFeedbackAction.CONTINUE
                    2 -> ReadingFeedbackAction.REPEAT_FOR_CLARITY
                    else -> error("Unknown training feedback action")
                }
                val feedbackZh = input.readText()
                if (version == 1) {
                    ReadingFeedback(feedbackZh, action)
                } else {
                    val confidence = if (input.readFlag()) input.readDouble() else null
                    val detailCount = input.readInt()
                    require(detailCount in 0..3)
                    val details = List(detailCount) {
                        ReadingFeedbackDetail(
                            aspect = when (input.readInt()) {
                                1 -> ReadingFeedbackAspect.WORD_OR_PHRASE
                                2 -> ReadingFeedbackAspect.RHYTHM
                                3 -> ReadingFeedbackAspect.COMPLETENESS
                                else -> error("Unknown reading feedback aspect")
                            },
                            targetText = input.readOptionalText(MAX_PAYLOAD_BYTES),
                            observationZh = input.readText(),
                            suggestionZh = input.readText(),
                            evidence = when (input.readInt()) {
                                1 -> ReadingFeedbackEvidence.AUDIO
                                2 -> ReadingFeedbackEvidence.TRANSCRIPT
                                3 -> ReadingFeedbackEvidence.GENERAL
                                else -> error("Unknown reading feedback evidence")
                            },
                        )
                    }
                    ReadingFeedback(feedbackZh, action, confidence, details)
                }
            } else null
            val transcription = if (input.readFlag()) {
                val transcript = input.readText()
                val count = input.readInt()
                // Every segment needs at least a four-byte length; no allocation from an unbounded count.
                require(count >= 0 && count <= input.available() / 4)
                val segments = ArrayList<String>(minOf(count, 256))
                repeat(count) { segments.add(input.readText()) }
                val confidence = if (input.readFlag()) input.readDouble() else null
                SpeakingTranscription(transcript, segments, confidence)
            } else null
            val evaluation = if (input.readFlag()) {
                val decision = when (input.readInt()) {
                    1 -> SpeakingEvaluationDecision.PASS
                    2 -> SpeakingEvaluationDecision.FAIL
                    3 -> SpeakingEvaluationDecision.INCONCLUSIVE
                    else -> error("Unknown evaluation decision")
                }
                SpeakingEvaluationCandidate(decision, input.readInt(), input.readFlag(), input.readFlag(), input.readText())
            } else null
            val confirmed = input.readOptionalText(MAX_PAYLOAD_BYTES)
            val played = input.readFlag()
            require(input.available() == 0)
            FullSpeakingRecoverySnapshot(recordingId, phase, body, training, transcription, evaluation, confirmed, played)
                .also(::validate)
        }
    }

    private fun validate(snapshot: FullSpeakingRecoverySnapshot) {
        require(snapshot.recordingId.isNotBlank())
        require(snapshot.requestBody == null || snapshot.requestBody.isNotBlank())
        snapshot.training?.let { training ->
            require(training.feedbackZh.isNotBlank())
            training.feedbackConfidence?.let { require(it.isFinite() && it in 0.0..1.0) }
            require(training.details.size <= 3)
            require(training.details.distinctBy { it.aspect }.size == training.details.size)
            training.details.forEach { detail ->
                require(detail.targetText == null || detail.targetText.isNotBlank())
                require(detail.observationZh.isNotBlank() && detail.suggestionZh.isNotBlank())
            }
        }
        require(snapshot.confirmedTranscript == null || snapshot.confirmedTranscript.isNotBlank())
        snapshot.transcription?.confidence?.let { require(it.isFinite() && it in 0.0..1.0) }
        snapshot.evaluation?.let { require(it.completenessScore in 0..100 && it.feedbackZh.isNotBlank()) }
        val hasBody = snapshot.requestBody != null
        val hasTraining = snapshot.training != null
        val hasTranscription = snapshot.transcription != null
        val hasEvaluation = snapshot.evaluation != null
        val hasConfirmed = snapshot.confirmedTranscript != null
        require(when (snapshot.phase) {
            FullSpeakingRecoveryPhase.TrainingPending, FullSpeakingRecoveryPhase.TranscriptionPending ->
                hasBody && !hasTraining && !hasTranscription && !hasEvaluation && !hasConfirmed && !snapshot.answerAudioPlayed
            FullSpeakingRecoveryPhase.TrainingDelivered ->
                !hasBody && hasTraining && !hasTranscription && !hasEvaluation && !hasConfirmed && !snapshot.answerAudioPlayed
            FullSpeakingRecoveryPhase.TranscriptionDelivered ->
                hasBody && !hasTraining && hasTranscription && !hasEvaluation && !hasConfirmed && !snapshot.answerAudioPlayed
            FullSpeakingRecoveryPhase.EvaluationPending ->
                hasBody && !hasTraining && hasTranscription && !hasEvaluation && hasConfirmed
            FullSpeakingRecoveryPhase.EvaluationDelivered ->
                !hasBody && !hasTraining && hasTranscription && hasEvaluation && hasConfirmed
            FullSpeakingRecoveryPhase.Blocked, FullSpeakingRecoveryPhase.Cleared ->
                !hasBody && !hasTraining && !hasTranscription && !hasEvaluation && !hasConfirmed && !snapshot.answerAudioPlayed
        })
    }

    private fun encodedSize(snapshot: FullSpeakingRecoverySnapshot): Int {
        // Header, three optional-result flags, and final played flag.
        var size = 12L + 4L
        fun addText(value: String?, limit: Int = MAX_PAYLOAD_BYTES) {
            size += 4L + (value?.let { utf8Length(it, limit) } ?: 0)
            require(size <= MAX_PAYLOAD_BYTES)
        }
        addText(snapshot.recordingId, MAX_RECORDING_BYTES)
        addText(snapshot.requestBody, MAX_BODY_BYTES)
        snapshot.training?.let {
            size += 4L + 1L + if (it.feedbackConfidence != null) 8L else 0L
            addText(it.feedbackZh)
            size += 4L
            it.details.forEach { detail ->
                size += 4L + 4L
                addText(detail.targetText)
                addText(detail.observationZh)
                addText(detail.suggestionZh)
            }
        }
        snapshot.transcription?.let {
            addText(it.transcript)
            require(it.lowConfidenceSegments.size <= MAX_PAYLOAD_BYTES / 4)
            size += 4L + 1L + if (it.confidence != null) 8L else 0L
            it.lowConfidenceSegments.forEach { segment -> addText(segment) }
        }
        snapshot.evaluation?.let {
            size += 4L + 4L + 1L + 1L
            addText(it.feedbackZh)
        }
        addText(snapshot.confirmedTranscript)
        require(size <= MAX_PAYLOAD_BYTES)
        return size.toInt()
    }

    private fun utf8Length(value: String, limit: Int): Int {
        require(value.length <= limit)
        var count = 0
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            count += when {
                character.code < 0x80 -> 1
                character.code < 0x800 -> 2
                character.isHighSurrogate() -> {
                    require(index < value.length && value[index++].isLowSurrogate())
                    4
                }
                character.isLowSurrogate() -> error("Unpaired UTF-16 surrogate")
                else -> 3
            }
            require(count <= limit)
        }
        return count
    }

    private fun FullSpeakingRecoveryPhase.code() = when (this) {
        FullSpeakingRecoveryPhase.TrainingPending -> 1
        FullSpeakingRecoveryPhase.TrainingDelivered -> 2
        FullSpeakingRecoveryPhase.TranscriptionPending -> 3
        FullSpeakingRecoveryPhase.TranscriptionDelivered -> 4
        FullSpeakingRecoveryPhase.EvaluationPending -> 5
        FullSpeakingRecoveryPhase.EvaluationDelivered -> 6
        FullSpeakingRecoveryPhase.Blocked -> 7
        FullSpeakingRecoveryPhase.Cleared -> 8
    }

    private fun ReadingFeedbackAspect.code() = when (this) {
        ReadingFeedbackAspect.WORD_OR_PHRASE -> 1
        ReadingFeedbackAspect.RHYTHM -> 2
        ReadingFeedbackAspect.COMPLETENESS -> 3
    }

    private fun ReadingFeedbackEvidence.code() = when (this) {
        ReadingFeedbackEvidence.AUDIO -> 1
        ReadingFeedbackEvidence.TRANSCRIPT -> 2
        ReadingFeedbackEvidence.GENERAL -> 3
    }

    private fun DataOutputStream.writeFlag(value: Boolean) = writeByte(if (value) 1 else 0)
    private fun DataInputStream.readFlag(): Boolean = when (readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> error("Invalid LR06 boolean or presence marker")
    }

    private fun DataOutputStream.writeOptionalText(value: String?) {
        if (value == null) writeInt(-1) else writeText(value)
    }

    private fun DataOutputStream.writeText(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        try { writeInt(encoded.size); write(encoded) }
        finally { encoded.fill(0) }
    }

    private fun DataInputStream.readOptionalText(limit: Int): String? {
        val length = readInt()
        return if (length == -1) null else readText(limit, length)
    }

    private fun DataInputStream.readText(limit: Int = MAX_PAYLOAD_BYTES, length: Int = readInt()): String {
        require(length in 0..limit && length <= available())
        val bytes = ByteArray(length)
        return try {
            readFully(bytes)
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } finally { bytes.fill(0) }
    }

    private class WipingOutput(size: Int) : ByteArrayOutputStream(size) {
        fun erase() { buf.fill(0); reset() }
    }
}
