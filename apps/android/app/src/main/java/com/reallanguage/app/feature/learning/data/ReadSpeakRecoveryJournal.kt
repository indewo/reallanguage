package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.feature.formation.data.FormationPayloadOwner
import com.reallanguage.app.feature.formation.data.FormationPayloadLoad
import com.reallanguage.app.feature.formation.data.FormationPayloadSave
import com.reallanguage.app.feature.formation.data.FormationRecoveryFileStore
import com.reallanguage.app.feature.learning.domain.ReadSpeakCompletionSignal
import com.reallanguage.app.feature.learning.domain.ReadSpeakFeedbackResult
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

data class ReadSpeakRecoverySnapshot(
    val signal: ReadSpeakCompletionSignal,
    val commandId: String,
    val requestBody: String?,
    val delivered: ReadSpeakFeedbackResult? = null,
    val phase: ReadSpeakRecoveryPhase = ReadSpeakRecoveryPhase.Pending,
)

enum class ReadSpeakRecoveryPhase { Pending, Delivered, Completed, Blocked, Cleared }

sealed interface ReadSpeakRecoveryLoad {
    data object Missing : ReadSpeakRecoveryLoad
    data object Unavailable : ReadSpeakRecoveryLoad
    data class Loaded(val revision: Long, val snapshot: ReadSpeakRecoverySnapshot) : ReadSpeakRecoveryLoad
}

interface ReadSpeakRecoveryJournal {
    suspend fun load(): ReadSpeakRecoveryLoad
    suspend fun save(expectedRevision: Long?, snapshot: ReadSpeakRecoverySnapshot): Long?
}

