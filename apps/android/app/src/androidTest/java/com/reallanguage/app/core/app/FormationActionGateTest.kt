package com.reallanguage.app.core.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.reallanguage.app.core.designsystem.RlTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FormationActionGateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val original = HomeDiaryRoute.SemanticSplit("已确认的原文。", entryId = "source")
    private val edited = original.copy(text = "用户确认修改后的完整原文。")

    @Test fun nextWaitsForTheLatestActualSelectionToBeSavedInsteadOfMerelyChangingThePage() {
        val saved = CompletableDeferred<Boolean>()
        val inputs = mutableListOf<HomeDiaryRoute>()
        val opened = mutableListOf<HomeDiaryRoute>()
        compose.setContent { RlTheme { FormationActionGate(original, { input, _, _ -> inputs += input; saved.await() }, opened::add) { _, actions ->
            Button(onClick = { actions.stateChanged(edited, 0); actions.navigate(HomeDiaryRoute.Home) }) { Text("继续测试") }
        } } }
        compose.onNodeWithText("继续测试").performClick()
        compose.waitForIdle(); assertTrue(opened.isEmpty())
        compose.onNodeWithText("继续测试").assertIsDisplayed()
        compose.onNodeWithText("正在保存学习进度…").assertDoesNotExist()
        compose.runOnIdle { saved.complete(true) }
        compose.waitUntil(5000) { opened.size == 1 }
        assertEquals(HomeDiaryRoute.Home, opened.single())
        assertTrue(inputs.isNotEmpty() && inputs.all { it == edited })
    }

    @Test fun failedSaveKeepsTheSamePageAndASecondUserActionRetriesTheSameInput() {
        var available = false
        val inputs = mutableListOf<HomeDiaryRoute>()
        val opened = mutableListOf<HomeDiaryRoute>()
        var childSubmits = 0
        compose.setContent { RlTheme { FormationActionGate(original, { input, _, _ -> inputs += input; available }, opened::add) { _, actions ->
            Button(onClick = { childSubmits++; actions.navigate(HomeDiaryRoute.Home) }) { Text("继续测试") }
        } } }
        compose.onNodeWithText("继续测试").performClick()
        compose.waitUntil(5_000) { inputs.size == 1 }
        compose.onNodeWithText("继续测试").assertIsDisplayed()
        compose.onNodeWithText("学习进度保存失败").assertDoesNotExist()
        assertTrue(opened.isEmpty()); assertEquals(1, inputs.size)
        compose.runOnIdle { available = true }
        compose.onNodeWithText("继续测试").performClick()
        compose.waitUntil(5000) { opened.size == 1 }
        assertEquals(listOf(original, original), inputs); assertEquals(2, childSubmits)
    }

    @Test fun failedSavePreservesLatestInputsWithoutRecreatingTheChild() {
        var mounts = 0
        compose.setContent { RlTheme { FormationActionGate(original, { _, _, _ -> false }, {}) { input, actions ->
            remember { mounts++ }
            Text((input as HomeDiaryRoute.SemanticSplit).text)
            Button(onClick = { actions.stateChanged(edited, 0); actions.navigate(HomeDiaryRoute.Home) }) { Text("继续测试") }
        } } }
        compose.onNodeWithText("继续测试").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(edited.text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(edited.text).assertIsDisplayed()
        compose.onNodeWithText("学习进度保存失败").assertDoesNotExist()
        assertEquals(1, mounts)
    }
}
