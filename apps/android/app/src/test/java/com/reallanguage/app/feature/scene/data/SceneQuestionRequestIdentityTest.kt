package com.reallanguage.app.feature.scene.data

import com.reallanguage.app.feature.scene.domain.PublicSceneTemplate
import com.reallanguage.app.feature.scene.domain.RecentSceneRecommendation
import com.reallanguage.app.feature.scene.domain.SceneAnswer
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneQuestionRequest
import com.reallanguage.app.feature.scene.domain.SceneStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure production identity logic: no Android JSON, network, credentials or local data. */
class SceneQuestionRequestIdentityTest {
    private val original = SceneQuestionRequest(
        SceneStart.DirectDescription("练习会议沟通"), SceneContentType.DIALOGUE,
        listOf(SceneAnswer("q1", "同事"), SceneAnswer("q2", "会议室")),
        remainingQuestions = 3, remainingContentBudget = 10, operationId = "isolated-operation", stepIndex = 2,
    )

    @Test fun unchangedInputAlwaysReusesItsCommandWithoutObjectIdentityOrClickCount() {
        val command = sceneQuestionCommandId(original)
        repeat(4) {
            assertEquals(command, sceneQuestionCommandId(original.copy(
                start = SceneStart.DirectDescription("练习会议沟通"),
                confirmedAnswers = original.confirmedAnswers.map { it.copy() },
            )))
        }
        assertTrue(command.isNotBlank() && command.length <= 128)
    }

    @Test fun returningToThePreviousQuestionAndChangingItsAnswerCreatesANewStableCommand() {
        val changed = original.copy(confirmedAnswers = listOf(original.confirmedAnswers.first(), SceneAnswer("q2", "线上会议")))
        assertEquals(original.operationId, changed.operationId)
        assertEquals(original.stepIndex, changed.stepIndex)
        assertNotEquals(sceneQuestionCommandId(original), sceneQuestionCommandId(changed))
        assertEquals(sceneQuestionCommandId(changed), sceneQuestionCommandId(changed.copy()))
        assertEquals(sceneQuestionCommandId(original), sceneQuestionCommandId(original.copy()))
    }

    @Test fun everyTransmittedTopLevelFieldAndOrderedAnswerParticipatesInIdentity() {
        val variants = listOf(
            original.copy(operationId = "another-operation"),
            original.copy(start = SceneStart.DirectDescription("练习家庭沟通")),
            original.copy(contentType = SceneContentType.ARTICLE),
            original.copy(confirmedAnswers = listOf(SceneAnswer("replacement-question", "同事"), original.confirmedAnswers.last())),
            original.copy(confirmedAnswers = original.confirmedAnswers.reversed()),
            original.copy(remainingQuestions = 2),
            original.copy(remainingContentBudget = 9),
            original.copy(stepIndex = 3),
        )
        variants.forEachIndexed { index, revised ->
            assertNotEquals("Transmitted field variant $index must not reuse another payload's command",
                sceneQuestionCommandId(original), sceneQuestionCommandId(revised))
        }
    }

    @Test fun recommendationAndTemplatePayloadsIncludeEveryFieldAndTheirDiscriminator() {
        val recommendation = RecentSceneRecommendation("rec", "会议", "近期沟通", listOf("source-a", "source-b"))
        val request = original.copy(start = SceneStart.Recommendation(recommendation))
        val variants = listOf(
            recommendation.copy(recommendationId = "other-rec"),
            recommendation.copy(shortTopicZh = "安排"),
            recommendation.copy(whyRelevantZh = "近期安排"),
            recommendation.copy(sourceRefs = listOf("source-a", "source-c")),
            recommendation.copy(sourceRefs = recommendation.sourceRefs.reversed()),
        )
        variants.forEach { assertNotEquals(sceneQuestionCommandId(request),
            sceneQuestionCommandId(request.copy(start = SceneStart.Recommendation(it)))) }
        val template = PublicSceneTemplate("work", "工作沟通", "汇报进度")
        val templateRequest = original.copy(start = SceneStart.Template(template))
        listOf(template.copy(templateId = "other"), template.copy(titleZh = "协作"), template.copy(scopeZh = "澄清任务"))
            .forEach { assertNotEquals(sceneQuestionCommandId(templateRequest),
                sceneQuestionCommandId(templateRequest.copy(start = SceneStart.Template(it)))) }
        assertEquals(3, setOf(sceneQuestionCommandId(original), sceneQuestionCommandId(request),
            sceneQuestionCommandId(templateRequest)).size)
    }

    @Test fun fieldDelimitersAndExactUnicodeTextCannotAliasDifferentWireInputs() {
        val first = original.copy(confirmedAnswers = listOf(SceneAnswer("a:b", "c")))
        val second = original.copy(confirmedAnswers = listOf(SceneAnswer("a", "b:c")))
        assertNotEquals(sceneQuestionCommandId(first), sceneQuestionCommandId(second))
        val text = original.copy(start = SceneStart.DirectDescription("讨论 ☕ café"))
        assertNotEquals(sceneQuestionCommandId(text), sceneQuestionCommandId(text.copy(start = SceneStart.DirectDescription("讨论 ☕ cafe\u0301"))))
        assertNotEquals(sceneQuestionCommandId(text), sceneQuestionCommandId(text.copy(start = SceneStart.DirectDescription("讨论  ☕ café"))))
        assertEquals(sceneQuestionCommandId(text), sceneQuestionCommandId(text.copy()))
    }
}
