package com.reallanguage.app.feature.scene.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.scene.domain.SceneAnswer
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneQuestion
import com.reallanguage.app.feature.scene.domain.SceneStart
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SceneQuestionsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun answersCurrentQuestionAndCanReturnToPreviousQuestion() {
        val actions = mutableListOf<SceneQuestionsAction>()
        val previous = SceneQuestion("q1", "你和对方是什么关系？", emptyList())
        val answer = SceneAnswer("q1", "我们是同事")
        composeRule.setContent {
            RlTheme {
                SceneQuestionsScreen(
                    SceneQuestionsUiState(
                        start = SceneStart.DirectDescription("向同事解释项目延期"),
                        contentType = SceneContentType.DIALOGUE,
                        currentQuestion = SceneQuestion("q2", "这次沟通发生在哪里？", listOf("办公室", "线上会议", "即时消息")),
                        confirmedAnswers = listOf(answer),
                        history = listOf(AnsweredSceneQuestion(previous, answer)),
                        answerText = "办公室",
                        loading = false,
                    ),
                    actions::add,
                )
            }
        }

        composeRule.onNodeWithTag("scene-questions-header").assertIsDisplayed()
        composeRule.onNodeWithTag("scene-questions-artwork").assertIsDisplayed()
        composeRule.onNodeWithTag("scene-questions-options").assertIsDisplayed()
        composeRule.onNodeWithTag("scene-questions-answer").assertIsDisplayed()
        composeRule.onNodeWithText("问题 2 / 最多 10 个").assertIsDisplayed()
        composeRule.onNodeWithTag("scene-questions-previous").performClick()
        composeRule.onNodeWithTag("scene-questions-submit").performClick()

        assertEquals(
            listOf(SceneQuestionsAction.PreviousRequested, SceneQuestionsAction.AnswerSubmitted),
            actions,
        )
    }

    @Test
    fun sixthQuestionIsNotClampedToFiveAndAllTenIndicatorsRemain() {
        showProgress(5)
        composeRule.onNodeWithText("问题 6 / 最多 10 个").assertIsDisplayed()
        (1..10).forEach { composeRule.onNodeWithTag("scene-question-progress-$it").assertIsDisplayed() }
    }

    @Test
    fun tenthQuestionUsesActualExistingMaximum() {
        showProgress(9)
        composeRule.onNodeWithText("问题 10 / 最多 10 个").assertIsDisplayed()
        composeRule.onNodeWithText("问题 5 / 最多 5 个").assertDoesNotExist()
        (1..10).forEach { composeRule.onNodeWithTag("scene-question-progress-$it").assertIsDisplayed() }
    }

    private fun showProgress(confirmedCount: Int) {
        composeRule.setContent {
            RlTheme {
                SceneQuestionsScreen(SceneQuestionsUiState(
                    start = SceneStart.DirectDescription("向同事解释项目延期"),
                    contentType = SceneContentType.DIALOGUE,
                    confirmedAnswers = (1..confirmedCount).map { SceneAnswer("q$it", "已确认回答$it") },
                    loading = false,
                ), {})
            }
        }
    }
}
