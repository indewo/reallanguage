package com.reallanguage.app.feature.formation.domain

/** Hard product boundary for selectable sentences. A semantic unit may contain
 * an enumeration, but not multiple finished sentences or a comma-separated new
 * reason, contrast, consequence, or follow-up proposition. */
fun String.hasMinimumSemanticGranularity(): Boolean {
    val multipleFinishedSentences = Regex("[。！？!?][\\s\\r\\n]*\\S").containsMatchIn(this)
    val propositionTransition = Regex(
        "[，,；;][\\s\\r\\n]*(?:因为|由于|但是|但|不过|可是|所以|因此|于是|然后|接着|后来|最后|结果|而且|并且|同时|其实|另外)",
    ).containsMatchIn(this)
    return !multipleFinishedSentences && !propositionTransition
}

private val finishedSentenceMarks = setOf('。', '！', '？', '!', '?')
private val propositionMarks = setOf('，', ',', '；', ';')
private val propositionTransitions = listOf(
    "因为", "由于", "但是", "不过", "可是", "所以", "因此", "于是",
    "然后", "接着", "后来", "最后", "结果", "而且", "并且", "同时",
    "其实", "另外", "但",
)

/**
 * Offline, lossless final boundary for formation. Every character remains in
 * source order, while finished sentences, dialogue lines, and new discourse
 * propositions become independently selectable units.
 */
fun String.toDeterministicSemanticUnits(dialogue: Boolean = false): List<SemanticUnit> {
    if (isBlank()) return emptyList()
    val boundaries = sortedSetOf(0, length)
    var index = 0
    while (index < length) {
        when {
            this[index] in finishedSentenceMarks -> {
                var end = index + 1
                while (end < length && this[end].isWhitespace()) end += 1
                if (end < length) boundaries += end
            }
            this[index] == '\n' || this[index] == '\r' -> {
                var end = index + 1
                while (end < length && (this[end] == '\n' || this[end] == '\r')) end += 1
                if (substring(0, index).isNotBlank() && substring(end).isNotBlank()) boundaries += end
            }
            this[index] in propositionMarks -> {
                var markerStart = index + 1
                while (markerStart < length && this[markerStart].isWhitespace()) markerStart += 1
                if (propositionTransitions.any { substring(markerStart).startsWith(it) }) boundaries += markerStart
            }
        }
        index += 1
    }
    return boundaries.zipWithNext()
        .map { (start, end) -> substring(start, end) }
        .filter { it.isNotBlank() }
        .mapIndexed { order, text ->
            val displayText = if (dialogue) {
                text.trim().replace(Regex("^(?:用户|对方)[：:]\\s*"), "")
            } else text
            SemanticUnit(
                unitId = "deterministic-${order + 1}",
                order = order,
                textZh = displayText,
            )
        }
}

fun List<SemanticUnit>.isExactMinimumSplitOf(source: String, dialogue: Boolean = false): Boolean {
    val exactSource = joinToString(separator = "") { it.textZh } == source
    val exactDialogue = dialogue && map { it.textZh.trim() } == source.lineSequence()
        .filter { it.isNotBlank() }
        .map { it.trim().replace(Regex("^(?:用户|对方)[：:]\\s*"), "") }
        .toList()
    return isNotEmpty() &&
        map { it.order } == indices.toList() &&
        (exactSource || exactDialogue) &&
        all { it.unitId.isNotBlank() && it.textZh.isNotBlank() && it.textZh.hasMinimumSemanticGranularity() }
}
