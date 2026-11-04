package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.feature.formation.data.FormationAtomicFileCommitter
import com.reallanguage.app.feature.formation.data.FormationPayloadCipher
import com.reallanguage.app.feature.formation.data.FormationPayloadKeys
import com.reallanguage.app.feature.formation.data.FormationPayloadLoad
import com.reallanguage.app.feature.formation.data.FormationPayloadOwner
import com.reallanguage.app.feature.formation.data.FormationPayloadSave
import com.reallanguage.app.feature.formation.data.FormationRecoveryFileStore
import com.reallanguage.app.feature.learning.domain.ReadSpeakCompletionSignal
import com.reallanguage.app.feature.learning.domain.ReadingFeedback
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackAction
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackAspect
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackDetail
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackEvidence
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationCandidate
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationDecision
import com.reallanguage.app.feature.learning.domain.SpeakingTranscription
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
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

/** Real AES-GCM and files, with isolated in-memory keys. No HTTP, microphone, SQL or AI evidence. */
class FullSentenceSpeakingRecoveryJournalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun absentJournalIsMissingWithoutCreatingAKeyOrPayload() = runBlocking {
        val f = fixture()
        assertEquals(FullSpeakingRecoveryLoad.Missing, f.journal().load())
        assertEquals(0, f.keys.creations)
        assertFalse(f.directory.walkTopDown().any { it.isFile && it.name.endsWith(".payload") })
    }

    @Test fun trainingRoundTripsBothActionsAndExactUnicodeRequestThroughNewStore() = runBlocking {
        for (action in ReadingFeedbackAction.entries) {
            val f = fixture()
            assertEquals(0L, f.journal().save(null, f.trainingPending))
            assertEquals(FullSpeakingRecoveryLoad.Loaded(0, f.trainingPending), f.journal().load())
            val encrypted = f.payload().readBytes().toString(Charsets.UTF_8)
            listOf(f.recordingId, "NOT_AUDIO", "确认 café 😀", f.body).forEach {
                assertFalse("Private request data must not be plaintext", encrypted.contains(it))
            }
            val delivered = f.trainingDelivered.copy(training = f.feedback.copy(action = action))
            assertEquals(1L, f.journal().save(0, delivered))
            assertEquals(FullSpeakingRecoveryLoad.Loaded(1, delivered), f.journal().load())
            assertEquals(1, f.keys.creations)
        }
    }

    @Test fun structuredAiFeedbackRoundTripsWithItsEvidenceLabels() = runBlocking {
        val f = fixture()
        val feedback = ReadingFeedback(
            "整句完整，个别短语可更连贯。",
            ReadingFeedbackAction.CONTINUE,
            .88,
            listOf(
                ReadingFeedbackDetail(ReadingFeedbackAspect.WORD_OR_PHRASE, "wonderful day", "短语边界清楚。", "保持连贯。", ReadingFeedbackEvidence.AUDIO),
                ReadingFeedbackDetail(ReadingFeedbackAspect.RHYTHM, null, "句末停顿自然。", "继续按意群朗读。", ReadingFeedbackEvidence.AUDIO),
                ReadingFeedbackDetail(ReadingFeedbackAspect.COMPLETENESS, null, "识别文本覆盖整句。", "继续保持完整。", ReadingFeedbackEvidence.TRANSCRIPT),
            ),
        )
        val snapshot = FullSpeakingRecoverySnapshot(
            f.recordingId,
            FullSpeakingRecoveryPhase.TrainingDelivered,
            training = feedback,
        )
        assertEquals(0L, f.journal().save(null, snapshot))
        assertEquals(FullSpeakingRecoveryLoad.Loaded(0, snapshot), f.journal().load())
    }

    @Test fun asrKeepsOriginalBodyAndEvaluationKeepsItsOwnFrozenBodyAndCandidate() = runBlocking {
        val f = fixture()
        val pending = f.trainingPending.copy(phase = FullSpeakingRecoveryPhase.TranscriptionPending)
        assertEquals(0L, f.journal().save(null, pending))
        assertEquals(FullSpeakingRecoveryLoad.Loaded(0, pending), f.journal().load())
        assertEquals(1L, f.journal().save(0, f.transcriptionDelivered))
        assertEquals(FullSpeakingRecoveryLoad.Loaded(1, f.transcriptionDelivered), f.journal().load())
        // Whitespace and confirmed wording are custody data; the journal must not trim or recreate them.
        val evaluating = f.evaluationPending.copy(confirmedTranscript = "  She worked up the courage.  ",
            answerAudioPlayed = true)
        assertNotEquals(f.transcriptionDelivered.requestBody, evaluating.requestBody)
        assertEquals(2L, f.journal().save(1, evaluating))
        assertEquals(FullSpeakingRecoveryLoad.Loaded(2, evaluating), f.journal().load())
        val delivered = evaluating.copy(phase = FullSpeakingRecoveryPhase.EvaluationDelivered,
            requestBody = null, evaluation = f.candidate)
        assertEquals(3L, f.journal().save(2, delivered))
        assertEquals(FullSpeakingRecoveryLoad.Loaded(3, delivered), f.journal().load())
    }

    @Test fun nullConfidenceEmptyAsrAndScoreBoundariesDoNotInventMasteryRestrictions() = runBlocking {
        for (confidence in listOf(null, 0.0, 1.0)) {
            val f = fixture()
            val snapshot = f.transcriptionDelivered.copy(transcription = SpeakingTranscription("", listOf(""), confidence))
            assertEquals(0L, f.journal().save(null, snapshot))
            assertEquals(FullSpeakingRecoveryLoad.Loaded(0, snapshot), f.journal().load())
        }
        for (decision in SpeakingEvaluationDecision.entries) for (score in listOf(0, 100)) {
            val f = fixture()
            // Eligibility/score interpretation belongs to the existing VM, not encrypted storage.
            val snapshot = f.evaluationDelivered.copy(evaluation = f.candidate.copy(decision = decision,
                completenessScore = score, coreMeaningRecognizable = false, pronunciationIntelligible = false))
            assertEquals(0L, f.journal().save(null, snapshot))
            assertEquals(FullSpeakingRecoveryLoad.Loaded(0, snapshot), f.journal().load())
        }
    }

    @Test fun lostCommitAcknowledgementReplaysExactlyWithoutCreatingAnotherRevision() = runBlocking {
        val f = fixture()
        var commits = 0
        val lostAcknowledgement = FormationAtomicFileCommitter { target, envelope, allowed ->
            check(allowed())
            target.writeBytes(envelope)
            commits++
            throw IOException("isolated lost acknowledgement")
        }
        assertNull(f.journal(committer = lostAcknowledgement).save(null, f.trainingPending))
        assertEquals(FullSpeakingRecoveryLoad.Loaded(0, f.trainingPending), f.journal().load())
        val ciphertext = f.payload().readBytes()
        assertEquals(0L, f.journal(committer = lostAcknowledgement).save(null, f.trainingPending))
        assertEquals(1, commits)
        assertArrayEquals(ciphertext, f.payload().readBytes())
    }

    @Test fun casRejectsChangedStaleReplayAndTerminalMarkersKeepRevisionWithoutRawData() = runBlocking {
        val f = fixture()
        assertEquals(0L, f.journal().save(null, f.trainingPending))
        assertEquals(0L, f.journal().save(null, f.trainingPending))
        assertNull(f.journal().save(null, f.trainingPending.copy(recordingId = "different-recording")))
        val blocked = FullSpeakingRecoverySnapshot(f.recordingId, FullSpeakingRecoveryPhase.Blocked)
        assertEquals(1L, f.journal().save(0, blocked))
        assertEquals(FullSpeakingRecoveryLoad.Loaded(1, blocked), f.journal().load())
        val cleared = blocked.copy(phase = FullSpeakingRecoveryPhase.Cleared)
        assertEquals(2L, f.journal().save(1, cleared))
        assertEquals(2L, f.journal().save(1, cleared))
        assertNull(f.journal().save(0, f.trainingPending))
        val next = f.trainingPending.copy(recordingId = "explicit-new-recording")
        assertEquals(3L, f.journal().save(2, next))
        assertEquals(FullSpeakingRecoveryLoad.Loaded(3, next), f.journal().load())
        assertFalse(f.directory.walkTopDown().any { it.name.endsWith(".discarded") })
    }

    @Test fun crossPhasePayloadsAreRejectedWithoutReplacingTheConfirmedRecord() = runBlocking {
        val f = fixture()
        val valid = listOf(f.trainingPending, f.trainingDelivered,
            f.trainingPending.copy(phase = FullSpeakingRecoveryPhase.TranscriptionPending),
            f.transcriptionDelivered, f.evaluationPending, f.evaluationDelivered,
            FullSpeakingRecoverySnapshot(f.recordingId, FullSpeakingRecoveryPhase.Blocked),
            FullSpeakingRecoverySnapshot(f.recordingId, FullSpeakingRecoveryPhase.Cleared))
        val invalid = valid.flatMap { snapshot ->
            buildList {
                if (snapshot.requestBody != null) add(snapshot.copy(requestBody = null))
                else add(snapshot.copy(requestBody = f.body))
                if (snapshot.training != null) add(snapshot.copy(training = null))
                else add(snapshot.copy(training = f.feedback))
                if (snapshot.transcription != null) add(snapshot.copy(transcription = null))
                else add(snapshot.copy(transcription = f.transcription))
                if (snapshot.evaluation != null) add(snapshot.copy(evaluation = null))
                else add(snapshot.copy(evaluation = f.candidate))
                if (snapshot.confirmedTranscript != null) add(snapshot.copy(confirmedTranscript = null))
                else add(snapshot.copy(confirmedTranscript = "not allowed in this phase"))
                if (snapshot.phase != FullSpeakingRecoveryPhase.EvaluationPending &&
                    snapshot.phase != FullSpeakingRecoveryPhase.EvaluationDelivered) add(snapshot.copy(answerAudioPlayed = true))
            }
        }
        assertInvalidPreserves(f, invalid)
    }

    @Test fun invalidDtoValuesAndUnpairedSurrogatesCannotBecomeRecoverableCandidates() = runBlocking {
        val f = fixture()
        val invalid = mutableListOf(
            f.trainingPending.copy(recordingId = " "), f.trainingPending.copy(requestBody = "\t"),
            f.trainingPending.copy(recordingId = "broken:\uD800"), f.trainingPending.copy(requestBody = "broken:\uDC00"),
            f.trainingDelivered.copy(training = f.feedback.copy(feedbackZh = " ")),
            f.trainingDelivered.copy(training = f.feedback.copy(feedbackZh = "broken:\uD800")),
            f.transcriptionDelivered.copy(transcription = f.transcription.copy(transcript = "broken:\uD800")),
            f.transcriptionDelivered.copy(transcription = f.transcription.copy(lowConfidenceSegments = listOf("broken:\uDC00"))),
            f.evaluationPending.copy(confirmedTranscript = "\n"),
            f.evaluationPending.copy(confirmedTranscript = "broken:\uD800"),
            f.evaluationDelivered.copy(evaluation = f.candidate.copy(feedbackZh = "")),
            f.evaluationDelivered.copy(evaluation = f.candidate.copy(feedbackZh = "broken:\uDC00")),
        )
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.001, 1.001).forEach {
            invalid += f.transcriptionDelivered.copy(transcription = f.transcription.copy(confidence = it))
        }
        listOf(-1, 101, Int.MIN_VALUE, Int.MAX_VALUE).forEach {
            invalid += f.evaluationDelivered.copy(evaluation = f.candidate.copy(completenessScore = it))
        }
        assertInvalidPreserves(f, invalid)
    }

    @Test fun utf8ByteLimitsPermitExactBodyAndRecordingBoundaryButRejectOneByteMore() = runBlocking {
        val f = fixture()
        val boundary = f.trainingPending.copy(recordingId = "r".repeat(1024), requestBody = "中".repeat(4_000_000))
        assertEquals(0L, f.journal().save(null, boundary))
        assertEquals(FullSpeakingRecoveryLoad.Loaded(0, boundary), f.journal().load())
        val before = f.payload().readBytes()
        for (invalid in listOf(boundary.copy(requestBody = boundary.requestBody + "x"),
            boundary.copy(recordingId = boundary.recordingId + "x"),
            boundary.copy(recordingId = "中".repeat(342)))) {
            assertNull(f.journal().save(0, invalid))
            assertArrayEquals(before, f.payload().readBytes())
        }
    }

    @Test fun totalPayloadLimitIsIndependentOfTheLargerEncryptedStoreLimit() = runBlocking {
        val f = fixture()
        // Both individual fields fit; their combined payload exceeds the agreed 15,000,000 bytes.
        val oversized = f.transcriptionDelivered.copy(requestBody = "x".repeat(12_000_000),
            transcription = SpeakingTranscription("中".repeat(1_000_000), emptyList(), null))
        assertNull(f.journal().save(null, oversized))
        assertEquals(FullSpeakingRecoveryLoad.Missing, f.journal().load())
        assertEquals(0, f.keys.creations)
    }

    @Test fun authenticatedTruncationUnknownVersionPhaseBooleanAndBadUtf8AreUnavailable() = runBlocking {
        val source = fixture()
        assertEquals(0L, source.journal().save(null, source.evaluationPending))
        val original = source.plaintext()
        // The versioned LR06 format starts with magic/version/phase and ends with a strict played byte.
        assertEquals(0x524c5236, ByteBuffer.wrap(original).int)
        assertEquals(2, ByteBuffer.wrap(original, 4, 4).int)
        val invalid = listOf(
            byteArrayOf(0), original.copyOf(original.size - 1), original + byteArrayOf(0),
            original.copyOf().also { ByteBuffer.wrap(it).putInt(0, 0x524c5233) },
            original.copyOf().also { ByteBuffer.wrap(it).putInt(4, 3) },
            original.copyOf().also { ByteBuffer.wrap(it).putInt(8, 99) },
            original.copyOf().also { it[it.lastIndex] = 2 },
            original.copyOf().also { val offset = findBytes(it, "NOT_AUDIO"); it[offset] = 0xc3.toByte(); it[offset + 1] = 0x28 },
        )
        for (payload in invalid) assertAuthenticatedMalformedIsProtected(payload)
        original.fill(0)
    }

    @Test fun legacyVersionOneTrainingFeedbackStillLoadsWithoutInventingDetailEvidence() = runBlocking {
        val f = fixture()
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                fun text(value: String) {
                    val encoded = value.toByteArray(Charsets.UTF_8)
                    output.writeInt(encoded.size)
                    output.write(encoded)
                }
                output.writeInt(0x524c5236)
                output.writeInt(1)
                output.writeInt(2)
                text(f.recordingId)
                output.writeInt(-1)
                output.writeByte(1)
                output.writeInt(1)
                text(f.feedback.feedbackZh)
                output.writeByte(0)
                output.writeByte(0)
                output.writeInt(-1)
                output.writeByte(0)
            }
            buffer.toByteArray()
        }
        assertEquals(FormationPayloadSave.Saved(0), f.store().compareAndSet(f.owner, null, 1, bytes))
        assertEquals(FullSpeakingRecoveryLoad.Loaded(0, f.trainingDelivered), f.journal().load())
    }

    @Test fun lr03AndUnrelatedAuthenticatedPayloadsCannotBeReadOrReplacedAsLr06() = runBlocking {
        val source = fixture()
        val lr03 = EncryptedReadSpeakRecoveryJournal(source.store(), source.owner)
        assertEquals(0L, lr03.save(null, ReadSpeakRecoverySnapshot(ReadSpeakCompletionSignal.TOUCH_REACHED_END,
            "isolated-command", source.body)))
        val original = source.plaintext()
        assertAuthenticatedMalformedIsProtected(original)
        assertAuthenticatedMalformedIsProtected("unrelated confirmed source snapshot".toByteArray())
        original.fill(0)
    }

    @Test fun corruptedCiphertextCannotBeTreatedAsMissingOrOverwritten() = runBlocking {
        for (damage in listOf<(ByteArray) -> ByteArray>(
            { it.copyOf().also { bytes -> bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte() } },
            { it.copyOf(it.size - 1) }, { it + byteArrayOf(0) },
        )) {
            val f = fixture()
            assertEquals(0L, f.journal().save(null, f.trainingPending))
            val damaged = damage(f.payload().readBytes())
            f.payload().writeBytes(damaged)
            assertEquals(FullSpeakingRecoveryLoad.Unavailable, f.journal().load())
            assertNull(f.journal().save(null, f.trainingPending))
            assertNull(f.journal().save(0, f.trainingPending))
            assertArrayEquals(damaged, f.payload().readBytes())
        }
    }

    @Test fun missingKeyAndDeniedOwnerNeverRecreateKeysOrReplacePrivateData() = runBlocking {
        val f = fixture()
        assertEquals(0L, f.journal().save(null, f.trainingPending))
        val before = f.payload().readBytes()
        val missing = MemoryKeys()
        for (reader in listOf(f.journal(allowed = { false }), f.journal(keys = missing))) {
            assertEquals(FullSpeakingRecoveryLoad.Unavailable, reader.load())
            assertNull(reader.save(null, f.trainingPending))
            assertNull(reader.save(0, f.trainingPending))
        }
        assertEquals(0, missing.creations)
        assertArrayEquals(before, f.payload().readBytes())
    }

    @Test fun copiedCiphertextCannotAuthenticateForAnotherSentenceOrAccount() = runBlocking {
        val f = fixture()
        assertEquals(0L, f.journal().save(null, f.trainingPending))
        val encrypted = f.payload().readBytes()
        for (other in listOf(f.owner.copy(sourceEntryId = "LR06:${hash("other-sentence-round")}"),
            f.owner.copy(accountSubjectHash = hash("other-account")))) {
            val destination = f.payload(other)
            check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
            destination.writeBytes(encrypted)
            val reader = EncryptedFullSpeakingRecoveryJournal(f.store(), other)
            assertEquals(FullSpeakingRecoveryLoad.Unavailable, reader.load())
            assertNull(reader.save(null, f.trainingPending))
            assertArrayEquals(encrypted, destination.readBytes())
        }
        assertEquals(FullSpeakingRecoveryLoad.Loaded(0, f.trainingPending), f.journal().load())
    }

    @Test fun saveRequiresAuthenticatedReadBackAndStillCurrentAccess() = runBlocking {
        val broken = fixture()
        val corrupting = FormationAtomicFileCommitter { target, _, allowed ->
            check(allowed()); target.writeBytes(byteArrayOf(1, 2, 3))
        }
        assertNull(broken.journal(committer = corrupting).save(null, broken.trainingPending))
        assertTrue("The attempted commit must really have run", broken.payload().exists())
        assertEquals(FullSpeakingRecoveryLoad.Unavailable, broken.journal().load())
        assertNull(broken.journal().save(null, broken.trainingPending))
        val f = fixture()
        var allowed = true
        val revoking = FormationAtomicFileCommitter { target, envelope, canCommit ->
            check(canCommit()); target.writeBytes(envelope); allowed = false
        }
        val reader = f.journal(allowed = { allowed }, committer = revoking)
        assertNull(reader.save(null, f.trainingPending))
        assertEquals(FullSpeakingRecoveryLoad.Unavailable, reader.load())
        allowed = true
        assertEquals(FullSpeakingRecoveryLoad.Loaded(0, f.trainingPending), f.journal().load())
    }

    @Test fun cancellationFromAccessCheckIsNotTurnedIntoMissingOrFailedSave() = runBlocking {
        val f = fixture()
        val marker = Any()
        val expected = OwnershipCancellation(marker)
        val journal = f.journal(allowed = { throw expected })
        for (operation in listOf<suspend () -> Any?>({ journal.load() }, { journal.save(null, f.trainingPending) })) {
            var caught: CancellationException? = null
            try { operation() } catch (cancelled: CancellationException) { caught = cancelled }
            assertSame(expected, caught)
            assertSame(marker, (caught as OwnershipCancellation).marker)
        }
        assertEquals(0, f.keys.creations)
        assertFalse(f.payload().exists())
    }

    private suspend fun assertInvalidPreserves(f: Fixture, invalid: List<FullSpeakingRecoverySnapshot>) {
        assertEquals(0L, f.journal().save(null, f.trainingPending))
        val before = f.payload().readBytes()
        invalid.forEachIndexed { index, snapshot ->
            assertNull("Invalid DTO $index must not replace confirmed ciphertext", f.journal().save(0, snapshot))
            assertArrayEquals(before, f.payload().readBytes())
            assertEquals(FullSpeakingRecoveryLoad.Loaded(0, f.trainingPending), f.journal().load())
        }
    }

    private suspend fun assertAuthenticatedMalformedIsProtected(payload: ByteArray) {
        val f = fixture()
        assertEquals(FormationPayloadSave.Saved(0), f.store().compareAndSet(f.owner, null, 1, payload))
        val before = f.payload().readBytes()
        assertEquals(FullSpeakingRecoveryLoad.Unavailable, f.journal().load())
        assertNull(f.journal().save(null, f.trainingPending))
        assertNull(f.journal().save(0, f.trainingPending))
        assertArrayEquals(before, f.payload().readBytes())
    }

    private fun fixture() = Fixture(temporary.newFolder(), UUID.randomUUID().toString())

    // Coroutine stack-trace recovery can copy a plain CancellationException; an extra field prevents
    // that diagnostic copy, so this assertion measures propagation rather than recovered-stack identity.
    private class OwnershipCancellation(val marker: Any) : CancellationException("isolated ownership cancellation")

    private class Fixture(val directory: File, id: String) {
        val keys = MemoryKeys()
        val owner = FormationPayloadOwner(hash("account:$id"), "LR06:${hash("chapter:$id:focus:fixed-English:attempt-4")}")
        val recordingId = "recording-$id"
        val body = """{ "commandId":"command-$id", "audio":"NOT_AUDIO", "text":"确认 café 😀", "spacing" : "kept" }"""
        val feedback = ReadingFeedback("保留重音与完整语意 café 😀。", ReadingFeedbackAction.CONTINUE)
        val transcription = SpeakingTranscription("She worked up the courage. 她鼓起勇气。", listOf("worked up", "the courage"), 0.75)
        val candidate = SpeakingEvaluationCandidate(SpeakingEvaluationDecision.PASS, 87, true, true, "语意完整；重音清楚 café 😀。")
        val trainingPending = FullSpeakingRecoverySnapshot(recordingId, FullSpeakingRecoveryPhase.TrainingPending, body)
        val trainingDelivered = FullSpeakingRecoverySnapshot(recordingId, FullSpeakingRecoveryPhase.TrainingDelivered, training = feedback)
        val transcriptionDelivered = FullSpeakingRecoverySnapshot(recordingId, FullSpeakingRecoveryPhase.TranscriptionDelivered,
            body, transcription = transcription)
        val evaluationPending = FullSpeakingRecoverySnapshot(recordingId, FullSpeakingRecoveryPhase.EvaluationPending,
            body.replace("command-$id", "evaluation-$id"), transcription = transcription,
            confirmedTranscript = "She worked up the courage.")
        val evaluationDelivered = evaluationPending.copy(phase = FullSpeakingRecoveryPhase.EvaluationDelivered,
            requestBody = null, evaluation = candidate)

        fun store(keys: MemoryKeys = this.keys, allowed: (String) -> Boolean = { true },
            committer: FormationAtomicFileCommitter? = null) = FormationRecoveryFileStore(directory,
            FormationPayloadCipher(keys, maxPlaintextBytes = 16 * 1024 * 1024), allowed, committer)
        fun journal(keys: MemoryKeys = this.keys, allowed: (String) -> Boolean = { true },
            committer: FormationAtomicFileCommitter? = null) = EncryptedFullSpeakingRecoveryJournal(store(keys, allowed, committer), owner)
        fun payload(identity: FormationPayloadOwner = owner) = File(File(directory, identity.accountSubjectHash),
            "${hash(identity.sourceEntryId)}.payload")
        suspend fun plaintext() = (store().load(owner) as FormationPayloadLoad.Loaded).plaintext
    }

    /** Only custody is substituted. Encryption, owner AAD, CAS and disk read-back are production code. */
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
        fun findBytes(bytes: ByteArray, text: String): Int {
            val target = text.toByteArray(Charsets.UTF_8)
            return (0..bytes.size - target.size).first { start -> target.indices.all { bytes[start + it] == target[it] } }
        }
    }
}
