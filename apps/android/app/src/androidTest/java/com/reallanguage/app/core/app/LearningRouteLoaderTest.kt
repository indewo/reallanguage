package com.reallanguage.app.core.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.reallanguage.app.core.designsystem.RlTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LearningRouteLoaderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unfinishedFormationFailureDoesNotClaimFormalChaptersExistAndReturnsToItsList() {
        var returned = 0
        compose.setContent {
            RlTheme {
                LearningRouteLoader(HomeDiaryRoute.ResumeLearning("source-1", "FORMATION:LEARNING_TARGETS", "1"),
                    load = { null }, onReady = { fail("No source was loaded") }, onReturn = { returned++ })
            }
        }
        compose.onNodeWithText("已固定内容不受影响。").assertDoesNotExist()
        compose.onNodeWithText("返回章节").assertDoesNotExist()
        compose.onNodeWithText("返回未完成学习").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, returned) }
    }

    @Test fun unfinishedFormationRetryKeepsTheOriginalSourceRequest() {
        var calls = 0
        var ready: HomeDiaryRoute? = null
        val restored = HomeDiaryRoute.IncompleteLearning()
        compose.setContent {
            RlTheme {
                LearningRouteLoader(HomeDiaryRoute.ResumeLearning("source-1", "FORMATION:LEARNING_TARGETS", "1"),
                    load = { calls++; if (calls == 1) null else restored },
                    onReady = { ready = it }, onReturn = { fail("Retry must not leave the requested source") })
            }
        }
        compose.onNodeWithText("重试").performClick()
        compose.runOnIdle { assertEquals(2, calls); assertEquals(restored, ready) }
    }

    @Test fun reviewContentReadFailureCanRetryAndNeverOpensIncompleteContent() {
        var calls = 0
        compose.setContent {
            RlTheme {
                LearningContentLoader("review:chapter:sentence", load = { calls++; if (calls == 1) null else "Restored original scene" }, onReturn = {}) {
                    androidx.compose.material3.Text(it)
                }
            }
        }
        compose.onNodeWithText("学习内容读取失败").assertIsDisplayed()
        compose.onNodeWithText("讲解准备失败").assertDoesNotExist()
        compose.onNodeWithText("重试").assertIsDisplayed().performClick()
        compose.onNodeWithText("Restored original scene").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, calls) }
    }

    @Test fun leavingFailedReviewContentUsesTheExistingReturnAction() {
        var returned = false
        compose.setContent {
            RlTheme {
                LearningContentLoader<String>("review:missing-pattern", load = { error("Isolated missing local pattern") }, onReturn = { returned = true }) {
                    org.junit.Assert.fail("No content may open")
                }
            }
        }
        compose.onNodeWithText("返回章节").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(returned) }
    }

    @Test fun failedResumeStaysOnTheSameRequestAndCanRetryWithoutLosingItsPointer() {
        var calls = 0
        var returned = false
        var ready: HomeDiaryRoute? = null
        compose.setContent {
            RlTheme {
                LearningRouteLoader(HomeDiaryRoute.ResumeLearning("c1", "LR-05", "flow:s2"),
                    load = { calls++; if (calls == 1) null else HomeDiaryRoute.ChapterLearning("c1") },
                    onReady = { ready = it }, onReturn = { returned = true })
            }
        }
        compose.onNodeWithText("重试").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, calls); assertFalse(returned); assertEquals(HomeDiaryRoute.ChapterLearning("c1"), ready) }
    }

    @Test fun readExceptionProvidesARetryInsteadOfCrashingOrLeavingAnEndlessSpinner() {
        compose.setContent {
            RlTheme {
                LearningRouteLoader(HomeDiaryRoute.ResumeLearning("c1", "LR-05", "flow:s2"),
                    load = { error("isolated read failure") }, onReady = { fail("No result was loaded") }, onReturn = { })
            }
        }
        compose.onNodeWithText("重试").assertIsDisplayed()
        compose.onNodeWithText("返回章节").assertIsDisplayed()
    }
}
