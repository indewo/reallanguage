package com.reallanguage.app.feature.knowledge.presentation

import androidx.compose.ui.test.assertIsDisplayed
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.VocabularyEntry
import com.reallanguage.app.feature.knowledge.domain.VocabularyExample
import com.reallanguage.app.feature.knowledge.domain.VocabularySenseRecord
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals

class VocabularyDetailScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before fun useProductionLandscapeWindow() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
    }

    @Test
    fun expandedMeaningShowsItsRealExamplesStatisticsAndActions() {
        val actions = mutableListOf<VocabularyBookAction>()
        val detail = VocabularySenseRecord(
            senseId = "run-1",
            englishForm = "run",
            meaningZh = "经营业务",
            status = KnowledgeStatus.MASTERED,
            effectiveLearnedCount = 2,
            learningScopes = setOf("六级", "商务英语"),
            enteredOrder = 1,
            phonetic = "/rʌn/",
            pronunciationText = "run",
            representativeScene = "经营一家社区咖啡店",
            examples = listOf(
                VocabularyExample("She runs a small café.", "她经营一家小咖啡店。", "example-1"),
                VocabularyExample("They run the shop together.", "他们一起经营这家店。", "example-2"),
            ),
            focusOccurrenceCount = 2,
            contextOccurrenceCount = 3,
        )
        val state = VocabularyBookUiState(
            currentScope = "六级 + 商务英语",
            entries = listOf(VocabularyEntry("run", listOf(detail))),
            expandedSenseId = "run-1",
            loading = false,
        )

        composeRule.setContent { RlTheme { VocabularyBookScreen(state, actions::add) } }

        composeRule.onNodeWithTag("vocabulary-detail-sheet").assertIsDisplayed()
        composeRule.onNodeWithText("重点例句").assertIsDisplayed()
        composeRule.onNodeWithText("She runs a small café.").assertIsDisplayed()
        composeRule.onNodeWithText("重点句出现 2 次").assertIsDisplayed()
        composeRule.onNodeWithText("非重点句出现 3 次").assertIsDisplayed()
        composeRule.onNodeWithTag("vocabulary-group-pronunciation-run-1").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("vocabulary-detail-collapse").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(
            listOf(
                VocabularyBookAction.SensePronunciationRequested("run-1"),
                VocabularyBookAction.CollapseRequested,
            ),
            actions,
        )
    }
}
