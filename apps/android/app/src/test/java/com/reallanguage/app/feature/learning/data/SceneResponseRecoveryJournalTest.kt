package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.feature.formation.data.*
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual AES-GCM/CAS/files, isolated memory keys. Raw JSON is custody data, not AI/audio evidence. */
class SceneResponseRecoveryJournalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun absentJournalIsMissingWithoutCreatingAKeyOrPayload() = runBlocking {
        val f = fixture()
        assertEquals(SceneResponseRecoveryLoad.Missing, f.journal().load())
        assertEquals(0, f.keys.creations)
        assertFalse(f.payload().exists())
    }

    @Test fun allSixPhasesAndThreeRoundsRoundTripExactlyThroughNewInstances() = runBlocking {
        for (round in 0..2) for (phase in SceneResponseRecoveryPhase.entries) {
            val f = fixture()
            val value = f.snapshot(phase, round)
            assertEquals(0L, f.journal().save(null, value))
            assertEquals(SceneResponseRecoveryLoad.Loaded(0, value), f.journal().load())
            val cipher = f.payload().readBytes().toString(Charsets.UTF_8)
            listOf(f.recordingId, f.taskId, "NOT_AUDIO", "确认 café 😀", "scene-command-").forEach {
                assertFalse("Private identity/body must not appear in the envelope", cipher.contains(it))
            }
            assertEquals(1, f.keys.creations)
        }
    }

    @Test fun everyIllegalPhaseFieldCombinationPreservesTheLastConfirmedRecord() = runBlocking {
        val f = fixture()
        val invalid = buildList {
            for (phase in SceneResponseRecoveryPhase.entries) for (mask in 0..31) {
                val expected = when (phase) {
                    SceneResponseRecoveryPhase.TranscriptionPending -> 1
                    SceneResponseRecoveryPhase.TranscriptionDelivered -> 3
                    SceneResponseRecoveryPhase.FeedbackPending -> 22
                    SceneResponseRecoveryPhase.FeedbackDelivered -> 24
                    SceneResponseRecoveryPhase.Blocked, SceneResponseRecoveryPhase.Cleared -> 0
                }
                if (mask != expected) add(SceneResponseRecoverySnapshot(f.recordingId, f.taskId, 2, phase,
                    transcriptionBody = f.request.takeIf { mask and 1 != 0 },
                    transcriptionResultBody = f.transcription.takeIf { mask and 2 != 0 },
                    feedbackBody = f.feedbackRequest.takeIf { mask and 4 != 0 },
                    feedbackResultBody = f.feedback.takeIf { mask and 8 != 0 },
                    confirmedTranscript = f.confirmed.takeIf { mask and 16 != 0 }))
            }
        }
        assertEquals(186, invalid.size)
        assertInvalidPreserves(f, invalid)
    }

    @Test fun identitiesRoundsBlankBodiesAndInvalidUtf16CannotReplaceTheRecord() = runBlocking {
        val f = fixture()
        val pending = f.snapshot()
        val delivered = f.snapshot(SceneResponseRecoveryPhase.TranscriptionDelivered)
        val feedback = f.snapshot(SceneResponseRecoveryPhase.FeedbackPending)
        assertInvalidPreserves(f, listOf(
            pending.copy(recordingId = " "), pending.copy(taskId = ""),
            pending.copy(roundIndex = -1), pending.copy(roundIndex = 3),
            pending.copy(recordingId = "bad:\uD800"), pending.copy(taskId = "bad:\uDC00"),
            pending.copy(transcriptionBody = "\t"), pending.copy(transcriptionBody = "bad:\uD800"),
            delivered.copy(transcriptionResultBody = ""), delivered.copy(transcriptionResultBody = "bad:\uDC00"),
            feedback.copy(feedbackBody = " "), feedback.copy(feedbackBody = "bad:\uD800"),
            feedback.copy(confirmedTranscript = "bad:\uDC00"),
            f.snapshot(SceneResponseRecoveryPhase.FeedbackDelivered).copy(feedbackResultBody = "bad:\uD800"),
        ))
    }

    @Test fun bodyUsesUtf8ByteLimitAndTotalSizeIsBoundedBeforeCreatingAKey() = runBlocking {
        val f = fixture()
        val boundary = f.snapshot().copy(recordingId = "r".repeat(1024), taskId = "t".repeat(1024),
            transcriptionBody = jsonPadding(12_000_000))
        assertEquals(0L, f.journal().save(null, boundary))
        assertEquals(SceneResponseRecoveryLoad.Loaded(0, boundary), f.journal().load())
        val before = f.payload().readBytes()
        for (bad in listOf(boundary.copy(transcriptionBody = boundary.transcriptionBody + " "),
            boundary.copy(recordingId = "中".repeat(342)), boundary.copy(taskId = boundary.taskId + "x"))) {
            assertNull(f.journal().save(0, bad)); assertArrayEquals(before, f.payload().readBytes())
        }
        val oversized = fixture()
        val combined = oversized.snapshot(SceneResponseRecoveryPhase.TranscriptionDelivered).copy(
            transcriptionBody = jsonPadding(12_000_000), transcriptionResultBody = jsonPadding(3_000_000))
        assertNull(oversized.journal().save(null, combined))
        assertEquals(SceneResponseRecoveryLoad.Missing, oversized.journal().load())
        assertEquals(0, oversized.keys.creations)
    }

    @Test fun lostCommitAcknowledgementReusesExactlyTheWrittenRevision() = runBlocking {
        val f = fixture()
        var commits = 0
        val lostAck = FormationAtomicFileCommitter { target, bytes, allowed ->
            check(allowed()); target.writeBytes(bytes); commits++
            throw IOException("isolated acknowledgement loss")
        }
        assertEquals(0L, f.journal(committer = lostAck).save(null, f.snapshot()))
        assertEquals(SceneResponseRecoveryLoad.Loaded(0, f.snapshot()), f.journal().load())
        val written = f.payload().readBytes()
        assertEquals(0L, f.journal(committer = lostAck).save(null, f.snapshot()))
        assertEquals(1, commits)
        assertArrayEquals(written, f.payload().readBytes())
    }

    @Test fun staleWritersCannotReplaceNewerPhaseAndClearedIsNotPermanentDiscard() = runBlocking {
        val f = fixture()
        val first = f.journal(); val second = f.journal()
        assertEquals(0L, first.save(null, f.snapshot()))
        assertEquals(0L, second.save(null, f.snapshot()))
        assertNull(second.save(null, f.snapshot().copy(recordingId = UUID.randomUUID().toString())))
        val blocked = f.snapshot(SceneResponseRecoveryPhase.Blocked)
        assertEquals(1L, first.save(0, blocked))
        assertNull(second.save(0, f.snapshot(SceneResponseRecoveryPhase.TranscriptionDelivered)))
        val cleared = f.snapshot(SceneResponseRecoveryPhase.Cleared)
        assertEquals(2L, second.save(1, cleared))
        assertEquals(SceneResponseRecoveryLoad.Loaded(2, cleared), first.load())
        val explicitNewRecording = f.snapshot().copy(recordingId = UUID.randomUUID().toString())
        assertEquals(3L, first.save(2, explicitNewRecording))
        assertEquals(SceneResponseRecoveryLoad.Loaded(3, explicitNewRecording), second.load())
        assertFalse(f.directory.walkTopDown().any { it.name.endsWith(".discarded") })
    }

    @Test fun corruptEnvelopeNeverBecomesMissingOrOverwritable() = runBlocking {
        for (damage in listOf<(ByteArray) -> ByteArray>(
            { it.copyOf().also { bytes -> bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte() } },
            { it.copyOf(it.size - 1) }, { it + byteArrayOf(0) },
        )) {
            val f = fixture()
            assertEquals(0L, f.journal().save(null, f.snapshot()))
            val corrupted = damage(f.payload().readBytes()); f.payload().writeBytes(corrupted)
            assertEquals(SceneResponseRecoveryLoad.Unavailable, f.journal().load())
            assertNull(f.journal().save(null, f.snapshot())); assertNull(f.journal().save(0, f.snapshot()))
            assertArrayEquals(corrupted, f.payload().readBytes())
        }
    }

    @Test fun missingKeyOrRevokedAccessDoesNotCreateAReplacementKeyOrPayload() = runBlocking {
        val f = fixture()
        assertEquals(0L, f.journal().save(null, f.snapshot()))
        val before = f.payload().readBytes()
        val absentKeys = MemoryKeys()
        for (journal in listOf(f.journal(keys = absentKeys), f.journal(allowed = { false }))) {
            assertEquals(SceneResponseRecoveryLoad.Unavailable, journal.load())
            assertNull(journal.save(null, f.snapshot())); assertNull(journal.save(0, f.snapshot()))
        }
        assertEquals(0, absentKeys.creations)
        assertArrayEquals(before, f.payload().readBytes())
    }

    @Test fun copiedEnvelopeCannotAuthenticateForAnotherAccountTaskOrRoundPurpose() = runBlocking {
        val f = fixture()
        assertEquals(0L, f.journal().save(null, f.snapshot()))
        val before = f.payload().readBytes()
        for (owner in listOf(f.owner.copy(accountSubjectHash = hash("other-account")),
            f.owner.copy(sourceEntryId = "LR09:${hash("other-task")}"),
            f.owner.copy(sourceEntryId = "LR09:${hash("other-round")}"))) {
            val target = f.payload(owner)
            check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
            target.writeBytes(before)
            val journal = EncryptedSceneResponseRecoveryJournal(f.store(), owner)
            assertEquals(SceneResponseRecoveryLoad.Unavailable, journal.load())
            assertNull(journal.save(null, f.snapshot())); assertArrayEquals(before, target.readBytes())
        }
        assertEquals(SceneResponseRecoveryLoad.Loaded(0, f.snapshot()), f.journal().load())
    }

    @Test fun authenticatedMalformedPayloadMustBeDecodedBeforeAnyCasOverwrite() = runBlocking {
        val source = fixture()
        assertEquals(0L, source.journal().save(null, source.snapshot()))
        val valid = (source.store().load(source.owner) as FormationPayloadLoad.Loaded).plaintext
        val marker = "NOT_AUDIO".toByteArray()
        val offset = (0..valid.size - marker.size).first { start -> marker.indices.all { valid[start + it] == marker[it] } }
        val malformed = listOf(byteArrayOf(0), valid.copyOf(valid.size - 1), valid + byteArrayOf(0),
            valid.copyOf().also { it[offset] = 0xc3.toByte(); it[offset + 1] = 0x28 },
            valid.copyOf().also { ByteBuffer.wrap(it).putInt(4, Int.MAX_VALUE) })
        for (bytes in malformed) {
            val f = fixture()
            assertEquals(FormationPayloadSave.Saved(0), f.store().compareAndSet(f.owner, null, 1, bytes))
            val before = f.payload().readBytes()
            assertEquals(SceneResponseRecoveryLoad.Unavailable, f.journal().load())
            assertNull(f.journal().save(null, f.snapshot())); assertNull(f.journal().save(0, f.snapshot()))
            assertArrayEquals(before, f.payload().readBytes())
        }
        valid.fill(0)
    }

    @Test fun saveNeedsAuthenticatedReadbackAndCancellationIsNotTranslatedToFailure() = runBlocking {
        val f = fixture()
        val corrupting = FormationAtomicFileCommitter { target, _, allowed ->
            check(allowed()); target.writeBytes(byteArrayOf(1, 2, 3))
        }
        assertNull(f.journal(committer = corrupting).save(null, f.snapshot()))
        assertTrue(f.payload().exists())
        assertEquals(SceneResponseRecoveryLoad.Unavailable, f.journal().load())
        val cancelled = fixture()
        val marker = OwnershipCancellation(Any())
        val journal = cancelled.journal(allowed = { throw marker })
        for (operation in listOf<suspend () -> Any?>({ journal.load() }, { journal.save(null, cancelled.snapshot()) })) {
            var caught: CancellationException? = null
            try { operation() } catch (failure: CancellationException) { caught = failure }
            assertSame(marker, caught)
        }
        assertEquals(0, cancelled.keys.creations)
    }

    private suspend fun assertInvalidPreserves(f: Fixture, values: List<SceneResponseRecoverySnapshot>) {
        assertEquals(0L, f.journal().save(null, f.snapshot()))
        val before = f.payload().readBytes()
        values.forEachIndexed { index, value ->
            assertNull("Invalid snapshot $index cannot replace revision 0", f.journal().save(0, value))
            assertArrayEquals(before, f.payload().readBytes())
        }
        assertEquals(SceneResponseRecoveryLoad.Loaded(0, f.snapshot()), f.journal().load())
    }
    private fun fixture() = Fixture(temporary.newFolder(), UUID.randomUUID().toString())
    private class OwnershipCancellation(val marker: Any) : CancellationException("isolated cancellation")
    private class Fixture(val directory: File, id: String) {
        val keys = MemoryKeys()
        val recordingId = "recording-$id"
        val taskId = "task-$id"
        val owner = FormationPayloadOwner(hash("owner-$id"), "LR09:${hash("chapter-$id:task:round2")}")
        val request = """{ "commandId":"scene-command-$id", "audioBase64":"NOT_AUDIO", "text":"确认 café 😀" }"""
        val transcription = """{ "transcript":"I can do that. café 😀", "segments":[], "confidence":null }"""
        val feedbackRequest = request.replace("scene-command-", "scene-feedback-")
        val feedback = """{ "decision":"INCONCLUSIVE", "feedbackZh":"请再清楚说一次 café 😀" }"""
        val confirmed = "  I can do that. café 😀  "
        fun snapshot(phase: SceneResponseRecoveryPhase = SceneResponseRecoveryPhase.TranscriptionPending, round: Int = 2) =
            SceneResponseRecoverySnapshot(recordingId, taskId, round, phase,
                transcriptionBody = request.takeIf { phase in setOf(SceneResponseRecoveryPhase.TranscriptionPending, SceneResponseRecoveryPhase.TranscriptionDelivered) },
                transcriptionResultBody = transcription.takeIf { phase in setOf(SceneResponseRecoveryPhase.TranscriptionDelivered, SceneResponseRecoveryPhase.FeedbackPending) },
                feedbackBody = feedbackRequest.takeIf { phase == SceneResponseRecoveryPhase.FeedbackPending },
                feedbackResultBody = feedback.takeIf { phase == SceneResponseRecoveryPhase.FeedbackDelivered },
                confirmedTranscript = confirmed.takeIf { phase in setOf(SceneResponseRecoveryPhase.FeedbackPending, SceneResponseRecoveryPhase.FeedbackDelivered) })
        fun store(keys: MemoryKeys = this.keys, allowed: (String) -> Boolean = { true }, committer: FormationAtomicFileCommitter? = null) =
            FormationRecoveryFileStore(directory, FormationPayloadCipher(keys, 16 * 1024 * 1024), allowed, committer)
        fun journal(keys: MemoryKeys = this.keys, allowed: (String) -> Boolean = { true }, committer: FormationAtomicFileCommitter? = null) =
            EncryptedSceneResponseRecoveryJournal(store(keys, allowed, committer), owner)
        fun payload(owner: FormationPayloadOwner = this.owner) = File(File(directory, owner.accountSubjectHash), "${hash(owner.sourceEntryId)}.payload")
    }
    private class MemoryKeys : FormationPayloadKeys {
        private val values = mutableMapOf<Pair<String, Int>, SecretKey>()
        var creations = 0
            private set
        @Synchronized override fun existing(accountSubjectHash: String, keyVersion: Int) = values[accountSubjectHash to keyVersion]
        @Synchronized override fun createIfAbsent(accountSubjectHash: String, keyVersion: Int): SecretKey = values.getOrPut(accountSubjectHash to keyVersion) {
            creations++; KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
        @Synchronized override fun deleteAccount(accountSubjectHash: String) { values.keys.removeAll { it.first == accountSubjectHash } }
    }
    private companion object {
        fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        fun jsonPadding(bytes: Int): String = "{\"padding\":\"" + "x".repeat(bytes - 14) + "\"}"
    }
}
