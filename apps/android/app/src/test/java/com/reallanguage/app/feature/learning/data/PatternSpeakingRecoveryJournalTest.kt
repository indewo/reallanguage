package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.feature.formation.data.*
import com.reallanguage.app.feature.learning.domain.PatternPracticeFeedback
import com.reallanguage.app.feature.learning.domain.PatternPracticeFeedbackResult
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

/** Real encrypted files; only key custody is replaced. No microphone, HTTP, SQL or AI. */
class PatternSpeakingRecoveryJournalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun missingDoesNotCreateKeysOrEvidence() = runBlocking {
        val f = fixture()
        assertEquals(PatternSpeakingRecoveryLoad.Missing, f.journal().load())
        assertEquals(0, f.keys.creations)
        assertFalse(f.directory.walkTopDown().any { it.name.endsWith(".payload") })
    }

    @Test fun exactRequestAndAllDeliveredResultsSurviveNewReaderWithoutPlaintext() = runBlocking {
        for (mode in PatternFeedbackMode.entries) for (result in listOf(
            PatternPracticeFeedbackResult.Ready(PatternPracticeFeedback("完整语意 café 😀", false, "  original transcript  ")),
            PatternPracticeFeedbackResult.Ready(PatternPracticeFeedback("请再练习。", true, "")),
            PatternPracticeFeedbackResult.Inconclusive,
        )) {
            val f = fixture()
            val pending = f.pending.copy(mode = mode)
            assertEquals(0L, f.journal().save(null, pending))
            assertEquals(PatternSpeakingRecoveryLoad.Loaded(0, pending), f.journal().load())
            val encrypted = f.payload().readBytes().toString(Charsets.UTF_8)
            listOf(f.pending.attemptId, f.pending.exampleId, "NOT_AUDIO", "确认 café 😀").forEach { assertFalse(encrypted.contains(it)) }
            val delivered = pending.copy(phase = PatternRecoveryPhase.Delivered, requestBody = null, result = result)
            assertEquals(1L, f.journal().save(0, delivered))
            assertEquals(PatternSpeakingRecoveryLoad.Loaded(1, delivered), f.journal().load())
            assertEquals(1, f.keys.creations)
        }
    }

    @Test fun lostAcknowledgementDoesNotCreateSecondRevisionOrOverwriteAnotherAttempt() = runBlocking {
        val f = fixture()
        var commits = 0
        val lost = FormationAtomicFileCommitter { target, envelope, allowed ->
            check(allowed()); target.writeBytes(envelope); commits++; throw IOException("isolated lost acknowledgement")
        }
        assertNull(f.journal(committer = lost).save(null, f.pending))
        assertEquals(PatternSpeakingRecoveryLoad.Loaded(0, f.pending), f.journal().load())
        val bytes = f.payload().readBytes()
        assertEquals(0L, f.journal(committer = lost).save(null, f.pending))
        assertEquals(1, commits)
        assertNull(f.journal().save(null, f.pending.copy(attemptId = "another-attempt")))
        assertArrayEquals(bytes, f.payload().readBytes())
    }

    @Test fun blockedAndExplicitClearedEraseRawRequestButKeepCasHistory() = runBlocking {
        val f = fixture()
        assertEquals(0L, f.journal().save(null, f.pending))
        val blocked = f.pending.copy(phase = PatternRecoveryPhase.Blocked, requestBody = null)
        assertEquals(1L, f.journal().save(0, blocked))
        assertEquals(PatternSpeakingRecoveryLoad.Loaded(1, blocked), f.journal().load())
        val cleared = blocked.copy(phase = PatternRecoveryPhase.Cleared, attemptId = "explicit-new-recording")
        assertEquals(2L, f.journal().save(1, cleared))
        assertEquals(PatternSpeakingRecoveryLoad.Loaded(2, cleared), f.journal().load())
        assertNull(f.journal().save(0, f.pending))
    }

    @Test fun invalidCrossPhaseOrFailedCandidateCannotReplaceValidEvidence() = runBlocking {
        val f = fixture()
        assertEquals(0L, f.journal().save(null, f.pending))
        val original = f.payload().readBytes()
        val ready = PatternPracticeFeedbackResult.Ready(PatternPracticeFeedback("反馈", false, ""))
        val invalid = listOf(f.pending.copy(attemptId = " "), f.pending.copy(exampleId = ""),
            f.pending.copy(requestBody = " "), f.pending.copy(requestBody = null), f.pending.copy(result = ready),
            f.pending.copy(phase = PatternRecoveryPhase.Delivered),
            f.pending.copy(phase = PatternRecoveryPhase.Delivered, requestBody = null, result = PatternPracticeFeedbackResult.Failed),
            f.pending.copy(phase = PatternRecoveryPhase.Delivered, requestBody = null,
                result = PatternPracticeFeedbackResult.Ready(PatternPracticeFeedback(" ", false, ""))),
            f.pending.copy(phase = PatternRecoveryPhase.Blocked), f.pending.copy(phase = PatternRecoveryPhase.Cleared))
        for (snapshot in invalid) { assertNull(f.journal().save(0, snapshot)); assertArrayEquals(original, f.payload().readBytes()) }
    }

    @Test fun utf8LimitsAndUnpairedSurrogatesFailBeforeCreatingKeys() = runBlocking {
        for (modify in listOf<(PatternSpeakingRecoverySnapshot) -> PatternSpeakingRecoverySnapshot>(
            { it.copy(attemptId = "a".repeat(1025)) }, { it.copy(exampleId = "中".repeat(342)) },
            { it.copy(requestBody = "a".repeat(12_000_001)) }, { it.copy(requestBody = "\uD800") },
        )) { val f = fixture(); assertNull(f.journal().save(null, modify(f.pending))); assertEquals(0, f.keys.creations) }
    }

    @Test fun authenticatedMalformedPayloadsAreUnavailableAndNeverOverwritten() = runBlocking {
        val f = fixture()
        assertEquals(0L, f.journal().save(null, f.pending))
        val raw = (f.store().load(f.owner) as FormationPayloadLoad.Loaded).plaintext
        val invalid = listOf(raw.copyOf(raw.size - 1), raw + byteArrayOf(0), raw.copyOf().also { ByteBuffer.wrap(it).putInt(0x524c5233) },
            raw.copyOf().also { ByteBuffer.wrap(it).putInt(4, 99) }, raw.copyOf().also { ByteBuffer.wrap(it).putInt(8, 99) })
        for (bytes in invalid) {
            val other = fixture()
            assertEquals(FormationPayloadSave.Saved(0), other.store().compareAndSet(other.owner, null, 1, bytes))
            val encrypted = other.payload().readBytes()
            assertEquals(PatternSpeakingRecoveryLoad.Unavailable, other.journal().load())
            assertNull(other.journal().save(0, other.pending)); assertArrayEquals(encrypted, other.payload().readBytes())
        }
        raw.fill(0)
    }

    @Test fun deniedOwnerMissingKeyAndCorruptCiphertextRemainUnavailable() = runBlocking {
        val f = fixture()
        assertEquals(0L, f.journal().save(null, f.pending))
        val bytes = f.payload().readBytes()
        assertEquals(PatternSpeakingRecoveryLoad.Unavailable, f.journal(allowed = { false }).load())
        assertNull(f.journal(allowed = { false }).save(0, f.pending))
        val missing = MemoryKeys()
        assertEquals(PatternSpeakingRecoveryLoad.Unavailable, f.journal(keys = missing).load())
        assertNull(f.journal(keys = missing).save(0, f.pending)); assertEquals(0, missing.creations)
        assertArrayEquals(bytes, f.payload().readBytes())
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); f.payload().writeBytes(bytes)
        assertEquals(PatternSpeakingRecoveryLoad.Unavailable, f.journal().load())
        assertNull(f.journal().save(0, f.pending)); assertArrayEquals(bytes, f.payload().readBytes())
    }

    @Test fun readbackAndCancellationNeverClaimSuccessfulSave() = runBlocking {
        val f = fixture()
        var allowed = true
        val revoke = FormationAtomicFileCommitter { target, bytes, canCommit -> check(canCommit()); target.writeBytes(bytes); allowed = false }
        assertNull(f.journal(allowed = { allowed }, committer = revoke).save(null, f.pending))
        assertEquals(PatternSpeakingRecoveryLoad.Loaded(0, f.pending), f.journal().load())
        val cancellation = OwnedCancellation(Any())
        try { f.journal(allowed = { throw cancellation }).load(); fail("Cancellation must propagate") }
        catch (actual: CancellationException) { assertSame(cancellation, actual) }
    }

    private fun fixture() = Fixture(temporary.newFolder(), UUID.randomUUID().toString())
    private class OwnedCancellation(val marker: Any) : CancellationException("isolated cancellation")
    private class Fixture(val directory: File, id: String) {
        val keys = MemoryKeys()
        val owner = FormationPayloadOwner(hash("account:$id"), "LR07:${hash("chapter:$id:fixed-pattern:practice")}")
        val pending = PatternSpeakingRecoverySnapshot("attempt-$id", "example-$id", PatternFeedbackMode.Reading,
            PatternRecoveryPhase.Pending, """{ "commandId":"isolated-$id", "audio":"NOT_AUDIO", "text":"确认 café 😀" }""")
        fun store(keys: MemoryKeys = this.keys, allowed: (String) -> Boolean = { true }, committer: FormationAtomicFileCommitter? = null) =
            FormationRecoveryFileStore(directory, FormationPayloadCipher(keys, maxPlaintextBytes = 16 * 1024 * 1024), allowed, committer)
        fun journal(keys: MemoryKeys = this.keys, allowed: (String) -> Boolean = { true }, committer: FormationAtomicFileCommitter? = null) =
            EncryptedPatternSpeakingRecoveryJournal(store(keys, allowed, committer), owner)
        fun payload() = File(File(directory, owner.accountSubjectHash), "${hash(owner.sourceEntryId)}.payload")
    }
    private class MemoryKeys : FormationPayloadKeys {
        private val values = mutableMapOf<Pair<String, Int>, SecretKey>()
        var creations = 0
        override fun existing(accountSubjectHash: String, keyVersion: Int) = values[accountSubjectHash to keyVersion]
        override fun createIfAbsent(accountSubjectHash: String, keyVersion: Int) = values.getOrPut(accountSubjectHash to keyVersion) {
            creations++; KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
        override fun deleteAccount(accountSubjectHash: String) { values.keys.removeAll { it.first == accountSubjectHash } }
    }
    companion object {
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
