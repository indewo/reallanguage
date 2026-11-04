package com.reallanguage.app.feature.formation.domain

/** Versioned evidence in the confirmed English, with half-open UTF-16 offsets. */
data class SourceEvidence(
    val version: Int,
    val startUtf16: Int,
    val endUtf16: Int,
    val surfaceForm: String,
    val normalizedForm: String,
    val allowedForms: List<String>,
)

internal val sourceWordTokens = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['‘’ʼ＇\\-][\\p{L}\\p{M}\\p{N}]+)*")

fun SourceEvidence.hasValidStructure(): Boolean = version == 1 && startUtf16 >= 0 && endUtf16 > startUtf16 &&
    surfaceForm.isNotBlank() && normalizedForm.isNotBlank() && endUtf16 - startUtf16 == surfaceForm.length &&
    allowedForms == listOf(surfaceForm)

fun SourceEvidence.isValidFor(textEn: String): Boolean {
    if (!hasValidStructure() || endUtf16 > textEn.length || textEn.substring(startUtf16, endUtf16) != surfaceForm) return false
    val tokens = sourceWordTokens.findAll(textEn).toList()
    return tokens.any { it.range.first == startUtf16 } && tokens.any { it.range.last + 1 == endUtf16 }
}

/** WORD displays the original word; normalized metadata is not evidence for a different word. */
fun SourceEvidence.isValidForTarget(textEn: String, display: String, type: LearningTargetType): Boolean {
    if (!isValidFor(textEn)) return false
    if (type != LearningTargetType.WORD) return true
    return display.trim().equals(surfaceForm, ignoreCase = true) && sourceWordTokens.findAll(textEn).any {
        it.range.first == startUtf16 && it.range.last + 1 == endUtf16
    }
}

/** Legacy-only, unambiguous literal compatibility; never persists guessed evidence. */
fun uniqueLiteralSourceEvidence(textEn: String, display: String): SourceEvidence? {
    val literal = display.trim()
    if (literal.isEmpty()) return null
    val tokens = sourceWordTokens.findAll(textEn).toList()
    val ends = tokens.map { it.range.last + 1 }.toSet()
    val matches = tokens.mapNotNull { token ->
        val start = token.range.first
        val end = start.toLong() + literal.length
        if (end > textEn.length || end.toInt() !in ends || !textEn.regionMatches(start, literal, 0, literal.length, ignoreCase = true)) null
        else textEn.substring(start, end.toInt()).let { surface ->
            SourceEvidence(1, start, end.toInt(), surface, literal, listOf(surface))
        }
    }
    return matches.singleOrNull()?.takeIf { it.isValidFor(textEn) }
}
