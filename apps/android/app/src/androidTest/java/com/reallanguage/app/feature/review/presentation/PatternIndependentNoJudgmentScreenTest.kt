package com.reallanguage.app.feature.review.presentation

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.feature.review.domain.PatternIndependentDecision
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class PatternIndependentNoJudgmentScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val pattern = LockedSpeakingPattern("pattern", "sentence", "used to + verb", "过去习惯")

    @Test fun inconclusiveFeedbackOffersRerecordingAndReturnButNeverCompletion() {
        val actions = mutableListOf<PatternIndependentAction>()
        show(PatternIndependentUiState(pattern, loading = false, decision = PatternIndependentDecision.INCONCLUSIVE,
            feedbackZh = "本次原音无法可靠判断，请重新回应。"), actions)
        captureIfRequested("inconclusive")
        compose.onNodeWithText("重试").assertIsDisplayed().performClick()
        compose.onNodeWithText("继续").assertDoesNotExist()
        compose.onNodeWithText("返回回顾章节").performScrollTo().performClick()
        assertEquals(listOf(PatternIndependentAction.RetryRequested, PatternIndependentAction.BackRequested), actions)
        compose.onNodeWithText(pattern.structure).assertDoesNotExist()
    }

    @Test fun unavailableNaturalSceneStillOffersTheExistingHandledItemContinuationWithoutAnswerOrAudio() {
        val actions = mutableListOf<PatternIndependentAction>()
        show(PatternIndependentUiState(pattern, loading = false, notNaturallyVerifiable = true), actions)
        captureIfRequested("unavailable")
        compose.onNodeWithText("继续").assertIsDisplayed().performClick()
        compose.onNodeWithText("开始回应").assertDoesNotExist()
        compose.onNodeWithText(pattern.structure).assertDoesNotExist()
        assertEquals(listOf(PatternIndependentAction.ContinueRequested), actions)
    }

    private fun show(state: PatternIndependentUiState, actions: MutableList<PatternIndependentAction>) {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.activityRule.scenario.onActivity { configureFocusedLearningWindow(it.window) }
        compose.setContent { RlTheme { PatternIndependentScreen(state) { actions += it } } }
        compose.waitForIdle()
    }

    /** Pure controlled exception-state evidence; never a claim of original artwork parity. */
    private fun captureIfRequested(state: String) {
        val phase = InstrumentationRegistry.getArguments().getString("patternNoJudgmentCapture") ?: return
        require(phase in setOf("before", "after"))
        val drawn = AtomicBoolean()
        val view = compose.activity.window.decorView
        lateinit var observer: ViewTreeObserver.OnDrawListener
        observer = ViewTreeObserver.OnDrawListener { drawn.set(true) }
        compose.activityRule.scenario.onActivity {
            view.viewTreeObserver.addOnDrawListener(observer)
            view.postInvalidateOnAnimation()
        }
        compose.waitUntil(10_000) { drawn.get() }
        compose.activityRule.scenario.onActivity { view.viewTreeObserver.removeOnDrawListener(observer) }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(200, 5_000)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "visual-qa").apply { mkdirs() }
        File(directory, "37-pattern-$state-$phase.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
