package com.reallanguage.app.feature.home.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.asAndroidBitmap
import com.reallanguage.app.core.designsystem.RlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry

class HomeScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun useProductionLandscapeOrientation() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        composeRule.waitUntil(5_000) {
            composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    @Test
    fun baselineUsesOriginalMainAndTaskColumnProportions() {
        composeRule.setContent {
            RlTheme { Box(Modifier.size(866.5.dp, 453.5.dp)) {
                HomeScreen(HomeUiState(hasDueReview = true, hasIncompleteLearning = true), {})
            } }
        }
        val main = composeRule.onNodeWithTag("home-diary-card").fetchSemanticsNode().boundsInRoot
        val scene = composeRule.onNodeWithTag("home-scene-card").fetchSemanticsNode().boundsInRoot
        val task = composeRule.onNodeWithTag("home-review-card").fetchSemanticsNode().boundsInRoot
        val incomplete = composeRule.onNodeWithTag("home-incomplete-card").fetchSemanticsNode().boundsInRoot
        assertEquals("Original main card is about 1110px; task paper about 477px", 2.32f, main.width / task.width, 0.03f)
        assertEquals(main.top, task.top, 3f)
        assertEquals("The diary card is slightly taller than the first task card", 1.048f, main.height / task.height, 0.025f)
        assertEquals("The scene card is slightly shorter than the second task card", .952f, scene.height / incomplete.height, 0.025f)
    }

    @Test
    fun brandWordmarkUsesTheReferenceWidthAtTheBaselineViewport() {
        composeRule.setContent {
            RlTheme {
                Box(Modifier.size(866.5.dp, 453.5.dp)) {
                    HomeScreen(HomeUiState(), {})
                }
            }
        }

        val density = composeRule.activity.resources.displayMetrics.density
        val wordmark = composeRule.onNodeWithText("RealLanguage").fetchSemanticsNode().boundsInRoot
        val widthDp = wordmark.width / density
        assertTrue("Wordmark width does not match the 07-home reference: $widthDp dp", widthDp in 218f..234f)
    }

