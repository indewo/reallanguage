package com.reallanguage.app.development

import android.content.pm.ActivityInfo
import android.util.Log
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.diary.presentation.DiaryDictationState
import com.reallanguage.app.feature.diary.presentation.DiaryInputUiState
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class DevelopmentPreviewScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** A controlled visual fixture, not evidence of saved user content or AI/audio. */
    @Test
    fun captureReferenceSizedLearningPreviewWithoutKeyboard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val destination = InstrumentationRegistry.getArguments().getString("destination")
        val targetCardScroll = InstrumentationRegistry.getArguments().getString("targetCardScroll")
        val diaryState = InstrumentationRegistry.getArguments().getString("diaryState")
        assumeTrue("This capture is explicitly invoked with a destination and matching device size", destination != null)
        require(targetCardScroll == null || (destination == "LEARNING_TARGETS" && targetCardScroll == "pattern-related")) {
            "Only the authorized target-card detail scroll can change the initial preview state"
        }
        val expectedSize = when (destination) {
            "AUDIO_DICTATION" -> 1690 to 931
            "COMPLETE_WRITING" -> 1774 to 887
            "LEARNING_TARGETS" -> 1846 to 852
            "DIARY_INPUT" -> 2340 to 1080
            else -> error("Only authorized learning reference fixtures can be captured")
        }
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        composeRule.setContent {
            RlTheme {
                DevelopmentPreviewScreen(
                    initialDestinationName = destination,
                    diaryInputState = if (destination == "DIARY_INPUT" && diaryState == "recording") {
                        DiaryInputUiState(
                            dictationState = DiaryDictationState.RECORDING,
                            dictationElapsedSeconds = 3,
                        )
                    } else null,
                )
            }
        }
        val title = when (destination) {
            "AUDIO_DICTATION" -> "第 1 章 · 音频听写"
            "COMPLETE_WRITING" -> "第 1 章 · 完整书写"
            "DIARY_INPUT" -> "写日记"
            else -> "选择学习目标"
        }
        composeRule.onNodeWithText(title).assertIsDisplayed()
        composeRule.onNodeWithText("返回建设目录").assertDoesNotExist()
        if (destination == "AUDIO_DICTATION") {
            composeRule.onNodeWithText("But in the end, I worked up the courage to explain...").assertIsDisplayed()
        } else if (destination == "COMPLETE_WRITING") {
            composeRule.onNodeWithTag("complete-writing-input-s1")
                .assertTextContains("I was a little nervous during today’s meeting.")
            composeRule.onNodeWithTag("complete-writing-input-s2")
                .assertTextContains("But in the end, I worked up the courage...")
        } else if (destination == "LEARNING_TARGETS") {
            // These exact fixture fields must exist, but some need local scrolling.
            // The default screenshot stays at its true initial state; existence
            // must not be misreported as all three examples fitting simultaneously.
            composeRule.onNodeWithText("It took him a while to work up the courage to apologize.").assertExists()
            composeRule.onNodeWithText("她用简单的方式解释了规则。").assertExists()
            composeRule.onNodeWithText("He worked up the courage to apply for the scholarship.").assertExists()
            if (targetCardScroll == "pattern-related") {
                composeRule.onNodeWithTag("learning_target_t3").performScrollTo().assertIsDisplayed()
                val related = composeRule.onNodeWithText("相关生词：courage", useUnmergedTree = true)
                related.performScrollTo().assertIsDisplayed()
                val results = mutableListOf<TextLayoutResult>()
                related.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
                val layout = results.single()
                val coordinates = related.fetchSemanticsNode().layoutInfo.coordinates
                val origin = coordinates.localToRoot(Offset.Zero)
                val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
                assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
                assertTrue("The scrolled detail must be fully readable, not a clipped sliver",
                    origin.y + layout.getLineTop(0) >= clip.top - 1f &&
                        origin.y + layout.getLineBottom(layout.lineCount - 1) <= clip.bottom + 1f)
            }
            composeRule.onNodeWithText("继续").assertIsDisplayed()
        } else if (diaryState == "recording") {
            composeRule.onNodeWithText("正在听写").assertIsDisplayed()
            composeRule.onNodeWithText("松开识别").assertIsDisplayed()
        } else {
            composeRule.onNodeWithText("在此写下你想表达的内容").assertIsDisplayed()
            composeRule.onNodeWithText("按住听写").assertIsDisplayed()
        }
        val view = composeRule.activity.window.decorView
        composeRule.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == false
        }
        assertEquals("The capture caller must set the reference density", 320, composeRule.activity.resources.displayMetrics.densityDpi)
        assertEquals("The reference font scale must be explicit", 1f, composeRule.activity.resources.configuration.fontScale, .001f)
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals("Do not normalize an incorrectly sized window afterwards", expectedSize.first.toFloat(), root.width, 1f)
        assertEquals("Do not crop system bars out afterwards", expectedSize.second.toFloat(), root.height, 1f)

        val drawn = AtomicBoolean()
        val listener = ViewTreeObserver.OnDrawListener { drawn.set(true) }
        instrumentation.runOnMainSync {
            view.viewTreeObserver.addOnDrawListener(listener)
            view.postInvalidateOnAnimation()
        }
        try { composeRule.waitUntil(3_000) { drawn.get() } }
        finally { instrumentation.runOnMainSync {
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener)
        } }
        runCatching { instrumentation.uiAutomation.waitForIdle(150, 3_000) }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val stateSuffix = (targetCardScroll ?: diaryState)?.let { "-$it" }.orEmpty()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null),
            "visual-qa/$destination-reference-${expectedSize.first}x${expectedSize.second}$stateSuffix-${UUID.randomUUID()}.png")
        try {
            assertEquals(expectedSize.first, bitmap.width)
            assertEquals(expectedSize.second, bitmap.height)
            check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
            output.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        Log.i("RealLanguagePreview", "REFERENCE_CAPTURE destination=$destination state=${targetCardScroll ?: diaryState ?: "initial"} pixels=${expectedSize.first}x${expectedSize.second} density=320 fontScale=1 ime=false fixtureOnly=true file=${output.absolutePath}")
    }

    @Test
    fun catalogExposesEveryImplementedProductScreen() {
        composeRule.setContent { RlTheme { DevelopmentPreviewScreen() } }

        listOf(
            "登录",
            "注册",
            "年龄不符合",
            "邮箱验证",
            "设置第 1 步",
            "设置第 2 步",
            "设置第 3 步",
            "首页",
            "日记输入",
            "日记处理中",
            "日记处理失败",
            "安全检查",
            "内容安全处理",
            "人工复核处理中",
            "人工复核结果（通过）",
            "人工复核结果（未通过）",
            "全量拆句与重点句选择",
            "确认英语表达",
            "确认学习目标",
            "确认并固定",
            "当前章节学习框架",
            "基础讲解与三轮朗读",
            "读＋说",
            "手写抄写",
            "音频听写",
        ).forEach {
            composeRule.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun homePreviewShowsRealEntriesAndCanReturnToCatalog() {
        composeRule.setContent { RlTheme { DevelopmentPreviewScreen() } }

        composeRule.onNodeWithText("首页").performClick()
        composeRule.onNodeWithText("写日记").assertIsDisplayed()
        composeRule.onNodeWithText("AI 设定场景").assertIsDisplayed()
        composeRule.onNodeWithText("返回建设目录").performClick()
        composeRule.onNodeWithText("RealLanguage 建设预览").assertIsDisplayed()
    }
}
