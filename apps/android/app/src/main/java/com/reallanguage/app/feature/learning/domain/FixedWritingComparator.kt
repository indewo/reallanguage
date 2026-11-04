package com.reallanguage.app.feature.learning.domain

import java.text.Normalizer

sealed interface FixedWritingResult {
    data object Correct : FixedWritingResult
    data class Incorrect(
        val missingTokens: List<String>,
        val extraTokens: List<String>,
        val orderChanged: Boolean,
    ) : FixedWritingResult
}

enum class WritingTargetMatch { CORRECT, INCORRECT, UNRESOLVED }

class FixedWritingComparator(
    private val equivalentForms: Map<String, String> = DEFAULT_EQUIVALENT_FORMS,
) {
    fun compare(expected: String, actual: String): FixedWritingResult {
        val expectedTokens = tokenize(expected)
        val actualTokens = tokenize(actual)
        if (expectedTokens == actualTokens) return FixedWritingResult.Correct

        val remainingActual = actualTokens.toMutableList()
        val missing = expectedTokens.filterNot { remainingActual.remove(it) }
        val remainingExpected = expectedTokens.toMutableList()
        val extra = actualTokens.filterNot { remainingExpected.remove(it) }
        val sharedTokens = expectedTokens.toSet().intersect(actualTokens.toSet())
        val expectedSharedOrder = expectedTokens.filter { it in sharedTokens }
        val actualSharedOrder = actualTokens.filter { it in sharedTokens }
        return FixedWritingResult.Incorrect(
            missingTokens = missing,
            extraTokens = extra,
            orderChanged = expectedSharedOrder != actualSharedOrder,
        )
    }

    fun containsExpectedForm(actual: String, expectedForm: String): Boolean {
        val actualTokens = tokenize(actual)
        val expectedTokens = tokenize(expectedForm)
        if (expectedTokens.isEmpty() || expectedTokens.size > actualTokens.size) return false
        return actualTokens.windowed(expectedTokens.size).any { it == expectedTokens }
    }

    /** A different occurrence of the same expression is not evidence for this target. */
    fun matchTarget(expected: String, actual: String, forms: List<String>, span: WritingTargetSpan?): WritingTargetMatch {
        val fixed = tokenize(expected)
        val answer = tokenize(actual)
        if (fixed.isEmpty() || forms.isEmpty() || fixed.size.toLong() * answer.size > 1_000_000L)
            return WritingTargetMatch.UNRESOLVED
        val range: IntRange
        val target: List<String>
        if (span != null) {
            if (span.startUtf16 < 0 || span.endUtf16 <= span.startUtf16 || span.endUtf16 > expected.length ||
                expected.substring(span.startUtf16, span.endUtf16) != span.surfaceForm)
                return WritingTargetMatch.UNRESOLVED
            val words = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['‘’ʼ＇\\-][\\p{L}\\p{M}\\p{N}]+)*").findAll(expected).toList()
            if (words.none { it.range.first == span.startUtf16 } || words.none { it.range.last + 1 == span.endUtf16 })
                return WritingTargetMatch.UNRESOLVED
            target = tokenize(span.surfaceForm)
            val prefix = tokenize(expected.take(span.startUtf16))
            val suffix = tokenize(expected.substring(span.endUtf16))
            if (target.isEmpty() || fixed != prefix + target + suffix || forms.any { tokenize(it) != target })
                return WritingTargetMatch.UNRESOLVED
            range = prefix.size until prefix.size + target.size
        } else {
            // Compatibility for a literal legacy target only. Do not infer a
            // grammar slot, stem, synonym or an arbitrary repeated occurrence.
            val positions = forms.flatMap { form ->
                val tokens = tokenize(form)
                if (tokens.isEmpty() || tokens.size > fixed.size) emptyList()
                else fixed.windowed(tokens.size).mapIndexedNotNull { index, window ->
                    if (window == tokens) index until index + tokens.size else null
                }
            }.distinct()
            range = positions.singleOrNull() ?: return WritingTargetMatch.UNRESOLVED
            target = fixed.slice(range)
        }
        val n = fixed.size
        val m = answer.size
        val forward = Array(n + 1) { IntArray(m + 1) }
        val backward = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) forward[i][0] = i
        for (j in 0..m) forward[0][j] = j
        for (i in 1..n) for (j in 1..m) forward[i][j] = minOf(
            forward[i - 1][j] + 1, forward[i][j - 1] + 1,
            forward[i - 1][j - 1] + if (fixed[i - 1] == answer[j - 1]) 0 else 1,
        )
        for (i in 0..n) backward[i][m] = n - i
        for (j in 0..m) backward[n][j] = m - j
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) backward[i][j] = minOf(
            backward[i + 1][j] + 1, backward[i][j + 1] + 1,
            backward[i + 1][j + 1] + if (fixed[i] == answer[j]) 0 else 1,
        )
        val minimum = forward[n][m]
        val start = range.first
        val end = range.last + 1
        val matches = if (target.size > m) emptyList() else (0..m - target.size).filter { j ->
            answer.subList(j, j + target.size) == target &&
                forward[start][j] + backward[end][j + target.size] == minimum
        }
        if (matches.isEmpty()) return WritingTargetMatch.INCORRECT
        val actualStart = matches.singleOrNull() ?: return WritingTargetMatch.UNRESOLVED
        // Inspect every shortest-path edge touching the target, not one arbitrary
        // backtrace. Ambiguous deletion/substitution/position is never a score.
        for (i in start until end) for (j in 0..m) {
            if (forward[i][j] + 1 + backward[i + 1][j] == minimum)
                return WritingTargetMatch.UNRESOLVED
            if (j < m) {
                val cost = if (fixed[i] == answer[j]) 0 else 1
                if (forward[i][j] + cost + backward[i + 1][j + 1] == minimum &&
                    (cost != 0 || j != actualStart + i - start)) return WritingTargetMatch.UNRESOLVED
            }
        }
        for (i in start + 1 until end) for (j in 0 until m) {
            if (forward[i][j] + 1 + backward[i][j + 1] == minimum)
                return WritingTargetMatch.UNRESOLVED
        }
        return WritingTargetMatch.CORRECT
    }

    private fun tokenize(value: String): List<String> {
        // Mobile keyboards use typographic apostrophes. Fold only equivalent
        // apostrophe characters before the existing contraction table; never
        // rewrite the saved answer or discard a contraction's negation.
        // Canonically equivalent code points are the same spelling. Keep this
        // local to comparison: source offsets and saved input remain untouched.
        var normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
            .replace(APOSTROPHE_VARIANTS, "'").lowercase().trim()
        equivalentForms.forEach { (shortForm, fullForm) ->
            normalized = normalized.replace(shortForm.lowercase(), fullForm.lowercase())
        }
        return normalized
            .replace(Regex("[\\p{P}\\p{S}]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
    }

    companion object {
        private val APOSTROPHE_VARIANTS = Regex("[\u2018\u2019\u02BC\uFF07]")

        val DEFAULT_EQUIVALENT_FORMS = linkedMapOf(
            "won't" to "will not",
            "can't" to "cannot",
            "shan't" to "shall not",
            "i'm" to "i am",
            "you're" to "you are",
            "we're" to "we are",
            "they're" to "they are",
            "he's" to "he is",
            "she's" to "she is",
            "it's" to "it is",
            "that's" to "that is",
            "there's" to "there is",
            "i've" to "i have",
            "you've" to "you have",
            "we've" to "we have",
            "they've" to "they have",
            "i'll" to "i will",
            "you'll" to "you will",
            "we'll" to "we will",
            "they'll" to "they will",
            "he'll" to "he will",
            "she'll" to "she will",
            "i'd" to "i would",
            "you'd" to "you would",
            "we'd" to "we would",
            "they'd" to "they would",
            "didn't" to "did not",
            "doesn't" to "does not",
            "don't" to "do not",
            "isn't" to "is not",
            "aren't" to "are not",
            "wasn't" to "was not",
            "weren't" to "were not",
            "hasn't" to "has not",
            "haven't" to "have not",
            "hadn't" to "had not",
            "wouldn't" to "would not",
            "shouldn't" to "should not",
            "couldn't" to "could not",
            "mustn't" to "must not",
        )
    }
}
