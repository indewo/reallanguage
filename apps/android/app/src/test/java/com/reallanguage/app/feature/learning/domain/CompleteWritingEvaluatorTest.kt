package com.reallanguage.app.feature.learning.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompleteWritingEvaluatorTest {
    private val evaluator = CompleteWritingEvaluator(FixedWritingComparator())

    @Test
    fun `whole sentence and every locked target settle independently`() {
        val result = evaluator.evaluate(
            sentences = listOf(sentence),
            inputs = mapOf("s1" to "I can't run the team today"),
            targets = listOf(
                LockedWritingTarget("meaning-run", "s1", WritingTargetType.MEANING, listOf("run")),
                LockedWritingTarget("meaning-company", "s1", WritingTargetType.MEANING, listOf("company")),
                LockedWritingTarget("pattern-can-not", "s1", WritingTargetType.PATTERN, listOf("cannot run")),
            ),
            helpUsed = false,
        ) as FinalWritingBatchResult.Valid

        val sentenceResult = result.sentenceResults.single()
        assertFalse(sentenceResult.wholeSentencePassed)
        assertTrue(sentenceResult.targetResults.first { it.targetId == "meaning-run" }.passed)
        assertFalse(sentenceResult.targetResults.first { it.targetId == "meaning-company" }.passed)
        assertTrue(sentenceResult.targetResults.first { it.targetId == "pattern-can-not" }.passed)
    }

    companion object {
        private val sentence = FixedLearningSentence(
            "s1",
            "I cannot run the company today.",
            "我今天不能经营公司。",
            true,
        )
    }
}
