package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.feature.formation.data.FormationPayloadCipher
import com.reallanguage.app.feature.formation.data.FormationPayloadKeys
import com.reallanguage.app.feature.formation.data.FormationPayloadLoad
import com.reallanguage.app.feature.formation.data.FormationPayloadOwner
import com.reallanguage.app.feature.formation.data.FormationPayloadSave
import com.reallanguage.app.feature.formation.data.FormationRecoveryFileStore
import com.reallanguage.app.feature.learning.domain.ReadSpeakCompletionSignal
import com.reallanguage.app.feature.learning.domain.ReadSpeakFeedbackResult
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real AES-GCM and durable files; NOT_AUDIO is synthetic data, not microphone or AI evidence. */
class ReadSpeakRecoveryJournalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun genuinelyAbsentJournalIsMissingWithoutCreatingAKey() = runBlocking {
        val f = fixture()
        assertEquals(ReadSpeakRecoveryLoad.Missing, f.journal().load())
        assertEquals(0, f.keys.creations)
        assertFalse(f.directory.walkTopDown().any { it.isFile && it.name.endsWith(".payload") })
    }

    @Test fun pendingPreservesExactUtf8BodyCommandAndSignalAcrossNewStoreWithoutPlaintext() = runBlocking {
        val f = fixture()
        assertEquals(0L, f.journal().save(null, f.pending))
        assertEquals(ReadSpeakRecoveryLoad.Loaded(0, f.pending), f.journal().load())
        val bytes = f.payload().readBytes()
        val raw = bytes.toString(Charsets.UTF_8)
        assertFalse(raw.contains("NOT_AUDIO"))
        assertFalse(raw.contains("确认 café 😀"))
        assertFalse(raw.contains(f.pending.commandId))
        assertFalse(raw.contains(requireNotNull(f.pending.requestBody)))
        assertEquals(1, f.keys.creations)
        assertEquals(ReadSpeakRecoveryLoad.Loaded(0, f.pending),
            EncryptedReadSpeakRecoveryJournal(f.store(), f.owner).load())
    }

    @Test fun casRejectsChangedReplayAndClearedKeepsRevisionForTheNextExplicitAttempt() = runBlocking {
        val f = fixture()
        val journal = f.journal()
        assertEquals(0L, journal.save(null, f.pending))
        assertEquals("A lost save acknowledgement replays the same revision", 0L, f.journal().save(null, f.pending))
        assertNull(f.journal().save(null, f.pending.copy(commandId = UUID.randomUUID().toString())))
        val blocked = f.pending.copy(requestBody = null, phase = ReadSpeakRecoveryPhase.Blocked)
        assertEquals(1L, f.journal().save(0, blocked))
        assertEquals(ReadSpeakRecoveryLoad.Loaded(1, blocked), f.journal().load())
        val cleared = blocked.copy(phase = ReadSpeakRecoveryPhase.Cleared)
        assertEquals(2L, f.journal().save(1, cleared))
        assertEquals(2L, f.journal().save(1, cleared))
        assertEquals(ReadSpeakRecoveryLoad.Loaded(2, cleared), f.journal().load())
        assertNull(f.journal().save(0, f.pending))
        val next = f.pending.copy(commandId = UUID.randomUUID().toString())
        assertEquals("Cleared is not a permanent discard tombstone", 3L, f.journal().save(2, next))
        assertEquals(ReadSpeakRecoveryLoad.Loaded(3, next), f.journal().load())
        assertFalse(f.directory.walkTopDown().any { it.name.endsWith(".discarded") })
    }

    @Test fun deliveredAndCompletedCandidatesRestoreWithoutRequiringTheOriginalBody() = runBlocking {
        val candidates = listOf(
            ReadSpeakCompletionSignal.TOUCH_REACHED_END to ReadSpeakFeedbackResult.Ready("已交付反馈 café 😀", true),
            ReadSpeakCompletionSignal.RECOGNITION_COMPLETE to ReadSpeakFeedbackResult.Ready("尚未读完整。", false),
            ReadSpeakCompletionSignal.MANUAL_CONFIRMATION to ReadSpeakFeedbackResult.CompletedWithoutAudio,
        )
        for ((signal, candidate) in candidates) {
            val f = fixture()
            val delivered = f.pending.copy(signal = signal, requestBody = null, delivered = candidate,
                phase = ReadSpeakRecoveryPhase.Delivered)
            assertEquals(0L, f.journal().save(null, delivered))
            assertEquals(ReadSpeakRecoveryLoad.Loaded(0, delivered), f.journal().load())
            val completed = delivered.copy(phase = ReadSpeakRecoveryPhase.Completed)
            assertEquals(1L, f.journal().save(0, completed))
            assertEquals(ReadSpeakRecoveryLoad.Loaded(1, completed), f.journal().load())
        }
        val f = fixture()
        val withBody = f.pending.copy(delivered = ReadSpeakFeedbackResult.Ready("保留交付"),
            phase = ReadSpeakRecoveryPhase.Delivered)
        assertEquals(0L, f.journal().save(null, withBody))
        assertEquals(ReadSpeakRecoveryLoad.Loaded(0, withBody), f.journal().load())
    }

    @Test fun inconsistentPhasesAndOversizeOrInvalidUtf8NeverReplaceTheConfirmedRecord() = runBlocking {
        val f = fixture()
        assertEquals(0L, f.journal().save(null, f.pending))
        val before = f.payload().readBytes()
        val ready = ReadSpeakFeedbackResult.Ready("已收到")
        val invalid = listOf(
            f.pending.copy(requestBody = null), f.pending.copy(requestBody = ""),
            f.pending.copy(commandId = " "),
            f.pending.copy(requestBody = "x".repeat(1024 * 1024 + 1)),
            f.pending.copy(requestBody = "unpaired:\uD800"),
            f.pending.copy(delivered = ready),
            f.pending.copy(requestBody = null, phase = ReadSpeakRecoveryPhase.Delivered),
            f.pending.copy(requestBody = null, delivered = ReadSpeakFeedbackResult.Failed,
                phase = ReadSpeakRecoveryPhase.Delivered),
            f.pending.copy(requestBody = null, delivered = ReadSpeakFeedbackResult.CompletedWithoutAudio,
                phase = ReadSpeakRecoveryPhase.Delivered), // TOUCH completion cannot become a manual exit.
            f.pending.copy(requestBody = null, phase = ReadSpeakRecoveryPhase.Completed),
            f.pending.copy(delivered = ready, phase = ReadSpeakRecoveryPhase.Completed),
            f.pending.copy(phase = ReadSpeakRecoveryPhase.Blocked),
            f.pending.copy(requestBody = null, delivered = ready, phase = ReadSpeakRecoveryPhase.Blocked),
            f.pending.copy(phase = ReadSpeakRecoveryPhase.Cleared),
            f.pending.copy(requestBody = null, delivered = ready, phase = ReadSpeakRecoveryPhase.Cleared),
        )
        invalid.forEachIndexed { index, value ->
            assertNull("Invalid record $index must not replace revision 0", f.journal().save(0, value))
            assertArrayEquals(before, f.payload().readBytes())
            assertEquals(ReadSpeakRecoveryLoad.Loaded(0, f.pending), f.journal().load())
        }
    }

    @Test fun damagedEnvelopeIsUnavailableAndCannotBeOverwrittenAsMissing() = runBlocking {
        for (damage in listOf<(ByteArray) -> ByteArray>(
            { it.copyOf().also { bytes -> bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte() } },
            { it.copyOf(it.size - 1) }, { it + byteArrayOf(0) },
        )) {
            val f = fixture()
            assertEquals(0L, f.journal().save(null, f.pending))
            val damaged = damage(f.payload().readBytes())
            f.payload().writeBytes(damaged)
            assertEquals(ReadSpeakRecoveryLoad.Unavailable, f.journal().load())
            assertNull(f.journal().save(null, f.pending))
            assertNull(f.journal().save(0, f.pending))
            assertArrayEquals(damaged, f.payload().readBytes())
        }
    }

    @Test fun missingKeyOrRevokedAccessCannotRecreateKeysOrReadAndReplaceTheJournal() = runBlocking {
        val f = fixture()
        assertEquals(0L, f.journal().save(null, f.pending))
        val before = f.payload().readBytes()
        val denied = EncryptedReadSpeakRecoveryJournal(f.store(allowed = { false }), f.owner)
        assertEquals(ReadSpeakRecoveryLoad.Unavailable, denied.load())
        assertNull(denied.save(0, f.pending))
        val missingKeys = MemoryKeys()
        val noKey = EncryptedReadSpeakRecoveryJournal(f.store(keys = missingKeys), f.owner)
        assertEquals(ReadSpeakRecoveryLoad.Unavailable, noKey.load())
        assertNull(noKey.save(null, f.pending))
        assertNull(noKey.save(0, f.pending))
        assertEquals(0, missingKeys.creations)
        assertArrayEquals(before, f.payload().readBytes())
    }

    @Test fun copiedCiphertextCannotAuthenticateForAnotherSentenceOrAccountOwner() = runBlocking {
        val f = fixture()
        assertEquals(0L, f.journal().save(null, f.pending))
        val ciphertext = f.payload().readBytes()
        for (other in listOf(f.owner.copy(sourceEntryId = "LR03:${hash("other-sentence")}"),
            f.owner.copy(accountSubjectHash = hash("other-account")))) {
            val target = f.payload(other)
            check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
            target.writeBytes(ciphertext)
            val reader = EncryptedReadSpeakRecoveryJournal(f.store(), other)
            assertEquals(ReadSpeakRecoveryLoad.Unavailable, reader.load())
            assertNull(reader.save(null, f.pending))
            assertArrayEquals(ciphertext, target.readBytes())
        }
        assertEquals(ReadSpeakRecoveryLoad.Loaded(0, f.pending), f.journal().load())
    }

    @Test fun authenticatedInvalidBinaryOrMalformedUtf8IsNotAnEmptyRecoverableAttempt() = runBlocking {
        val source = fixture()
        assertEquals(0L, source.journal().save(null, source.pending))
        val original = (source.store().load(source.owner) as FormationPayloadLoad.Loaded).plaintext
        val marker = "NOT_AUDIO".toByteArray(Charsets.UTF_8)
        val offset = (0..original.size - marker.size).first { start ->
            marker.indices.all { original[start + it] == marker[it] }
        }
        val invalidUtf8 = original.copyOf().also { it[offset] = 0xc3.toByte(); it[offset + 1] = 0x28 }
        val invalid = listOf(byteArrayOf(0), original.copyOf(original.size - 1), original + byteArrayOf(0), invalidUtf8)
        for (payload in invalid) {
            val f = fixture()
            assertEquals(FormationPayloadSave.Saved(0), f.store().compareAndSet(f.owner, null, 1, payload))
            val before = f.payload().readBytes()
            assertEquals(ReadSpeakRecoveryLoad.Unavailable, f.journal().load())
            assertNull(f.journal().save(null, f.pending))
            assertNull(f.journal().save(0, f.pending))
            assertArrayEquals(before, f.payload().readBytes())
        }
    }

    private fun fixture() = Fixture(temporary.newFolder(), UUID.randomUUID().toString())

    private class Fixture(val directory: File, id: String) {
        val keys = MemoryKeys()
        val owner = FormationPayloadOwner(hash("account:$id"), "LR03:${hash("chapter:$id:sentence:fixed-English")}")
        val pending = ReadSpeakRecoverySnapshot(ReadSpeakCompletionSignal.TOUCH_REACHED_END,
            UUID.randomUUID().toString(), """{ "audio":"NOT_AUDIO", "text":"确认 café 😀", "spacing" : "kept" }""")
        fun store(keys: MemoryKeys = this.keys, allowed: (String) -> Boolean = { true }) =
            FormationRecoveryFileStore(directory, FormationPayloadCipher(keys), allowed)
        fun journal() = EncryptedReadSpeakRecoveryJournal(store(), owner)
        fun payload(identity: FormationPayloadOwner = owner) = File(File(directory, identity.accountSubjectHash),
            "${hash(identity.sourceEntryId)}.payload")
    }

    /** Only key custody is replaced; the cipher/file-store code is production code. */
    private class MemoryKeys : FormationPayloadKeys {
        private val values = mutableMapOf<Pair<String, Int>, SecretKey>()
        var creations = 0
            private set
        @Synchronized override fun existing(accountSubjectHash: String, keyVersion: Int) = values[accountSubjectHash to keyVersion]
        @Synchronized override fun createIfAbsent(accountSubjectHash: String, keyVersion: Int): SecretKey =
            values.getOrPut(accountSubjectHash to keyVersion) {
                creations++
                KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            }
        @Synchronized override fun deleteAccount(accountSubjectHash: String) {
            values.keys.removeAll { it.first == accountSubjectHash }
        }
    }

    private companion object {
        fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
