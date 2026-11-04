package com.reallanguage.app.feature.review.presentation

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.review.domain.ReviewAbility
import com.reallanguage.app.feature.review.domain.ReviewAbilityModule
import com.reallanguage.app.feature.review.domain.ReviewAbilityResultDraft
import com.reallanguage.app.feature.review.domain.ReviewAbilityStatus
import com.reallanguage.app.feature.review.domain.ReviewChapterSnapshot
import com.reallanguage.app.feature.review.domain.ReviewContextSentence
import com.reallanguage.app.feature.review.domain.ReviewSourceType
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReviewChapterMetadataScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun uuidSuffixWithoutSavedMetadataIsNotAChapterNumberOrFocusPosition() {
        val chapter = snapshot().copy(originalChapterNumber = null, originalSentences = null)
        show(chapter)
        compose.onNodeWithText("统一回顾 · 第 620147 章").assertDoesNotExist()
        compose.onNodeWithText("重点句 1 / 3").assertDoesNotExist()
        compose.onNodeWithText("当前回顾章节").assertExists()
    }

    @Test fun savedChapterOrderAndFocusRolesExcludeContextFromTheCounter() {
        val chapter = snapshot().copy(
            abilities = listOf(ability("focus-two")),
            originalSentences = sentences.map { it.copy(isFocus = it.sentenceId == "focus-two") },
        )
        show(chapter)
        compose.onNodeWithText("统一回顾 · 第 2 章").assertExists()
        compose.onNodeWithText("重点句 1 / 1").assertExists()
        compose.onNodeWithText("重点句 3 / 3").assertDoesNotExist()
    }

    @Test fun duplicateTextHighlightsOnlyTheCurrentFixedSentenceId() {
        show(snapshot().copy(abilities = listOf(ability("focus-two"))))
        if (InstrumentationRegistry.getArguments().getString("reviewMetadataCapture") == "content-fit-focused-after") {
            compose.onNodeWithTag("review-context-focus-two").performScrollTo()
        }
        captureIfRequested()
        compose.onNodeWithTag("review-context-context").assertIsNotSelected()
        compose.onNodeWithTag("review-context-focus-one").assertIsNotSelected()
        compose.onNodeWithTag("review-context-focus-two").assertIsSelected()
        compose.onNodeWithText("重点句 2 / 2").assertExists()
    }

    @Test fun completingOneAbilityMovesHighlightAndOrdinalToTheNextSentenceId() {
        val state = mutableStateOf(state(snapshot()))
        compose.setContent { RlTheme { ReviewChapterScreen(state.value) {} } }
        compose.onNodeWithTag("review-context-focus-one").assertIsSelected()
        compose.onNodeWithText("重点句 1 / 2").assertExists()
        compose.runOnIdle {
            state.value = state.value.copy(resultDrafts = listOf(ReviewAbilityResultDraft("ability-focus-one", "local-evidence")))
        }
        compose.onNodeWithTag("review-context-focus-one").assertIsNotSelected()
        compose.onNodeWithTag("review-context-focus-two").assertIsSelected()
        compose.onNodeWithText("重点句 2 / 2").assertExists()
    }

    @Test fun missingCurrentIdentityCannotBorrowAnotherSentencesPosition() {
        show(snapshot().copy(abilities = listOf(ability("not-in-this-chapter"))))
        compose.onNodeWithText("重点句 1 / 3").assertDoesNotExist()
        compose.onNodeWithText("重点句 1 / 2").assertDoesNotExist()
        compose.onNodeWithTag("review-context-focus-one").assertIsNotSelected()
        compose.onNodeWithTag("review-context-focus-two").assertIsNotSelected()
    }

    private fun show(chapter: ReviewChapterSnapshot) {
        if (InstrumentationRegistry.getArguments().containsKey("reviewMetadataCapture")) {
            compose.activityRule.scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                configureFocusedLearningWindow(it.window)
            }
        }
        compose.setContent {
            RlTheme {
                RlPaperBackground(Modifier.testTag("review-metadata-capture")) {
                    ReviewChapterScreen(state(chapter)) {}
                }
            }
        }
    }

    /** Same controlled source state before/after; not proof of production data or pixel parity. */
    private fun captureIfRequested() {
        val phase = InstrumentationRegistry.getArguments().getString("reviewMetadataCapture") ?: return
        require(phase in setOf("before", "after", "content-fit-after", "content-fit-focused-after"))
        compose.waitForIdle()
        val metrics = compose.activity.resources.displayMetrics
        check(metrics.densityDpi == 320 && compose.activity.resources.configuration.fontScale == 1f)
        val bitmap = compose.onNodeWithTag("review-metadata-capture").captureToImage().asAndroidBitmap()
        try {
            check(bitmap.width == 1829 && bitmap.height == 860) { "Expected unscaled 1829x860 capture; got ${bitmap.width}x${bitmap.height}" }
            val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "visual-qa")
            check(directory.isDirectory || directory.mkdirs())
            val filename = when (phase) {
                "content-fit-after" -> "37-review-content-fit-after.png"
                "content-fit-focused-after" -> "37-review-content-fit-focused-after.png"
                else -> "37-review-metadata-$phase.png"
            }
            File(directory, filename).outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun state(chapter: ReviewChapterSnapshot) = ReviewChapterUiState(
        reviewChapterId = "review", commandId = "command", chapter = chapter, loading = false,
    )

    private fun snapshot() = ReviewChapterSnapshot(
        reviewChapterId = "review",
        originalChapterId = "d44f77d6-6abc-413c-9ded-c1b95a620147",
        sourceType = ReviewSourceType.DIARY,
        sourceTitle = "已保存的日记",
        originalScene = sentences.map { it.textEn },
        focusSentence = "The same words.",
        abilities = listOf(ability("focus-one"), ability("focus-two")),
        originalChapterNumber = 2,
        originalSentences = sentences,
    )

    private fun ability(sentenceId: String) = ReviewAbility(
        "ability-$sentenceId", ReviewAbilityModule.FULL_SENTENCE_SPEAKING,
        ReviewAbilityStatus.LEARNING, sentenceId = sentenceId,
    )

    private val sentences = listOf(
        ReviewContextSentence("context", "The same words.", "非重点上下文", false, 0),
        ReviewContextSentence("focus-one", "The same words.", "第一重点句", true, 1),
        ReviewContextSentence("focus-two", "The same words.", "第二重点句", true, 2),
    )
}
