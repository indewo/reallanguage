package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.feature.formation.data.FormationPayloadOwner
import com.reallanguage.app.feature.formation.data.FormationPayloadLoad
import com.reallanguage.app.feature.formation.data.FormationPayloadSave
import com.reallanguage.app.feature.formation.data.FormationRecoveryFileStore
import com.reallanguage.app.feature.learning.domain.PatternPracticeFeedback
import com.reallanguage.app.feature.learning.domain.PatternPracticeFeedbackResult
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

enum class PatternFeedbackMode { Reading, Response }
enum class PatternRecoveryPhase { Pending, Delivered, Blocked, Cleared }

data class PatternSpeakingRecoverySnapshot(
    val attemptId: String,
    val exampleId: String,
    val mode: PatternFeedbackMode,
    val phase: PatternRecoveryPhase,
    val requestBody: String? = null,
    val result: PatternPracticeFeedbackResult? = null,
)

sealed interface PatternSpeakingRecoveryLoad {
    data object Missing : PatternSpeakingRecoveryLoad
    data object Unavailable : PatternSpeakingRecoveryLoad
    data class Loaded(val revision: Long, val snapshot: PatternSpeakingRecoverySnapshot) : PatternSpeakingRecoveryLoad
}

interface PatternSpeakingRecoveryJournal {
    suspend fun load(): PatternSpeakingRecoveryLoad
    suspend fun save(expectedRevision: Long?, snapshot: PatternSpeakingRecoverySnapshot): Long?
}