    @Test
    fun mainAndTaskCardsUseTheReferenceStandaloneDecorations() {
        composeRule.setContent {
            RlTheme {
                Box(Modifier.size(866.5.dp, 453.5.dp)) {
                    HomeScreen(HomeUiState(), {})
                }
            }
        }

        listOf(
            "home-diary-scribble",
            "home-diary-underline",
            "home-scene-paperclip",
            "home-scene-underline",
            "home-review-pin",
            "home-incomplete-green-tape",
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag, useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun shortLandscapeKeepsTaskLabelsAndActionsVisibleWithLargeFont() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                RlTheme { Box(Modifier.size(760.dp, 300.dp).consumeWindowInsets(WindowInsets.safeDrawing).testTag("short-home-viewport")) {
                    HomeScreen(HomeUiState(hasDueReview = true, hasIncompleteLearning = true), {})
                } }
            }
        }
        val viewportBounds = composeRule.onNodeWithTag("short-home-viewport").fetchSemanticsNode().boundsInRoot
        val density = composeRule.activity.resources.displayMetrics.density
        assertEquals("The test must actually render the requested landscape width", 760f * density, viewportBounds.width, 1f)
        val taskBounds = composeRule.onNodeWithTag("home-review-card").fetchSemanticsNode().boundsInRoot
        listOf("统一回顾已到期", "继续未完成学习", "开始回顾", "继续", "快速录音",
            "RealLanguage", "消息", "设置", "账号", "写日记", "AI 设定场景",
            "把今天真实发生的事变成英语", "练习你真正会遇到的对话", "词汇本", "句式本").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
            composeRule.onNodeWithText(it).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayout ->
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(getLayout(layouts))
                assertTrue("$it must show all text, not only a visible fragment; viewport=$viewportBounds task=$taskBounds: " + layouts.joinToString { layout ->
                    "size=${layout.size}, paragraphWidth=${layout.multiParagraph.width}, lineRight=${layout.getLineRight(0)}, lines=${layout.lineCount}, font=${layout.layoutInput.style.fontSize}, widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}, constraints=${layout.layoutInput.constraints}"
                }, layouts.all { layout ->
                    // The paragraph can retain the available width while Text wraps
                    // its measured width to its glyphs. Compare actual line bounds,
                    // not didOverflowWidth (paragraph width > measured width).
                    !layout.didOverflowHeight && (0 until layout.lineCount).all { line ->
                        !layout.isLineEllipsized(line) && layout.getLineLeft(line) >= -0.5f &&
                            layout.getLineRight(line) <= layout.size.width + 0.5f
                    }
                })
            }
        }
        val actions = listOf("开始回顾" to "home-review-card", "继续" to "home-incomplete-card")
        actions.forEach { (label, tag) ->
            val text = composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            val card = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$label must be inside its paper", text.top >= card.top && text.bottom <= card.bottom)
        }
    }

    @Test
    fun landscapeHomeKeepsAllEntriesInsideAvailableHeight() {
        composeRule.setContent {
            RlTheme {
                Box(Modifier.size(850.dp, 350.dp).testTag("home-viewport")) {
                    HomeScreen(HomeUiState(hasDueReview = true, hasIncompleteLearning = true), {})
                }
            }
        }
        val viewport = composeRule.onNodeWithTag("home-viewport").fetchSemanticsNode().boundsInRoot
        listOf("home-diary-card", "home-scene-card", "home-review-card", "home-incomplete-card", "home-quick-recording").forEach {
            val bounds = composeRule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot
            assertTrue("$it extends below the viewport", bounds.bottom <= viewport.bottom)
            composeRule.onNodeWithTag(it).assertIsDisplayed()
        }
    }

    @Test
    fun widePhoneKeepsAdaptiveTaskActionsInsideAndInTheLowerHalf() {
        composeRule.setContent {
            RlTheme {
                Box(Modifier.size(936.dp, 432.dp).testTag("wide-phone-home-viewport")) {
                    HomeScreen(
                        HomeUiState(hasDueReview = true, hasIncompleteLearning = true),
                        {},
                    )
                }
            }
        }

        listOf(
            Triple("home-review-card", "home-review-action", "home-review-paper"),
            Triple("home-incomplete-card", "home-incomplete-action", "home-incomplete-paper"),
        ).forEach { (cardTag, actionTag, paperTag) ->
            val card = composeRule.onNodeWithTag(cardTag).fetchSemanticsNode().boundsInRoot
            val action = composeRule.onNodeWithTag(actionTag, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val paper = composeRule.onNodeWithTag(paperTag, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue("$actionTag must stay inside $cardTag", action.top >= card.top && action.bottom <= card.bottom)
            assertTrue("$actionTag must use the lower half of $cardTag", action.center.y > card.center.y)
            assertTrue(
                "$actionTag must retain adaptive paper space below it",
                card.bottom - action.bottom >= card.height * .04f,
            )
            assertTrue("$paperTag must move down from the card top", paper.top > card.top)
            assertTrue("$paperTag must still cover the card bottom", paper.bottom >= card.bottom)
        }

        val outputDirectory = requireNotNull(composeRule.activity.getExternalFilesDir("visual-qa"))
        val output = File(outputDirectory, "07-home-wide-phone-current.png")
        output.outputStream().use { stream ->
            composeRule.onNodeWithTag("wide-phone-home-viewport").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        }
        val copy = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "cp ${output.absolutePath} /sdcard/Download/07-home-wide-phone-current.png",
        )
        ParcelFileDescriptor.AutoCloseInputStream(copy).use { it.readBytes() }
        assertTrue("Wide-phone visual QA screenshot was not written", output.isFile && output.length() > 0)
    }

    @Test
    fun widePhoneAlignsFeatureArrowsAndKeepsRecorderTapeSeparateFromSceneCard() {
        composeRule.setContent {
            RlTheme {
                Box(Modifier.size(936.dp, 432.dp).testTag("wide-home-alignment-viewport")) {
                    HomeScreen(HomeUiState(hasDueReview = true, hasIncompleteLearning = true), {})
                }
            }
        }

        val diary = composeRule.onNodeWithTag("home-diary-card").fetchSemanticsNode().boundsInRoot
        val scene = composeRule.onNodeWithTag("home-scene-card").fetchSemanticsNode().boundsInRoot
        val diaryArrow = composeRule.onNodeWithTag("home-diary-arrow", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val sceneArrow = composeRule.onNodeWithTag("home-scene-arrow", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val diaryPosition = (diaryArrow.center.y - diary.top) / diary.height
        val scenePosition = (sceneArrow.center.y - scene.top) / scene.height
        assertEquals("Both entry arrows must share one responsive vertical anchor", diaryPosition, scenePosition, .025f)
        assertTrue("Entry arrows belong in the lower-middle of both cards", diaryPosition in .63f.. .72f)

        val tape = composeRule.onNodeWithTag("home-recorder-tape", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Recorder tape must not touch or visually join the AI scene paper: tape=$tape scene=$scene",
            tape.top > scene.bottom)
    }

    @Test
    fun keepsReviewAndIncompleteLearningAsPermanentHomeModulesWithoutFinancialDetails() {
        val actions = mutableListOf<HomeAction>()
        composeRule.setContent {
            RlTheme {
                HomeScreen(state = HomeUiState(), onAction = actions::add)
            }
        }

        listOf(
            "消息",
            "设置",
            "账号",
            "写日记",
            "AI 设定场景",
        ).forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        composeRule.onNodeWithText("把今天真实发生的事变成英语").assertIsDisplayed()
        composeRule.onNodeWithText("练习你真正会遇到的对话").assertIsDisplayed()
        composeRule.onNodeWithTag("home-diary-card").assertIsDisplayed()
        composeRule.onNodeWithTag("home-scene-card").assertIsDisplayed()
        composeRule.onNodeWithTag("home-quick-recording").assertIsDisplayed()
        listOf("快速录音", "词汇本", "句式本").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
        composeRule.onNodeWithText("统一回顾已到期").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("继续未完成学习").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("home-review-card").assertIsDisplayed()
        composeRule.onNodeWithTag("home-incomplete-card").assertIsDisplayed()
        assertEquals(
            listOf(HomeAction.DueReviewRequested, HomeAction.IncompleteLearningRequested),
            actions,
        )
        composeRule.onNodeWithText("人民币余额").assertDoesNotExist()
        composeRule.onNodeWithText("Token").assertDoesNotExist()
        composeRule.onNodeWithText("模型价格").assertDoesNotExist()
    }

    @Test
    fun showsRealTaskEntriesIndependentlyAndDispatchesTheirOwnActions() {
        val actions = mutableListOf<HomeAction>()
        composeRule.setContent {
            RlTheme {
                HomeScreen(
                    state = HomeUiState(
                        hasDueReview = true,
                        hasIncompleteLearning = true,
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText("统一回顾已到期").performClick()
        composeRule.onNodeWithText("继续未完成学习").performClick()
        composeRule.onNodeWithTag("home-review-card").assertIsDisplayed()
        composeRule.onNodeWithTag("home-incomplete-card").assertIsDisplayed()

        assertEquals(
            listOf(HomeAction.DueReviewRequested, HomeAction.IncompleteLearningRequested),
            actions,
        )
    }

    @Test
    fun footerUsesTheReferenceBooksCleanRecorderCapsuleAndVisibleWaveform() {
        val actions = mutableListOf<HomeAction>()
        composeRule.setContent {
            RlTheme {
                Box(
                    Modifier.size(866.5.dp, 453.5.dp)
                        .consumeWindowInsets(WindowInsets.safeDrawing)
                        .testTag("home-footer-reference-viewport"),
                ) {
                    HomeScreen(HomeUiState(), actions::add)
                }
            }
        }

        listOf(
            "home-vocabulary-art",
            "home-vocabulary-rule",
            "home-footer-divider-left",
            "home-recorder-surface",
            "home-recorder-tape",
            "home-recorder-wave-window",
            "home-recorder-wave-source",
            "home-footer-divider-right",
            "home-pattern-art",
            "home-pattern-rule",
        ).forEach { composeRule.onNodeWithTag(it, useUnmergedTree = true).assertExists() }

        val viewport = composeRule.onNodeWithTag("home-footer-reference-viewport")
            .fetchSemanticsNode().boundsInRoot
        listOf(
            "home-vocabulary-art",
            "home-vocabulary-rule",
            "home-footer-divider-left",
            "home-recorder-surface",
            "home-recorder-tape",
            "home-recorder-wave-window",
            "home-recorder-wave-source",
            "home-footer-divider-right",
            "home-pattern-art",
            "home-pattern-rule",
        ).forEach { tag ->
            val layer = composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag must intersect the viewport", layer.right > viewport.left && layer.left < viewport.right)
            assertTrue("$tag must intersect the viewport", layer.bottom > viewport.top && layer.top < viewport.bottom)
        }

        val vocabularyArt = composeRule.onNodeWithTag("home-vocabulary-art", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val patternArt = composeRule.onNodeWithTag("home-pattern-art", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val density = composeRule.activity.resources.displayMetrics.density
        assertTrue("Vocabulary art must retain the illustrated-book scale", vocabularyArt.width >= 38.dp.value * density)
        assertTrue("Pattern art must retain the illustrated-book scale", patternArt.height >= 36.dp.value * density)

        val recorder = composeRule.onNodeWithTag("home-quick-recording").fetchSemanticsNode().boundsInRoot
        listOf("home-recorder-surface", "home-recorder-wave-window").forEach { tag ->
            val layer = composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag must stay within the recorder: layer=$layer recorder=$recorder",
                layer.left >= recorder.left && layer.right <= recorder.right)
            assertTrue("$tag must stay within the recorder: layer=$layer recorder=$recorder",
                layer.top >= recorder.top && layer.bottom <= recorder.bottom)
        }
        val waveformWindow = composeRule.onNodeWithTag("home-recorder-wave-window", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val recorderTape = composeRule.onNodeWithTag("home-recorder-tape", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "The recording tape must retain the wide overlapping shape from 07-home",
            recorderTape.width >= 42.dp.value * density,
        )
        val recorderSurface = composeRule.onNodeWithTag("home-recorder-surface", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("The tape must overlap the capsule from above", recorderTape.top < recorderSurface.top)
        assertTrue("The tape must remain visibly attached to the capsule", recorderTape.bottom > recorderSurface.top)
        assertTrue(
            "The visible waveform must occupy a readable footer area",
            waveformWindow.width >= 82.dp.value * density && waveformWindow.height >= 24.dp.value * density,
        )

        composeRule.onNodeWithText("词汇本").performClick()
        composeRule.onNodeWithTag("home-quick-recording").performClick()
        composeRule.onNodeWithText("句式本").performClick()
        assertEquals(
            listOf(
                HomeAction.VocabularyRequested,
                HomeAction.QuickRecordingRequested,
                HomeAction.SentencePatternsRequested,
            ),
            actions,
        )
    }

    @Test
    fun capturesBaselineHomeForVisualComparison() {
        composeRule.setContent {
            RlTheme {
                Box(
                    Modifier.size(866.5.dp, 453.5.dp)
                        .consumeWindowInsets(WindowInsets.safeDrawing)
                        .testTag("home-visual-baseline"),
                ) {
                    HomeScreen(
                        state = HomeUiState(hasDueReview = true, hasIncompleteLearning = true),
                        onAction = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val outputDirectory = requireNotNull(composeRule.activity.getExternalFilesDir("visual-qa"))
        val output = File(outputDirectory, "07-home-current.png")
        output.outputStream().use { stream ->
            composeRule.onNodeWithTag("home-visual-baseline").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        }
        val copy = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "cp ${output.absolutePath} /sdcard/Download/07-home-current.png",
        )
        ParcelFileDescriptor.AutoCloseInputStream(copy).use { it.readBytes() }
        assertTrue("Visual QA screenshot was not written", output.isFile && output.length() > 0)
    }
}
