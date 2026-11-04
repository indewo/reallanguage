package com.reallanguage.app.feature.learning.domain

import org.junit.Assert.*
import org.junit.Test

class CompletedStepSnapshotTest {
    private val expression = SelectableExpression("word-1", "felt", 2, 6)
    private val snapshot = CompletedStepSnapshot("LR-02", "第 1 章 · 基础讲解", "I felt calm.", "我很平静。", "过去时",
        expressions = listOf(expression))
    private val saved = mapOf("version" to "1", "activeExpressionId" to "word-1", "expressionId" to "word-1",
        "expressionZh" to "感到；feel 的过去式", "phonetic" to "/felt/", "exampleEn" to "She felt happy.", "exampleZh" to "她感到开心。")

    @Test fun restoresOnlyTheActuallySelectedDeliveredExpression() {
        val restored = snapshot.withSavedExpression(saved)
        assertEquals(expression, restored.reviewedExpression?.expression)
        assertEquals("感到；feel 的过去式", restored.reviewedExpression?.explanation?.explanationZh)
        assertEquals(NaturalExpressionExample("She felt happy.", "她感到开心。"), restored.reviewedExpression?.explanation?.naturalExample)
        assertEquals(snapshot.contentEn, restored.contentEn)
    }

    @Test fun absentDeliveryDoesNotInventAnExpression() {
        assertNull(snapshot.withSavedExpression(null).reviewedExpression)
        assertNull(snapshot.withSavedExpression(mapOf("version" to "1", "reads" to "3")).reviewedExpression)
    }

    @Test fun restoresStructuredExplanationAsOneExactGroupWithoutGuessingLegacyText() {
        val grouped = saved + mapOf("expressionContextualMeaningZh" to "此处指感到平静", "expressionUsageZh" to "felt 后接形容词", "expressionHintZh" to "不要写成 feel。")
        val explanation = snapshot.withSavedExpression(grouped).reviewedExpression!!.explanation
        assertEquals("此处指感到平静", explanation.contextualMeaningZh)
        assertEquals("felt 后接形容词", explanation.usageZh)
        assertEquals("不要写成 feel。", explanation.expressionHintZh)
        assertNull(snapshot.withSavedExpression(grouped + ("expressionHintZh" to "")).reviewedExpression!!.explanation.expressionHintZh)
        assertNull(snapshot.withSavedExpression(saved).reviewedExpression!!.explanation.contextualMeaningZh)
        assertNull(snapshot.withSavedExpression(saved).reviewedExpression!!.explanation.usageZh)
    }

    @Test fun partiallySavedStructuredExplanationIsRejectedRatherThanInvented() {
        val group = mapOf("expressionContextualMeaningZh" to "此处指感到平静", "expressionUsageZh" to "felt 后接形容词", "expressionHintZh" to "")
        (group.keys.map { group - it } + listOf(group + ("expressionUsageZh" to " "), group + ("expressionHintZh" to "  "))).forEach {
            assertTrue(runCatching { snapshot.withSavedExpression(saved + it) }.isFailure)
        }
    }

    @Test fun anotherSentenceOrIncorrectSurfaceCannotBecomeThisExplanation() {
        assertTrue(runCatching { snapshot.withSavedExpression(saved + ("expressionId" to "word-99")) }.isFailure)
        assertTrue(runCatching { snapshot.copy(expressions = listOf(expression.copy(display = "feel"))).withSavedExpression(saved) }.isFailure)
    }

    @Test fun pendingOtherSelectionNeverUsesOldDeliveredExplanation() {
        assertNull(snapshot.withSavedExpression(saved + ("activeExpressionId" to "word-2")).reviewedExpression)
    }

    @Test fun invalidOrHalfDeliveredContentFailsInsteadOfReportingSuccess() {
        listOf(saved - "exampleZh", saved + ("expressionZh" to " "), saved + ("version" to "99")).forEach {
            assertTrue(runCatching { snapshot.withSavedExpression(it) }.isFailure)
        }
    }
}
