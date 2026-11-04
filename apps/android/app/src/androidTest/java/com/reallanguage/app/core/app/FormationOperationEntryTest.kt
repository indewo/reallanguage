package com.reallanguage.app.core.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.formation.presentation.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class FormationOperationEntryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Before fun landscape() {
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(5_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test fun targetLoadingUsesTheLatestCallbackWhenTheConfirmedPageRecomposes() {
        val revision = mutableStateOf(0)
        val response = CompletableDeferred<LearningTargetExtractionResult>()
        val callbacks = mutableListOf<Int>()
        var calls = 0
        val route = HomeDiaryRoute.LearningTargets(text, units, english, mapOf("u1" to "option"), sourceEntryId = "callback-source")
        rule.setContent { RlTheme {
            val captured = revision.value
            LearningTargetLoadingEntry(route, { factory {
                LearningTargetLoadingViewModel(object : LearningTargetExtractionRepository {
                    override suspend fun extract(): LearningTargetExtractionResult { calls++; return response.await() }
                    override suspend fun generate(unitId: String, expressionId: String) = LearningTargetGenerationResult.Failed
                })
            } }, { _, _ -> callbacks += captured }, {})
        } }
        rule.waitUntil(5_000) { calls == 1 }
        rule.runOnIdle { revision.value = 1 }; rule.waitForIdle()
        rule.runOnIdle { response.complete(LearningTargetExtractionResult.Extracted(emptyList(), emptyList())) }
        rule.waitUntil(5_000) { callbacks.isNotEmpty() }
        assertEquals(listOf(1), callbacks)
        assertEquals(1, calls)
    }

    @Test fun chapterFormationSameSelectionReentryDeliversTheCachedOperationAgain() {
        val visible = mutableStateOf(true)
        val route = HomeDiaryRoute.ChapterFormation(text, units, listOf("u1"), sourceEntryId = "source-1")
        val commands = mutableListOf<String>()
        val results = mutableListOf<List<ChapterDraft>>()
        rule.setContent { RlTheme {
            if (visible.value) ChapterFormationEntry(route, { command -> factory {
                ChapterFormationViewModel(units, route.selectedUnitIds, ChapterFormationRepository { _, _ ->
                    commands += command; ChapterFormationResult.Formed(contexts)
                })
            } }, results::add, {})
        } }
        rule.waitUntil(5_000) { results.size == 1 }
        reenter(visible)
        assertEquals(2, commands.size)
        assertEquals(commands[0], commands[1])
        assertEquals(listOf(contexts, contexts), results)
    }

    @Test fun chapterFormationUsesOnlyNeutralLoadingThenContinuesWithoutTheRemovedPage() {
        val response = CompletableDeferred<ChapterFormationResult>()
        var results = 0
        val route = HomeDiaryRoute.ChapterFormation(text, units, listOf("u1"), sourceEntryId = "hidden-stage")
        rule.setContent { RlTheme {
            ChapterFormationEntry(route, { factory {
                ChapterFormationViewModel(units, route.selectedUnitIds, ChapterFormationRepository { _, _ -> response.await() })
            } }, { results++ }, {})
        } }

        rule.waitForIdle()
        rule.onNodeWithText("正在形成学习章节").assertDoesNotExist()
        rule.onNodeWithText("章节没有形成成功").assertDoesNotExist()
        rule.runOnIdle { response.complete(ChapterFormationResult.Formed(contexts)) }
        rule.waitUntil(5_000) { results == 1 }
    }

    @Test fun chapterFormationFailureReturnsToSelectionWithoutShowingAStandaloneFailurePage() {
        var returns = 0
        val route = HomeDiaryRoute.ChapterFormation(text, units, listOf("u1"), sourceEntryId = "failed-hidden-stage")
        rule.setContent { RlTheme {
            ChapterFormationEntry(route, { factory {
                ChapterFormationViewModel(units, route.selectedUnitIds, ChapterFormationRepository { _, _ -> ChapterFormationResult.Failed })
            } }, {}, { returns++ })
        } }

        rule.waitUntil(5_000) { returns == 1 }
        rule.onNodeWithText("正在形成学习章节").assertDoesNotExist()
        rule.onNodeWithText("章节没有形成成功").assertDoesNotExist()
    }

    @Test fun englishGenerationReentryReusesFullInputCommandAndDeliversAgain() {
        val visible = mutableStateOf(true)
        val route = HomeDiaryRoute.EnglishGeneration(text, units, listOf("u1"), contexts, sourceEntryId = "source-1")
        val commands = mutableListOf<String>()
        val results = mutableListOf<List<EnglishChapterDraft>>()
        rule.setContent { RlTheme {
            if (visible.value) EnglishGenerationEntry(route, { command -> factory {
                EnglishGenerationViewModel(units, route.selectedUnitIds, contexts, EnglishGenerationRepository { _, _, _ ->
                    commands += command; EnglishGenerationResult.Generated(english)
                })
            } }, results::add, {})
        } }
        rule.waitUntil(5_000) { results.size == 1 }
        reenter(visible)
        assertEquals(2, commands.size)
        assertEquals(commands[0], commands[1])
        assertEquals(listOf(english, english), results)
    }

    @Test fun englishConfirmationSameChineseNewCurrentEnglishDoesNotShowSubmittedOldContents() {
        val visible = mutableStateOf(true)
        val route = mutableStateOf(HomeDiaryRoute.EnglishConfirmation(text, units, english, sourceEntryId = "source-1"))
        val results = mutableListOf<List<EnglishChapterDraft>>()
        rule.setContent { RlTheme {
            if (visible.value) EnglishConfirmationEntry(route.value,
                factory { EnglishConfirmationViewModel(route.value.chapters, route.value.initialSelectedCandidateIds) },
                { chapters, _ -> results += chapters }, {})
        } }
        rule.onNodeWithText("继续选择学习目标").assertIsEnabled().performClick()
        rule.waitUntil(5_000) { results.size == 1 }
        rule.runOnIdle { visible.value = false }; rule.waitForIdle()
        val changed = listOf(english.single().copy(sentences = listOf(english.single().sentences.single().copy(
            candidates = listOf(EnglishCandidate("new-option", "Current revised English.")),
        ))))
        rule.runOnIdle { route.value = route.value.copy(chapters = changed); visible.value = true }; rule.waitForIdle()
        rule.onNodeWithText("Current revised English.").assertIsDisplayed()
        rule.onNodeWithText("继续选择学习目标").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf(english, changed), results)
    }

    @Test fun targetLoadingReentryUsesTheSameCommandAndDoesNotWaitForAnAlreadyConsumedEffect() {
        val visible = mutableStateOf(true)
        val route = HomeDiaryRoute.LearningTargets(text, units, english, mapOf("u1" to "option"), sourceEntryId = "source-1")
        val commands = mutableListOf<String>()
        var results = 0
        rule.setContent { RlTheme {
            if (visible.value) LearningTargetLoadingEntry(route, { command -> factory {
                LearningTargetLoadingViewModel(object : LearningTargetExtractionRepository {
                    override suspend fun extract(): LearningTargetExtractionResult {
                        commands += command; return LearningTargetExtractionResult.Extracted(emptyList(), emptyList())
                    }
                    override suspend fun generate(unitId: String, expressionId: String): LearningTargetGenerationResult = LearningTargetGenerationResult.Failed
                })
            } }, { _, _ -> results++ }, {})
        } }
        rule.waitUntil(5_000) { results == 1 }
        reenter(visible)
        assertEquals(2, commands.size)
        assertEquals(commands[0], commands[1])
        assertEquals(2, results)
    }

    private fun reenter(visible: androidx.compose.runtime.MutableState<Boolean>) {
        rule.runOnIdle { visible.value = false }; rule.waitForIdle()
        rule.runOnIdle { visible.value = true }; rule.waitForIdle()
    }
    private val text = "同一份中文。"
    private val units = listOf(SemanticUnit("u1", 0, text))
    private val contexts = listOf(ChapterDraft("chapter", listOf("u1")))
    private val english = listOf(EnglishChapterDraft("chapter", listOf(EnglishSentenceDraft("u1", text, true, listOf(EnglishCandidate("option", "Previously confirmed English."))))))
    private fun factory(create: () -> ViewModel) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
}