/** Ordinary LR-07 feedback only; the Android owner binds the fixed target and saved practice. */
class EncryptedPatternSpeakingRecoveryJournal(
    private val store: FormationRecoveryFileStore,
    private val owner: FormationPayloadOwner,
) : PatternSpeakingRecoveryJournal {
    override suspend fun load(): PatternSpeakingRecoveryLoad = withContext(Dispatchers.IO) {
        try {
            currentCoroutineContext().ensureActive()
            when (val stored = store.load(owner)) {
                FormationPayloadLoad.Missing -> if (store.hasCurrentAccess(owner)) PatternSpeakingRecoveryLoad.Missing
                    else PatternSpeakingRecoveryLoad.Unavailable
                FormationPayloadLoad.Unavailable -> PatternSpeakingRecoveryLoad.Unavailable
                is FormationPayloadLoad.Loaded -> try {
                    require(stored.identity.owner == owner && stored.identity.payloadVersion == 1)
                    val snapshot = PatternRecoveryCodec.decode(stored.plaintext)
                    currentCoroutineContext().ensureActive()
                    if (store.hasCurrentAccess(owner)) PatternSpeakingRecoveryLoad.Loaded(stored.identity.revision, snapshot)
                    else PatternSpeakingRecoveryLoad.Unavailable
                } finally { stored.plaintext.fill(0) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { PatternSpeakingRecoveryLoad.Unavailable }
    }

    override suspend fun save(expectedRevision: Long?, snapshot: PatternSpeakingRecoverySnapshot): Long? = withContext(Dispatchers.IO) {
        try {
            if (load() == PatternSpeakingRecoveryLoad.Unavailable) return@withContext null
            val encoded = PatternRecoveryCodec.encode(snapshot)
            try {
                currentCoroutineContext().ensureActive()
                when (val saved = store.compareAndSet(owner, expectedRevision, 1, encoded)) {
                    FormationPayloadSave.Conflict, FormationPayloadSave.Failed -> null
                    is FormationPayloadSave.Saved -> {
                        val readBack = load()
                        currentCoroutineContext().ensureActive()
                        if (readBack == PatternSpeakingRecoveryLoad.Loaded(saved.revision, snapshot) && store.hasCurrentAccess(owner))
                            saved.revision else null
                    }
                }
            } finally { encoded.fill(0) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
}

/** Independent LR-07 binary version, strict UTF-8 and bounded allocations; never Java serialization. */
private object PatternRecoveryCodec {
    private const val MAGIC = 0x524c5237
    private const val MAX_BODY = 12_000_000
    private const val MAX_TEXT = 1_000_000
    private const val MAX_PAYLOAD = 15_000_000

    fun encode(snapshot: PatternSpeakingRecoverySnapshot): ByteArray {
        validate(snapshot)
        val bytes = WipingOutput()
        return try {
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC); out.writeInt(1)
                out.writeInt(snapshot.phase.ordinal + 1); out.writeInt(snapshot.mode.ordinal + 1)
                out.text(snapshot.attemptId, 1024); out.text(snapshot.exampleId, 1024)
                if (snapshot.requestBody == null) out.writeInt(-1) else out.text(snapshot.requestBody, MAX_BODY)
                when (val result = snapshot.result) {
                    null -> out.writeInt(0)
                    is PatternPracticeFeedbackResult.Ready -> {
                        out.writeInt(1); out.text(result.feedback.feedbackZh, MAX_TEXT)
                        out.writeByte(if (result.feedback.repeatRecommended) 1 else 0)
                        out.text(result.feedback.transcript, MAX_TEXT)
                    }
                    PatternPracticeFeedbackResult.Inconclusive -> out.writeInt(2)
                    PatternPracticeFeedbackResult.Failed -> error("Failed is not a delivered result")
                }
            }
            require(bytes.size() <= MAX_PAYLOAD)
            bytes.toByteArray()
        } finally { bytes.wipe() }
    }

    fun decode(bytes: ByteArray): PatternSpeakingRecoverySnapshot {
        require(bytes.size in 1..MAX_PAYLOAD)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == 1)
            val phase = PatternRecoveryPhase.entries.getOrNull(input.readInt() - 1) ?: error("Unknown phase")
            val mode = PatternFeedbackMode.entries.getOrNull(input.readInt() - 1) ?: error("Unknown mode")
            val attempt = input.text(1024); val example = input.text(1024)
            val length = input.readInt()
            val body = if (length == -1) null else input.text(MAX_BODY, length)
            val result = when (input.readInt()) {
                0 -> null
                1 -> PatternPracticeFeedbackResult.Ready(PatternPracticeFeedback(input.text(MAX_TEXT),
                    when (input.readUnsignedByte()) { 0 -> false; 1 -> true; else -> error("Invalid boolean") }, input.text(MAX_TEXT)))
                2 -> PatternPracticeFeedbackResult.Inconclusive
                else -> error("Unknown result")
            }
            require(input.available() == 0)
            PatternSpeakingRecoverySnapshot(attempt, example, mode, phase, body, result).also(::validate)
        }
    }

    private fun validate(snapshot: PatternSpeakingRecoverySnapshot) {
        require(snapshot.attemptId.isNotBlank() && snapshot.exampleId.isNotBlank())
        val validResult = when (val result = snapshot.result) {
            is PatternPracticeFeedbackResult.Ready -> result.feedback.feedbackZh.isNotBlank()
            PatternPracticeFeedbackResult.Inconclusive -> true
            PatternPracticeFeedbackResult.Failed, null -> false
        }
        require(when (snapshot.phase) {
            PatternRecoveryPhase.Pending -> snapshot.requestBody?.isNotBlank() == true && snapshot.result == null
            PatternRecoveryPhase.Delivered -> snapshot.requestBody == null && validResult
            PatternRecoveryPhase.Blocked, PatternRecoveryPhase.Cleared -> snapshot.requestBody == null && snapshot.result == null
        })
    }

    private fun DataOutputStream.text(value: String, limit: Int) {
        var size = 0
        var index = 0
        while (index < value.length) {
            val c = value[index++]
            size += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() -> { require(index < value.length && value[index++].isLowSurrogate()); 4 }
                c.isLowSurrogate() -> error("Unpaired surrogate")
                else -> 3
            }
            require(size <= limit)
        }
        val bytes = value.toByteArray(Charsets.UTF_8)
        try { require(bytes.size == size); writeInt(size); write(bytes) } finally { bytes.fill(0) }
    }

    private fun DataInputStream.text(limit: Int, length: Int = readInt()): String {
        require(length in 0..limit && length <= available())
        val bytes = ByteArray(length)
        return try {
            readFully(bytes)
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } finally { bytes.fill(0) }
    }

    private class WipingOutput : ByteArrayOutputStream() {
        // DataOutputStream.close happens before the final copy; wipe only after that copy is taken.
        override fun close() = Unit
        fun wipe() { buf.fill(0) }
    }
}