/** Ordinary LR-03 only. The owner/access capability is supplied by the authenticated composition. */
class EncryptedReadSpeakRecoveryJournal(
    private val store: FormationRecoveryFileStore,
    private val owner: FormationPayloadOwner,
) : ReadSpeakRecoveryJournal {
    override suspend fun load(): ReadSpeakRecoveryLoad = withContext(Dispatchers.IO) {
        try {
            currentCoroutineContext().ensureActive()
            when (val stored = store.load(owner)) {
                FormationPayloadLoad.Missing -> if (store.hasCurrentAccess(owner)) ReadSpeakRecoveryLoad.Missing
                    else ReadSpeakRecoveryLoad.Unavailable
                FormationPayloadLoad.Unavailable -> ReadSpeakRecoveryLoad.Unavailable
                is FormationPayloadLoad.Loaded -> try {
                    require(stored.identity.owner == owner && stored.identity.payloadVersion == 1)
                    val snapshot = ReadSpeakRecoveryCodec.decode(stored.plaintext)
                    currentCoroutineContext().ensureActive()
                    if (store.hasCurrentAccess(owner)) ReadSpeakRecoveryLoad.Loaded(stored.identity.revision, snapshot)
                    else ReadSpeakRecoveryLoad.Unavailable
                } finally { stored.plaintext.fill(0) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ReadSpeakRecoveryLoad.Unavailable }
    }

    override suspend fun save(expectedRevision: Long?, snapshot: ReadSpeakRecoverySnapshot): Long? = withContext(Dispatchers.IO) {
        try {
            // Authentication alone cannot make an old malformed DTO safe to overwrite.
            if (load() == ReadSpeakRecoveryLoad.Unavailable) return@withContext null
            val encoded = ReadSpeakRecoveryCodec.encode(snapshot)
            try {
                currentCoroutineContext().ensureActive()
                when (val saved = store.compareAndSet(owner, expectedRevision, 1, encoded)) {
                    FormationPayloadSave.Conflict, FormationPayloadSave.Failed -> null
                    is FormationPayloadSave.Saved -> {
                        val readBack = load()
                        currentCoroutineContext().ensureActive()
                        if (readBack == ReadSpeakRecoveryLoad.Loaded(saved.revision, snapshot) && store.hasCurrentAccess(owner))
                            saved.revision else null
                    }
                }
            } finally { encoded.fill(0) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
}

/** Versioned length-prefixed UTF-8, not Java modified UTF-8 and not Android's JSON implementation. */
private object ReadSpeakRecoveryCodec {
    private const val MAGIC = 0x524c5233 // RLR3: this payload cannot be decoded as a formation snapshot.
    private const val VERSION = 1
    private const val MAX_BODY_BYTES = 12_000_000
    private const val MAX_COMMAND_BYTES = 1024
    private const val MAX_FEEDBACK_BYTES = 1_000_000
    private const val MAX_PAYLOAD_BYTES = MAX_BODY_BYTES + MAX_COMMAND_BYTES + MAX_FEEDBACK_BYTES + 128

    fun encode(snapshot: ReadSpeakRecoverySnapshot): ByteArray {
        validate(snapshot)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(when (snapshot.signal) {
                    ReadSpeakCompletionSignal.TOUCH_REACHED_END -> 1
                    ReadSpeakCompletionSignal.RECOGNITION_COMPLETE -> 2
                    ReadSpeakCompletionSignal.MANUAL_CONFIRMATION -> 3
                })
                output.writeInt(when (snapshot.phase) {
                    ReadSpeakRecoveryPhase.Pending -> 1
                    ReadSpeakRecoveryPhase.Delivered -> 2
                    ReadSpeakRecoveryPhase.Completed -> 3
                    ReadSpeakRecoveryPhase.Blocked -> 4
                    ReadSpeakRecoveryPhase.Cleared -> 5
                })
                output.writeText(snapshot.commandId, MAX_COMMAND_BYTES)
                if (snapshot.requestBody == null) output.writeInt(-1)
                else output.writeText(snapshot.requestBody, MAX_BODY_BYTES)
                when (val result = snapshot.delivered) {
                    null -> output.writeInt(0)
                    is ReadSpeakFeedbackResult.Ready -> {
                        output.writeInt(1)
                        output.writeText(result.feedbackZh, MAX_FEEDBACK_BYTES)
                        output.writeByte(if (result.contentComplete) 1 else 0)
                    }
                    ReadSpeakFeedbackResult.CompletedWithoutAudio -> output.writeInt(2)
                    ReadSpeakFeedbackResult.Failed -> error("A failed request is not a delivered candidate")
                }
            }
            require(bytes.size() <= MAX_PAYLOAD_BYTES)
            bytes.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): ReadSpeakRecoverySnapshot {
        require(bytes.size in 1..MAX_PAYLOAD_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == VERSION)
            val signal = when (input.readInt()) {
                1 -> ReadSpeakCompletionSignal.TOUCH_REACHED_END
                2 -> ReadSpeakCompletionSignal.RECOGNITION_COMPLETE
                3 -> ReadSpeakCompletionSignal.MANUAL_CONFIRMATION
                else -> error("Unknown completion signal")
            }
            val phase = when (input.readInt()) {
                1 -> ReadSpeakRecoveryPhase.Pending
                2 -> ReadSpeakRecoveryPhase.Delivered
                3 -> ReadSpeakRecoveryPhase.Completed
                4 -> ReadSpeakRecoveryPhase.Blocked
                5 -> ReadSpeakRecoveryPhase.Cleared
                else -> error("Unknown recovery phase")
            }
            val command = input.readText(MAX_COMMAND_BYTES)
            val bodyLength = input.readInt()
            val body = if (bodyLength == -1) null else input.readText(MAX_BODY_BYTES, bodyLength)
            val delivered = when (input.readInt()) {
                0 -> null
                1 -> ReadSpeakFeedbackResult.Ready(input.readText(MAX_FEEDBACK_BYTES), when (input.readUnsignedByte()) {
                    0 -> false
                    1 -> true
                    else -> error("Invalid completion boolean")
                })
                2 -> ReadSpeakFeedbackResult.CompletedWithoutAudio
                else -> error("Unknown delivered candidate")
            }
            require(input.available() == 0)
            ReadSpeakRecoverySnapshot(signal, command, body, delivered, phase).also(::validate)
        }
    }

    private fun validate(snapshot: ReadSpeakRecoverySnapshot) {
        require(snapshot.commandId.isNotBlank())
        require(snapshot.requestBody == null || snapshot.requestBody.isNotBlank())
        val candidate = when (val result = snapshot.delivered) {
            is ReadSpeakFeedbackResult.Ready -> result.feedbackZh.isNotBlank()
            ReadSpeakFeedbackResult.CompletedWithoutAudio -> snapshot.signal == ReadSpeakCompletionSignal.MANUAL_CONFIRMATION
            ReadSpeakFeedbackResult.Failed, null -> false
        }
        require(when (snapshot.phase) {
            ReadSpeakRecoveryPhase.Pending -> snapshot.requestBody != null && snapshot.delivered == null
            ReadSpeakRecoveryPhase.Delivered -> candidate
            // Completed means this attempt was consumed; Ready(false) is not a mastery result.
            ReadSpeakRecoveryPhase.Completed -> candidate && snapshot.requestBody == null
            ReadSpeakRecoveryPhase.Blocked, ReadSpeakRecoveryPhase.Cleared -> snapshot.requestBody == null && snapshot.delivered == null
        })
    }

    private fun DataOutputStream.writeText(value: String, limit: Int) {
        // Count first: reject oversized input and unpaired UTF-16 surrogates before allocating UTF-8.
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
        val encoded = value.toByteArray(Charsets.UTF_8)
        try {
            require(encoded.size == count)
            writeInt(encoded.size)
            write(encoded)
        } finally { encoded.fill(0) }
    }

    private fun DataInputStream.readText(limit: Int, length: Int = readInt()): String {
        require(length in 0..limit && length <= available())
        val bytes = ByteArray(length)
        return try {
            readFully(bytes)
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } finally { bytes.fill(0) }
    }
}
