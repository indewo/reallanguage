package com.reallanguage.app.feature.review.domain

import java.security.MessageDigest
import java.util.UUID

/** Only the current live chapter entry owns this cache; nothing is persisted. */
class PatternIndependentGenerationMemory {
    private val nonce = UUID.randomUUID().toString()
    private val metadataGate = Any()
    @Volatile private var active = true
    private val deliveries = mutableMapOf<String, PatternIndependentGenerationResult>()

    internal fun commandId(identity: PatternIndependentReceiptIdentity?, requestWithoutCommand: String): String? {
        if (!active) return null
        val fields = listOf("pattern-review-generation-v1", nonce, identity?.accountId.orEmpty(),
            identity?.accountEpoch?.toString().orEmpty(), identity?.reviewChapterId.orEmpty(), identity?.entryId.orEmpty(),
            identity?.originalChapterId.orEmpty(), identity?.sourceSentenceId.orEmpty(), identity?.abilityId.orEmpty(),
            identity?.targetId.orEmpty(), requestWithoutCommand)
        val canonical = fields.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical)
        return UUID.nameUUIDFromBytes(digest).toString().takeIf { active }
    }

    internal fun read(commandId: String): PatternIndependentGenerationResult? = synchronized(metadataGate) {
        deliveries[commandId].takeIf { active }
    }

    internal fun remember(commandId: String, result: PatternIndependentGenerationResult): Boolean = synchronized(metadataGate) {
        if (!active || result == PatternIndependentGenerationResult.Failed) return@synchronized false
        deliveries[commandId] = result
        true
    }

    internal fun revoke() {
        active = false
        synchronized(metadataGate) { deliveries.clear() }
    }
}
