package com.reallanguage.app.feature.formation.data

import android.content.Context
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.learning.data.accountLearningAttemptsDirectory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.DigestOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Accepted example delivery only. Never a FormationRecoverySnapshot or a formal knowledge item. */
internal class ContentFixExamplesRecovery(
    context: Context,
    accountId: String,
    private val isOwnerActive: () -> Boolean,
    keys: FormationPayloadKeys,
    committer: FormationAtomicFileCommitter? = null,
) {
    private val accountHash = fixedSourceAccountHash(accountId)
    private val directory = File(accountLearningAttemptsDirectory(context.filesDir, accountId), "fix-examples-recovery")
    private val store = FormationRecoveryFileStore(directory, FormationPayloadCipher(keys, MAX_BYTES),
        { hash -> hash == accountHash && isOwnerActive() }, committer)
    private var working: Working? = null
    private data class Snapshot(val fingerprint: String, val details: Map<String, KnowledgeTargetDetails>)
    private data class Working(val owner: FormationPayloadOwner, var revision: Long?, var saved: Snapshot?, var next: Snapshot)

    suspend fun resolve(request: ContentFixRequest, plans: List<KnowledgeExampleGenerationRequest>,
        provider: KnowledgeExampleGenerationRepository): Map<String, KnowledgeTargetDetails>? {
        val owner = owner(request)
        val lockKey = directory.absolutePath + owner.sourceEntryId
        return locks[(lockKey.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            try {
                currentCoroutineContext().ensureActive()
                check(isOwnerActive())
                val fingerprint = fingerprint(request, plans)
                val saved = read(owner)
                val previous = working?.takeIf { it.owner == owner }
                val scope = if (previous != null) {
                    check(previous.next.fingerprint == fingerprint)
                    check(saved == previous.saved?.let { previous.revision to it } ||
                        saved == (previous.revision?.plus(1) ?: 0L) to previous.next)
                    if (saved?.second == previous.next) { previous.revision = saved.first; previous.saved = saved.second }
                    previous
                } else Working(owner, saved?.first, saved?.second, saved?.second ?: Snapshot(fingerprint, emptyMap())).also { working = it }
                check(scope.next.fingerprint == fingerprint)
                check(scope.next.details.keys.all { id -> plans.any { it.targetId == id } })
                // Freeze the identity before the first request; a changed body is never a cache miss.
                check(persist(scope))
                for (plan in plans) {
                    check(isOwnerActive()); currentCoroutineContext().ensureActive()
                    scope.next.details[plan.targetId]?.let { check(valid(it, plan)) } ?: run {
                        val result = provider.generate(plan) as? KnowledgeExampleGenerationResult.Generated ?: return@withLock null
                        currentCoroutineContext().ensureActive(); check(isOwnerActive() && valid(result.details, plan))
                        val accepted = result.details.copy(examples = result.details.examples.toList())
                        // Hold an accepted candidate across a local write failure, without calling its provider again.
                        scope.next = scope.next.copy(details = scope.next.details + (plan.targetId to accepted))
                        check(persist(scope))
                    }
                }
                check(isOwnerActive()); currentCoroutineContext().ensureActive()
                scope.next.details.toMap()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }

    /** Called only after the existing fixed-source/selection transaction has succeeded and read back. */
    suspend fun complete(request: ContentFixRequest) {
        try {
            val owner = owner(request)
            val record = read(owner) ?: return
            if (isOwnerActive() && store.discard(owner, record.first) == FormationPayloadRemoval.Removed) working = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The fixed transaction remains the authority; retry can finish local cleanup. */ }
    }

    private suspend fun read(owner: FormationPayloadOwner): Pair<Long, Snapshot>? = when (val value = store.load(owner)) {
        FormationPayloadLoad.Missing -> null
        FormationPayloadLoad.Unavailable -> error("Example recovery unavailable")
        is FormationPayloadLoad.Loaded -> try {
            val decoded = decode(value.plaintext)
            check(store.hasCurrentAccess(owner))
            value.identity.revision to decoded
        } finally { value.plaintext.fill(0) }
    }

    private suspend fun persist(scope: Working): Boolean {
        if (!isOwnerActive()) return false
        val current = read(scope.owner)
        if (current == (scope.revision?.plus(1) ?: 0L) to scope.next) {
            scope.revision = current.first; scope.saved = scope.next; return true
        }
        if (current != scope.saved?.let { scope.revision to it }) return false
        if (scope.saved == scope.next) return true
        val expected = scope.revision
        val bytes = encode(scope.next)
        try { store.compareAndSet(scope.owner, expected, 1, bytes) } finally { bytes.fill(0) }
        val after = read(scope.owner)
        if (after != (expected?.plus(1) ?: 0L) to scope.next || !isOwnerActive()) return false
        scope.revision = after.first; scope.saved = scope.next
        return true
    }

    private fun owner(request: ContentFixRequest) = FormationPayloadOwner(accountHash, "fix-examples-v1:" + hash(request.commandId.toByteArray(Charsets.UTF_8)))

    private fun fingerprint(request: ContentFixRequest, plans: List<KnowledgeExampleGenerationRequest>): String {
        val source = requireNotNull(request.sourceProjection)
        val digest = MessageDigest.getInstance("SHA-256")
        // No data-class toString delimiters or collection insertion order are identity boundaries.
        val sink = object : OutputStream() { override fun write(value: Int) = Unit; override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit }
        DataOutputStream(DigestOutputStream(sink, digest)).use { out ->
            out.writeInt(1); out.text(request.formationFlowId); out.text(request.commandId)
            out.text(request.sourceType); out.text(request.sourceTitle)
            out.text(source.formationFlowId); out.text(source.sourceEntryId); out.writeLong(source.sourceRevision)
            out.text(source.source.text); out.text(source.source.sourceType.name); out.text(source.source.contentShape.name); out.text(source.title)
            when (val origin = source.origin) {
                FixedSourceOrigin.Diary -> out.text("DIARY")
                is FixedSourceOrigin.Recording -> { out.text("RECORDING"); out.text(origin.recordingId); out.text(origin.transcriptId) }
                is FixedSourceOrigin.Scene -> { out.text("SCENE"); out.text(origin.operationId); out.text(origin.draftId) }
                is FixedSourceOrigin.Unavailable -> { out.text("UNAVAILABLE"); out.nullable(origin.reviewRequestId) }
            }
            out.writeInt(source.units.size); source.units.forEach { out.text(it.unitId); out.writeInt(it.order); out.text(it.textZh) }
            out.strings(source.selectedUnitIds); out.strings(request.selectedTargetIds.sorted())
            out.writeInt(request.selectedCandidateIds.size); request.selectedCandidateIds.toSortedMap().forEach { (id, candidate) -> out.text(id); out.text(candidate) }
            out.writeInt(request.chapters.size)
            request.chapters.forEach { chapter ->
                out.text(chapter.chapterId); out.writeInt(chapter.sentences.size)
                chapter.sentences.forEach { sentence ->
                    val selected = sentence.candidates.single { it.candidateId == request.selectedCandidateIds[sentence.unitId] }
                    out.text(sentence.unitId); out.text(sentence.textZh); out.writeBoolean(sentence.isFocus)
                    out.text(selected.candidateId); out.text(selected.textEn); out.strings(selected.reusedKnowledgeRefs.sorted())
                }
            }
            val targets = request.targets.filter { it.targetId in request.selectedTargetIds }.sortedBy { it.targetId }
            out.writeInt(targets.size)
            targets.forEach { target ->
                out.text(target.targetId); out.text(target.unitId); out.text(target.type.name); out.text(target.display); out.text(target.explanationZh)
                out.strings(target.structureParts); out.strings(target.relatedVocabulary); out.strings(target.learningScopes.sorted())
                out.nullable(target.knownKnowledgeRef); out.evidence(target.sourceEvidence)
                val items = target.relatedVocabularyItems
                out.writeInt(items?.size ?: -1)
                items?.forEach { out.text(it.itemRef); out.text(it.unitId); out.text(it.display); out.text(it.senseZh); out.text(it.unitType.name); out.evidence(it.sourceEvidence) }
                out.writeInt(target.relatedVocabularyItemRefs?.size ?: -1); target.relatedVocabularyItemRefs?.forEach { out.text(it) }
            }
            out.writeInt(plans.size)
            plans.sortedBy { it.targetId }.forEach { plan ->
                out.text(plan.targetId); out.text(plan.targetType.name); out.text(plan.display); out.text(plan.meaningZh); out.text(plan.commandId)
                out.writeInt(plan.sourceScene.size); plan.sourceScene.forEach { out.text(it.textEn); out.text(it.textZh) }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun DataOutputStream.strings(values: List<String>) { writeInt(values.size); values.forEach { text(it) } }
    private fun DataOutputStream.evidence(value: SourceEvidence?) {
        writeBoolean(value != null)
        value?.let { writeInt(it.version); writeInt(it.startUtf16); writeInt(it.endUtf16); text(it.surfaceForm); text(it.normalizedForm); strings(it.allowedForms) }
    }

    private fun encode(snapshot: Snapshot): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeInt(1); out.text(snapshot.fingerprint)
            out.writeInt(snapshot.details.size)
            snapshot.details.toSortedMap().forEach { (id, value) ->
                require(id == value.targetId)
                out.text(id); out.nullable(value.phonetic); out.text(value.pronunciationText); out.text(value.representativeScene)
                out.writeInt(when (value.naturalRevalidation) { null -> -1; false -> 0; true -> 1 })
                out.writeInt(value.examples.size)
                value.examples.forEach { out.text(it.textEn); out.text(it.textZh); out.text(it.pronunciationText) }
                require(bytes.size() <= MAX_BYTES)
            }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    private fun decode(bytes: ByteArray): Snapshot = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(bytes.size <= MAX_BYTES && input.readInt() == MAGIC && input.readInt() == 1)
        val fingerprint = input.text().also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        val count = input.readInt().also { require(it >= 0 && it <= input.available() / 20) }
        val details = linkedMapOf<String, KnowledgeTargetDetails>()
        repeat(count) {
            val id = input.text().also { require(it.isNotBlank() && it !in details) }
            val phonetic = input.nullable(); val pronunciation = input.text(); val scene = input.text()
            val natural = when (input.readInt()) { -1 -> null; 0 -> false; 1 -> true; else -> error("Invalid evidence flag") }
            require(input.readInt() == 3)
            val examples = List(3) { KnowledgeExampleDraft(input.text(), input.text(), input.text()) }
            details[id] = KnowledgeTargetDetails(id, phonetic, pronunciation, scene, examples, natural).also { require(valid(it)) }
        }
        require(input.available() == 0)
        Snapshot(fingerprint, details)
    }

    private fun valid(value: KnowledgeTargetDetails, plan: KnowledgeExampleGenerationRequest? = null): Boolean =
        value.targetId.isNotBlank() && (plan == null || value.targetId == plan.targetId) &&
            value.phonetic?.isNotBlank() != false && value.pronunciationText.isNotBlank() && value.representativeScene.isNotBlank() &&
            (plan?.targetType != KnowledgeTargetType.PATTERN || value.naturalRevalidation != null) &&
            value.examples.size == 3 && value.examples.all { it.textEn.isNotBlank() && it.textZh.isNotBlank() && it.pronunciationText.isNotBlank() } &&
            value.examples.map { it.textEn.trim().lowercase(java.util.Locale.ROOT) to it.textZh.trim() }.distinct().size == 3

    private fun DataOutputStream.text(value: String) {
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        require(encoded.remaining() <= MAX_BYTES)
        val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
        try { writeInt(bytes.size); write(bytes) } finally { bytes.fill(0) }
    }
    private fun DataInputStream.text(): String {
        val count = readInt().also { require(it in 0..MAX_BYTES && it <= available()) }
        val bytes = ByteArray(count).also(::readFully)
        return try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
        finally { bytes.fill(0) }
    }
    private fun DataOutputStream.nullable(value: String?) { writeBoolean(value != null); if (value != null) text(value) }
    private fun DataInputStream.nullable(): String? = when (readUnsignedByte()) { 0 -> null; 1 -> text(); else -> error("Invalid optional text") }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private companion object { const val MAGIC = 0x524c4b45; const val MAX_BYTES = 12 * 1024 * 1024; val locks = Array(64) { Mutex() } }
}
