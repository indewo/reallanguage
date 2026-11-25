package com.reallanguage.app.feature.scene.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.scene.domain.SceneAnswer
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneQuestion
import com.reallanguage.app.feature.scene.domain.SceneStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SceneQuestionsScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before fun landscape() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        composeRule.waitUntil(5_000) {
            composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

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
        composeRule.onNodeWithText("第2问 / 最多10问").assertIsDisplayed()
        composeRule.onNodeWithTag("scene-questions-answer").assertDoesNotExist()
        composeRule.onNodeWithTag("scene-questions-previous").performClick()
        composeRule.onNodeWithTag("scene-questions-submit").performClick()

        assertEquals(
            listOf(SceneQuestionsAction.PreviousRequested, SceneQuestionsAction.AnswerSubmitted),
            actions,
        )
    }

    @Test
    fun sixthQuestionIsNotClampedToFive() {
        showProgress(5)
        composeRule.onNodeWithText("第6问 / 最多10问").assertIsDisplayed()
    }

    @Test
    fun tenthQuestionUsesActualExistingMaximum() {
        showProgress(9)
        composeRule.onNodeWithText("第10问 / 最多10问").assertIsDisplayed()
        composeRule.onNodeWithText("问题 5 / 最多 5 个").assertDoesNotExist()
    }

    @Test
    fun missingQuestionAfterGenerationReturnOffersRetryInsteadOfBlankSubmit() {
        val actions = mutableListOf<SceneQuestionsAction>()
        composeRule.setContent {
            RlTheme {
                SceneQuestionsScreen(
                    SceneQuestionsUiState(
                        start = SceneStart.DirectDescription("向同事解释项目延期"),
                        contentType = SceneContentType.DIALOGUE,
                        confirmedAnswers = (1..7).map { SceneAnswer("q$it", "已确认回答$it") },
                        loading = false,
                    ),
                    actions::add,
                )
            }
        }

        composeRule.onNodeWithTag("scene-questions-error").assertIsDisplayed()
        composeRule.onNodeWithText("确认回答").assertDoesNotExist()
        composeRule.onNodeWithTag("scene-questions-submit").performClick()
        assertEquals(listOf(SceneQuestionsAction.RetryRequested), actions)
    }

    @Test
    fun ordinaryAndLoadingQuestionKeepTheSameEntryGeometryWithoutAcceptingAnotherAnswer() {
        var loading by mutableStateOf(false)
        val actions = mutableListOf<SceneQuestionsAction>()
        val question = SceneQuestion("q1", "这次沟通发生在哪里？", listOf("办公室", "线上会议", "即时消息"))
        composeRule.setContent {
            RlTheme {
                SceneQuestionsScreen(
                    SceneQuestionsUiState(
                        start = SceneStart.DirectDescription("向同事解释项目延期"),
                        contentType = SceneContentType.DIALOGUE,
                        currentQuestion = question,
                        answerText = "办公室",
                        loading = loading,
                    ),
                    actions::add,
                )
            }
        }
        val tags = listOf(
            "scene-questions-header", "scene-questions-artwork", "scene-questions-progress",
            "scene-questions-options", "scene-questions-submit",
        )
        composeRule.onNodeWithTag("scene-questions-answer").assertDoesNotExist()
        val ordinary = tags.associateWith { tag ->
            composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        }
        composeRule.runOnIdle { loading = true }
        composeRule.waitForIdle()
        tags.forEach { tag ->
            assertEquals("Loading must not reshuffle the same entry's $tag",
                ordinary.getValue(tag), composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot)
        }
        composeRule.onNodeWithText(question.questionZh).assertExists()
        composeRule.onNodeWithTag("scene-questions-answer").assertDoesNotExist()
        composeRule.onNodeWithTag("scene-questions-submit").assertIsNotEnabled()
        composeRule.onNodeWithTag("scene-option-办公室").performScrollTo().performClick()
        assertTrue("A loading fallback must not dispatch another answer", actions.isEmpty())
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        java.io.File(instrumentation.targetContext.filesDir, "scene-questions-loading-same-entry.png").outputStream().use {
            composeRule.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun customAnswerCanReplaceASelectedOptionAndBeSubmitted() {
        var answer by mutableStateOf("办公室")
        var submitted: String? = null
        composeRule.setContent {
            RlTheme {
                SceneQuestionsScreen(SceneQuestionsUiState(
                    start = SceneStart.DirectDescription("沟通计划"),
                    contentType = SceneContentType.DIALOGUE,
                    currentQuestion = SceneQuestion("custom", "在哪里沟通？", listOf("办公室", "线上会议", "其他", "自行填写")),
                    loading = false, answerText = answer,
                )) { action ->
                    when (action) {
                        is SceneQuestionsAction.AnswerChanged -> answer = action.answer
                        SceneQuestionsAction.AnswerSubmitted -> submitted = answer
                        else -> Unit
                    }
                }
            }
        }
        composeRule.onNodeWithText("其他").assertDoesNotExist()
        composeRule.onNodeWithText("自行填写").assertDoesNotExist()
        composeRule.onNodeWithTag("scene-questions-custom-answer").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals("", answer) }
        composeRule.onNodeWithTag("scene-questions-submit").assertIsNotEnabled()
        composeRule.onNodeWithTag("scene-questions-answer").assertIsDisplayed().performTextReplacement("在园区咖啡店")
        composeRule.onNodeWithTag("scene-questions-submit").performClick()
        composeRule.runOnIdle { assertEquals("在园区咖啡店", submitted) }
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
