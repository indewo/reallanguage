package com.reallanguage.app.feature.formation.domain

enum class KnownKnowledgeType { VOCABULARY, PATTERN }

data class KnownKnowledgeCandidate(val ref: String, val type: KnownKnowledgeType, val display: String, val meaningZh: String)
data class KnownKnowledgeEntry(
    val candidate: KnownKnowledgeCandidate,
    val statusPriority: Int,
    val occurrenceCount: Long,
    val enteredOrder: Long,
    val learningScopes: Set<String> = emptySet(),
)

class KnownKnowledgeSelection(private val maxItems: Int = 16, private val maxCharacters: Int = 4000) {
    fun select(entries: List<KnownKnowledgeEntry>, currentScopes: Set<String>): List<KnownKnowledgeCandidate> {
        require(maxItems in 1..16 && maxCharacters in 1..4000)
        require(entries.map { it.candidate.ref }.distinct().size == entries.size)
        require(entries.all { it.statusPriority in 0..3 && it.occurrenceCount >= 0 && it.enteredOrder > 0 && it.candidate.isValid() })
        val eligible = entries.filter { it.candidate.type == KnownKnowledgeType.PATTERN || it.learningScopes.any(currentScopes::contains) }
            .sortedWith(compareBy<KnownKnowledgeEntry> { it.statusPriority }.thenBy { it.occurrenceCount }
                // Existing rowids establish order only within each knowledge table. Cross-kind
                // history has no shared timestamp; type/ref is a stable tie break, not a date.
                .thenBy { it.candidate.type.name }.thenBy { it.enteredOrder }.thenBy { it.candidate.ref })
        var remainingCharacters = maxCharacters
        return buildList {
            for (entry in eligible) {
                val candidate = entry.candidate
                val characters = candidate.display.codePointCount(0, candidate.display.length) + candidate.meaningZh.codePointCount(0, candidate.meaningZh.length)
                if (size == maxItems || characters > remainingCharacters) break
                add(candidate)
                remainingCharacters -= characters
            }
        }
    }
}

fun isValidKnowledgeRef(ref: String): Boolean = ref.length in 1..128 && !ref.any(Char::isWhitespace) &&
    KnownKnowledgeType.entries.any { ref.startsWith("${it.name}:") && ref.length > it.name.length + 1 }

fun KnownKnowledgeCandidate.isValid(): Boolean = isValidKnowledgeRef(ref) && ref.startsWith("${type.name}:") && display.isNotBlank() && meaningZh.isNotBlank()

sealed interface KnownKnowledgeSnapshotResult {
    data class Ready(val candidates: List<KnownKnowledgeCandidate>) : KnownKnowledgeSnapshotResult
    data object Failed : KnownKnowledgeSnapshotResult
}
fun interface EnglishKnowledgeSnapshotSource {
    suspend fun freeze(baseCommandId: String, requestDigest: String): KnownKnowledgeSnapshotResult
}

fun interface KnownKnowledgeReferenceSource {
    suspend fun read(refs: List<String>): List<KnownKnowledgeCandidate>?
}
