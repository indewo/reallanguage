package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.feature.formation.data.FormationPayloadOwner
import com.reallanguage.app.feature.formation.data.FormationPayloadLoad
import com.reallanguage.app.feature.formation.data.FormationPayloadSave
import com.reallanguage.app.feature.formation.data.FormationRecoveryFileStore
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

enum class SceneResponseRecoveryPhase {
    TranscriptionPending, TranscriptionDelivered, FeedbackPending, FeedbackDelivered, Blocked, Cleared,
}

/** recordingId identifies this local audio attempt; it is not required to be a field in the wire body. */
data class SceneResponseRecoverySnapshot(
    val recordingId: String,
    val taskId: String,
    val roundIndex: Int,
    val phase: SceneResponseRecoveryPhase,
    val transcriptionBody: String? = null,
    val transcriptionResultBody: String? = null,
    val feedbackBody: String? = null,
    val feedbackResultBody: String? = null,
    val confirmedTranscript: String? = null,
)

sealed interface SceneResponseRecoveryLoad {
    data object Missing : SceneResponseRecoveryLoad
    data object Unavailable : SceneResponseRecoveryLoad
    data class Loaded(val revision: Long, val snapshot: SceneResponseRecoverySnapshot) : SceneResponseRecoveryLoad
}

interface SceneResponseRecoveryJournal {
    suspend fun load(): SceneResponseRecoveryLoad
    suspend fun save(expectedRevision: Long?, snapshot: SceneResponseRecoverySnapshot): Long?
}

