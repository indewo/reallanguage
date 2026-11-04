package com.reallanguage.app.feature.review.domain

import java.security.MessageDigest

/** Temporary, entry-bound provenance. No audio or answer text is retained in this object. */
data class PatternIndependentReceiptIdentity(
    val accountId: String,
    val accountEpoch: Long,
    val reviewChapterId: String,
    val entryId: String,
    val originalChapterId: String,
    val sourceSentenceId: String,
    val abilityId: String,
    val targetId: String,
)

data class PatternIndependentReceipt(
    val version: Int,
    val identity: PatternIndependentReceiptIdentity,
    val exampleId: String,
    val commandId: String,
    val audioSha256: String,
    val requestSha256: String,
    val decision: PatternIndependentDecision,
    val structurePreserved: Boolean,
    val contentSuitable: Boolean,
    val pronunciationIntelligible: Boolean,
    val confidence: Double,
) {
    val evidenceId: String get() {
        val fields = listOf(version.toString(), identity.accountId, identity.accountEpoch.toString(),
            identity.reviewChapterId, identity.entryId, identity.originalChapterId, identity.sourceSentenceId,
            identity.abilityId, identity.targetId, exampleId, commandId, audioSha256, requestSha256, decision.name,
            structurePreserved.toString(), contentSuitable.toString(), pronunciationIntelligible.toString(), confidence.toString())
        val canonical = fields.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
        return "pattern-review-v1:" + MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    fun hasValidIndependentJudgment(): Boolean {
        if (version != 1 || identity.accountEpoch < 0 ||
            listOf(identity.accountId, identity.reviewChapterId, identity.entryId, identity.originalChapterId,
                identity.sourceSentenceId, identity.abilityId, identity.targetId, exampleId, commandId).any(String::isBlank) ||
            !audioSha256.matches(Regex("[a-f0-9]{64}")) || !requestSha256.matches(Regex("[a-f0-9]{64}")) ||
            !confidence.isFinite() || confidence !in .6..1.0
        ) return false
        val allSatisfied = structurePreserved && contentSuitable && pronunciationIntelligible
        return when (decision) {
            PatternIndependentDecision.PASS -> allSatisfied
            PatternIndependentDecision.FAIL -> !allSatisfied
            PatternIndependentDecision.INCONCLUSIVE -> false
        }
    }
}

/** The same lease spans chapter -> ability -> chapter, but never a new review entry. */
class ReviewPatternEntryLease(
    val accountId: String,
    val accountEpoch: Long,
    val reviewChapterId: String,
    val entryId: String,
) {
    @Volatile private var active = true
    private val databaseGate = Any()
    private val metadataGate = Any()
    private val registeredCommitments = mutableSetOf<String>()
    internal val generationMemory = PatternIndependentGenerationMemory()
    /** Entry mutations always acquire this gate before a SQLite transaction. */
    internal fun <T> serialized(block: () -> T): T = synchronized(databaseGate) { block() }
    fun isActiveFor(accountId: String?, accountEpoch: Long): Boolean = active && this.accountId.isNotBlank() &&
        reviewChapterId.isNotBlank() && entryId.isNotBlank() && this.accountEpoch >= 0 &&
        this.accountId == accountId && this.accountEpoch == accountEpoch
    internal fun rememberRegistered(receipt: PatternIndependentReceipt): Boolean = synchronized(metadataGate) {
        val identity = receipt.identity
        if (!isActiveFor(identity.accountId, identity.accountEpoch) || identity.reviewChapterId != reviewChapterId ||
            identity.entryId != entryId || !receipt.hasValidIndependentJudgment()) return@synchronized false
        registeredCommitments += receipt.evidenceId
        true
    }
    internal fun containsRegistered(receipt: PatternIndependentReceipt): Boolean = synchronized(metadataGate) {
        isActiveFor(receipt.identity.accountId, receipt.identity.accountEpoch) &&
            receipt.identity.reviewChapterId == reviewChapterId && receipt.identity.entryId == entryId &&
            receipt.evidenceId in registeredCommitments
    }
    fun revoke() {
        // UI/session disposal must never wait for a busy SQLite writer. Metadata
        // sections contain no database, capture or transport work.
        active = false
        synchronized(metadataGate) { registeredCommitments.clear() }
        generationMemory.revoke()
    }
}

interface PatternIndependentReceiptStore {
    val identity: PatternIndependentReceiptIdentity
    val generationMemory: PatternIndependentGenerationMemory? get() = null
    fun isActive(): Boolean
    /** Recheck inside the transaction, not just before a potentially suspended save. */
    suspend fun register(receipt: PatternIndependentReceipt, mayCommit: () -> Boolean = { true }): Boolean
}
