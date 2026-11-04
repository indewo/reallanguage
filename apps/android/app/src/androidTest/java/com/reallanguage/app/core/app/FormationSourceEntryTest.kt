package com.reallanguage.app.core.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.formation.domain.SemanticSplitRepository
import com.reallanguage.app.feature.formation.domain.SemanticSplitResult
import com.reallanguage.app.feature.formation.domain.SemanticUnit
import com.reallanguage.app.feature.formation.presentation.FocusSelectionViewModel
import com.reallanguage.app.feature.formation.presentation.SemanticSplitViewModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class FormationSourceEntryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Before fun landscape() {
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(5_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test fun semanticSplitHasNoFrontendAndLeavesThePreviousPageVisibleUntilReady() {
        val response = CompletableDeferred<SemanticSplitResult>()
        var delivered: List<SemanticUnit>? = null
        val route = HomeDiaryRoute.SemanticSplit("第一句。第二句。", entryId = "silent-source")
        rule.setContent { RlTheme {
            Text("上一页仍在显示")
            SilentSemanticSplitEntry(
                route,
                { source, _ -> factory { SemanticSplitViewModel(source, SemanticSplitRepository { response.await() }) } },
                { delivered = it },
                {},
            )
        } }
        rule.onNodeWithText("上一页仍在显示").assertIsDisplayed()
        listOf("整理学习内容", "正在拆分内容", "读取内容", "按语义分句", "检查完整性").forEach {
            rule.onNodeWithText(it).assertDoesNotExist()
        }
        rule.runOnIdle { response.complete(SemanticSplitResult.Ready(units)) }
        rule.waitUntil(5_000) { delivered == units }
    }

    @Test fun sameSplitRequestReentersWithStableCommandAndDeliversExistingUnitsAgain() {
        val visible = mutableStateOf(true)
        val route = HomeDiaryRoute.SemanticSplit("第一句。第二句。", entryId = "passed-source")
        val commands = mutableListOf<String>()
        val delivered = mutableListOf<List<SemanticUnit>>()
        rule.setContent { RlTheme {
            if (visible.value) SilentSemanticSplitEntry(route, { source, command -> factory {
                SemanticSplitViewModel(source, SemanticSplitRepository {
                    commands += command
                    SemanticSplitResult.Ready(units)
                })
            } }, delivered::add, {})
        } }
        rule.waitUntil(5_000) { delivered.size == 1 }
        rule.runOnIdle { visible.value = false }; rule.waitForIdle()
        rule.runOnIdle { visible.value = true }; rule.waitForIdle()
        assertEquals(2, commands.size)
        assertEquals(commands[0], commands[1])
        assertEquals(listOf(units, units), delivered)
    }

    @Test fun confirmedFocusReentryRestoresSelectionAndCanConfirmAgainInsteadOfRemainingLocked() {
        val visible = mutableStateOf(true)
        val route = mutableStateOf(HomeDiaryRoute.FocusSelection("原内容。", units))
        val delivered = mutableListOf<List<String>>()
        rule.setContent { RlTheme {
            if (visible.value) FocusSelectionEntry(route.value,
                factory { FocusSelectionViewModel(route.value.units, route.value.initialSelectedUnitIds) }, {}, delivered::add)
        } }
        rule.onNodeWithTag("focus_sentence_u2").performClick()
        rule.onNodeWithTag("focus_confirm").assertIsEnabled().performClick()
        rule.waitUntil(5_000) { delivered.size == 1 }
        rule.runOnIdle { visible.value = false }; rule.waitForIdle()
        rule.runOnIdle { route.value = route.value.copy(initialSelectedUnitIds = setOf("u2")); visible.value = true }; rule.waitForIdle()
        rule.onNodeWithTag("focus_confirm").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf(listOf("u2"), listOf("u2")), delivered)
    }

    private val units = listOf(SemanticUnit("u1", 0, "第一句。"), SemanticUnit("u2", 1, "第二句。"))
    private fun factory(create: () -> ViewModel) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
}