/** Ordinary LR09 only. The factory supplies the exact account/chapter/task/round access capability. */
class EncryptedSceneResponseRecoveryJournal(
    private val store: FormationRecoveryFileStore,
    private val owner: FormationPayloadOwner,
) : SceneResponseRecoveryJournal {
    override suspend fun load(): SceneResponseRecoveryLoad = withContext(Dispatchers.IO) {
        try {
            currentCoroutineContext().ensureActive()
            when (val stored = store.load(owner)) {
                FormationPayloadLoad.Missing -> if (store.hasCurrentAccess(owner)) SceneResponseRecoveryLoad.Missing
                    else SceneResponseRecoveryLoad.Unavailable
                FormationPayloadLoad.Unavailable -> SceneResponseRecoveryLoad.Unavailable
                is FormationPayloadLoad.Loaded -> try {
                    require(stored.identity.owner == owner && stored.identity.payloadVersion == 1)
                    val snapshot = SceneResponseRecoveryCodec.decode(stored.plaintext)
                    currentCoroutineContext().ensureActive()
                    if (store.hasCurrentAccess(owner)) SceneResponseRecoveryLoad.Loaded(stored.identity.revision, snapshot)
                    else SceneResponseRecoveryLoad.Unavailable
                } finally { stored.plaintext.fill(0) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { SceneResponseRecoveryLoad.Unavailable }
    }

    override suspend fun save(expectedRevision: Long?, snapshot: SceneResponseRecoverySnapshot): Long? = withContext(Dispatchers.IO) {
        try {
            require(expectedRevision == null || expectedRevision in 0 until Long.MAX_VALUE)
            val nextRevision = expectedRevision?.plus(1) ?: 0L
            // Authentication alone is insufficient: a malformed old record must not be overwritten.
            if (load() == SceneResponseRecoveryLoad.Unavailable) return@withContext null
            val encoded = SceneResponseRecoveryCodec.encode(snapshot)
            try {
                currentCoroutineContext().ensureActive()
                when (val saved = store.compareAndSet(owner, expectedRevision, 1, encoded)) {
                    is FormationPayloadSave.Saved -> require(saved.revision == nextRevision)
                    FormationPayloadSave.Conflict, FormationPayloadSave.Failed -> Unit
                }
                // A failed response may follow a durable write. Acknowledge only this exact next
                // revision and snapshot, never a different body or an arbitrary newer revision.
                val readBack = load()
                currentCoroutineContext().ensureActive()
                if (readBack == SceneResponseRecoveryLoad.Loaded(nextRevision, snapshot) && store.hasCurrentAccess(owner))
                    nextRevision else null
            } finally { encoded.fill(0) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
}

/** RLR9/version 1 stores exact wire strings; their endpoint-specific JSON validation belongs to the repository. */
private object SceneResponseRecoveryCodec {
    private const val MAGIC = 0x524c5239
    private const val VERSION = 1
    private const val MAX_ID_BYTES = 1024
    private const val MAX_BODY_BYTES = 12_000_000
    private const val MAX_PAYLOAD_BYTES = 15_000_000

    fun encode(snapshot: SceneResponseRecoverySnapshot): ByteArray {
        validate(snapshot)
        // Check UTF-8 and the complete byte budget before allocating buffers or creating a key.
        val size = encodedSize(snapshot)
        val bytes = WipingOutput(size)
        return try {
            val output = DataOutputStream(bytes)
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeInt(snapshot.phase.code())
            output.writeInt(snapshot.roundIndex)
            output.writeText(snapshot.recordingId)
            output.writeText(snapshot.taskId)
            output.writeOptionalText(snapshot.transcriptionBody)
            output.writeOptionalText(snapshot.transcriptionResultBody)
            output.writeOptionalText(snapshot.feedbackBody)
            output.writeOptionalText(snapshot.feedbackResultBody)
            output.writeOptionalText(snapshot.confirmedTranscript)
            output.flush()
            require(bytes.size() == size)
            bytes.toByteArray()
        } finally { bytes.erase() }
    }

    fun decode(bytes: ByteArray): SceneResponseRecoverySnapshot {
        require(bytes.size in 1..MAX_PAYLOAD_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == VERSION)
            val phase = when (input.readInt()) {
                1 -> SceneResponseRecoveryPhase.TranscriptionPending
                2 -> SceneResponseRecoveryPhase.TranscriptionDelivered
                3 -> SceneResponseRecoveryPhase.FeedbackPending
                4 -> SceneResponseRecoveryPhase.FeedbackDelivered
                5 -> SceneResponseRecoveryPhase.Blocked
                6 -> SceneResponseRecoveryPhase.Cleared
                else -> error("Unknown LR09 recovery phase")
            }
            val round = input.readInt()
            val snapshot = SceneResponseRecoverySnapshot(
                recordingId = input.readText(MAX_ID_BYTES),
                taskId = input.readText(MAX_ID_BYTES),
                roundIndex = round,
                phase = phase,
                transcriptionBody = input.readOptionalText(MAX_BODY_BYTES),
                transcriptionResultBody = input.readOptionalText(MAX_BODY_BYTES),
                feedbackBody = input.readOptionalText(MAX_BODY_BYTES),
                feedbackResultBody = input.readOptionalText(MAX_BODY_BYTES),
                confirmedTranscript = input.readOptionalText(MAX_PAYLOAD_BYTES),
            )
            require(input.available() == 0)
            snapshot.also(::validate)
        }
    }

    private fun validate(snapshot: SceneResponseRecoverySnapshot) {
        require(snapshot.recordingId.isNotBlank() && snapshot.taskId.isNotBlank())
        require(snapshot.roundIndex in 0..2)
        require(snapshot.transcriptionBody == null || snapshot.transcriptionBody.isNotBlank())
        require(snapshot.transcriptionResultBody == null || snapshot.transcriptionResultBody.isNotBlank())
        require(snapshot.feedbackBody == null || snapshot.feedbackBody.isNotBlank())
        require(snapshot.feedbackResultBody == null || snapshot.feedbackResultBody.isNotBlank())
        // Presence is significant. Even an empty/whitespace confirmation is kept exactly; the
        // repository, not this custody codec, decides whether it may be sent to an endpoint.
        val fields = listOf(snapshot.transcriptionBody, snapshot.transcriptionResultBody,
            snapshot.feedbackBody, snapshot.feedbackResultBody, snapshot.confirmedTranscript)
        val mask = fields.foldIndexed(0) { index, value, field -> value or (if (field != null) 1 shl index else 0) }
        require(mask == when (snapshot.phase) {
            SceneResponseRecoveryPhase.TranscriptionPending -> 1
            SceneResponseRecoveryPhase.TranscriptionDelivered -> 3
            SceneResponseRecoveryPhase.FeedbackPending -> 22
            SceneResponseRecoveryPhase.FeedbackDelivered -> 24
            SceneResponseRecoveryPhase.Blocked, SceneResponseRecoveryPhase.Cleared -> 0
        })
    }

    private fun encodedSize(snapshot: SceneResponseRecoverySnapshot): Int {
        // Magic, codec version, phase, and round; each following field has a signed length.
        var size = 16L
        fun addText(value: String?, limit: Int) {
            size += 4L + (value?.let { utf8Length(it, limit) } ?: 0)
            require(size <= MAX_PAYLOAD_BYTES)
        }
        addText(snapshot.recordingId, MAX_ID_BYTES)
        addText(snapshot.taskId, MAX_ID_BYTES)
        addText(snapshot.transcriptionBody, MAX_BODY_BYTES)
        addText(snapshot.transcriptionResultBody, MAX_BODY_BYTES)
        addText(snapshot.feedbackBody, MAX_BODY_BYTES)
        addText(snapshot.feedbackResultBody, MAX_BODY_BYTES)
        addText(snapshot.confirmedTranscript, MAX_PAYLOAD_BYTES)
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

    private fun SceneResponseRecoveryPhase.code() = when (this) {
        SceneResponseRecoveryPhase.TranscriptionPending -> 1
        SceneResponseRecoveryPhase.TranscriptionDelivered -> 2
        SceneResponseRecoveryPhase.FeedbackPending -> 3
        SceneResponseRecoveryPhase.FeedbackDelivered -> 4
        SceneResponseRecoveryPhase.Blocked -> 5
        SceneResponseRecoveryPhase.Cleared -> 6
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

    private fun DataInputStream.readText(limit: Int, length: Int = readInt()): String {
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
