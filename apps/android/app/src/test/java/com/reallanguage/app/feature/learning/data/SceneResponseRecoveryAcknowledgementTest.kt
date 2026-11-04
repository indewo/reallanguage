package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.feature.formation.data.*
import java.io.File
import java.io.IOException
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

/** Real isolated AES/CAS files: acknowledging an exact write never permits following a different attempt. */
class SceneResponseRecoveryAcknowledgementTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun lostAcknowledgementAtEachPhaseReturnsExactRevisionAndSameObjectCanAdvance() = runBlocking {
        val f = fixture()
        var commits = 0
        val journal = f.journal(committer = FormationAtomicFileCommitter { target, bytes, allowed ->
            check(allowed()); target.writeBytes(bytes); commits++
            throw IOException("isolated post-write acknowledgement loss")
        })
        var revision: Long? = null
        f.phases.forEachIndexed { index, snapshot ->
            revision = journal.save(revision, snapshot)
            assertEquals("The exact authenticated write must be acknowledged immediately", index.toLong(), revision)
            assertEquals(SceneResponseRecoveryLoad.Loaded(index.toLong(), snapshot), journal.load())
        }
        assertEquals(4, commits)
        val delivered = f.payload().readBytes()
        assertEquals(3L, journal.save(2, f.phases.last()))
        assertEquals(4, commits)
        assertArrayEquals(delivered, f.payload().readBytes())
        assertNull((journal.load() as SceneResponseRecoveryLoad.Loaded).snapshot.feedbackBody)
    }

    @Test fun conflictNeverFollowsDifferentContentOrAnArbitraryHigherRevision() = runBlocking {
        for (higherRevision in listOf(false, true)) {
            val f = fixture(); val journal = f.journal()
            val value = if (higherRevision) f.pending else f.pending.copy(transcriptionBody = "{\"commandId\":\"different\"}")
            assertEquals(0L, journal.save(null, value))
            if (higherRevision) {
                assertEquals(1L, journal.save(0, value)); assertEquals(2L, journal.save(1, value))
            }
            val before = f.payload().readBytes()
            assertNull(journal.save(null, f.pending))
            if (higherRevision) assertNull(journal.save(0, f.pending))
            assertArrayEquals(before, f.payload().readBytes())
            assertEquals(SceneResponseRecoveryLoad.Loaded(if (higherRevision) 2 else 0, value), journal.load())
        }
    }

    @Test fun failedSaveCannotAcknowledgeDifferentNextBodyMalformedPayloadOrAnotherOwner() = runBlocking {
        for (kind in listOf("different-body", "higher-revision", "malformed", "other-owner")) {
            val f = fixture()
            val replacement = when (kind) {
                "different-body" -> f.envelope(f.pending.copy(transcriptionBody = "{\"commandId\":\"other\"}"), 0)
                "higher-revision" -> f.envelope(f.pending, 1)
                else -> FormationPayloadCipher(f.keys).encrypt(FormationPayloadIdentity(
                    if (kind == "other-owner") f.owner.copy(accountSubjectHash = hash("other-${f.id}")) else f.owner,
                    0, 1), byteArrayOf(0))
            }
            val journal = f.journal(committer = FormationAtomicFileCommitter { target, _, allowed ->
                check(allowed()); target.writeBytes(replacement)
                throw IOException("isolated failed write with different stored evidence")
            })
            assertNull(kind, journal.save(null, f.pending))
            assertArrayEquals(replacement, f.payload().readBytes())
            if (kind in setOf("malformed", "other-owner")) {
                assertEquals(SceneResponseRecoveryLoad.Unavailable, journal.load())
                assertNull(journal.save(0, f.pending))
                assertArrayEquals(replacement, f.payload().readBytes())
            }
        }
    }

    @Test fun acknowledgementRecoveryStillHonoursRevokedAccessAndCancellation() = runBlocking {
        for (cancel in listOf(false, true)) {
            val f = fixture(); var allowed = true
            val journal = f.journal(allowed = { allowed }, committer = FormationAtomicFileCommitter { target, bytes, mayCommit ->
                check(mayCommit()); target.writeBytes(bytes)
                if (cancel) throw CancellationException("isolated cancellation after write")
                allowed = false
                throw IOException("isolated ownership revocation after write")
            })
            if (cancel) {
                var cancelled = false
                try { journal.save(null, f.pending) } catch (_: CancellationException) { cancelled = true }
                assertTrue("Cancellation cannot be converted into successful delivery", cancelled)
            } else assertNull(journal.save(null, f.pending))
            assertEquals(SceneResponseRecoveryLoad.Loaded(0, f.pending), f.journal().load())
        }
    }

    private fun fixture() = Fixture(temporary.newFolder(), UUID.randomUUID().toString())
    private class Fixture(val directory: File, val id: String) {
        val keys = MemoryKeys()
        val owner = FormationPayloadOwner(hash("account-$id"), "LR09:${hash("chapter/task/round-$id")}")
        val pending = SceneResponseRecoverySnapshot("recording-$id", "task-$id", 2,
            SceneResponseRecoveryPhase.TranscriptionPending, transcriptionBody = "{\"commandId\":\"asr-$id\",\"audioBase64\":\"NOT_AUDIO\"}")
        private val transcription = "{\"transcript\":\"I can join.\",\"segments\":[],\"confidence\":null}"
        val phases = listOf(pending,
            pending.copy(phase = SceneResponseRecoveryPhase.TranscriptionDelivered, transcriptionResultBody = transcription),
            pending.copy(phase = SceneResponseRecoveryPhase.FeedbackPending, transcriptionBody = null,
                transcriptionResultBody = transcription, feedbackBody = "{\"commandId\":\"feedback-$id\",\"audioBase64\":\"NOT_AUDIO\"}",
                confirmedTranscript = "I can join."),
            pending.copy(phase = SceneResponseRecoveryPhase.FeedbackDelivered, transcriptionBody = null,
                feedbackResultBody = "{\"decision\":\"INCONCLUSIVE\"}", confirmedTranscript = "I can join."))
        fun journal(allowed: (String) -> Boolean = { true }, committer: FormationAtomicFileCommitter? = null,
            root: File = directory) = EncryptedSceneResponseRecoveryJournal(
            FormationRecoveryFileStore(root, FormationPayloadCipher(keys), allowed, committer), owner)
        fun payload(root: File = directory) = File(File(root, owner.accountSubjectHash), "${hash(owner.sourceEntryId)}.payload")
        suspend fun envelope(snapshot: SceneResponseRecoverySnapshot, revision: Int): ByteArray {
            val shadow = File(directory, "shadow-${UUID.randomUUID()}").also { check(it.mkdir()) }
            val journal = journal(root = shadow)
            var previous: Long? = null
            repeat(revision + 1) { previous = journal.save(previous, snapshot); assertEquals(it.toLong(), previous) }
            return payload(shadow).readBytes()
        }
    }
    private class MemoryKeys : FormationPayloadKeys {
        private val keys = mutableMapOf<Pair<String, Int>, SecretKey>()
        @Synchronized override fun existing(accountSubjectHash: String, keyVersion: Int) = keys[accountSubjectHash to keyVersion]
        @Synchronized override fun createIfAbsent(accountSubjectHash: String, keyVersion: Int): SecretKey =
            keys.getOrPut(accountSubjectHash to keyVersion) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        @Synchronized override fun deleteAccount(accountSubjectHash: String) { keys.keys.removeAll { it.first == accountSubjectHash } }
    }
    private companion object {
        fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
