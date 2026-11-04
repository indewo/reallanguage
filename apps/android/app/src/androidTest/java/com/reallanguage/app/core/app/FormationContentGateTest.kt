package com.reallanguage.app.core.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.formation.domain.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FormationContentGateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val route = HomeDiaryRoute.SemanticSplit("我喝茶。", entryId = "source")
    private val saved = requireNotNull(route.toFormationRecoverySnapshot(0, "日记"))

    @Test fun acceptedSourceMustBeSavedBeforeAnyBillableChildIsMounted() {
        val result = CompletableDeferred<FormationRecoverySnapshot?>()
        compose.setContent { RlTheme { FormationContentGate(route, { result.await() }, {}, {}) { displayed, _ -> Text(if (displayed == route) "paid-child" else "previous-page") } } }
        compose.onNodeWithText("paid-child").assertDoesNotExist()
        compose.onNodeWithText("previous-page").assertIsDisplayed()
        compose.onNodeWithText("正在保存学习进度…").assertDoesNotExist()
        compose.runOnIdle { result.complete(saved) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("paid-child").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("paid-child").assertIsDisplayed()
    }

    @Test fun failedLoadRestoresThePreviousPageWithoutRenderingAProgressOrFailurePage() {
        var calls = 0
        val restored = mutableListOf<HomeDiaryRoute>()
        compose.setContent { RlTheme { FormationContentGate(route, { calls++; null }, {}, restored::add) { displayed, _ -> Text(if (displayed == route) "paid-child" else "previous-page") } } }
        compose.waitUntil(5_000) { calls == 1 && restored.isNotEmpty() }
        compose.onNodeWithText("paid-child").assertDoesNotExist()
        compose.onNodeWithText("previous-page").assertIsDisplayed()
        compose.onNodeWithText("学习进度保存失败").assertDoesNotExist()
        assertEquals(listOf(HomeDiaryRoute.Home), restored)
    }

    @Test fun savedResultBypassesOnlyTheLoadingRequestAndPreservesTheSourceIdentity() {
        val focus = HomeDiaryRoute.FocusSelection(route.text, listOf(SemanticUnit("s", 0, route.text)), sourceEntryId = route.entryId)
        val splitWithUnits = requireNotNull(route.toFormationRecoverySnapshot(0, "日记", focus.toFormationRecoverySnapshot(0, "日记")))
        val opened = mutableListOf<HomeDiaryRoute>()
        compose.setContent { RlTheme { FormationContentGate(route, { splitWithUnits }, opened::add, {}) { _, _ -> Text("paid-child") } } }
        compose.waitForIdle()
        compose.onNodeWithText("paid-child").assertIsDisplayed()
        assertEquals(1, opened.size)
        assertEquals(route.entryId, (opened.single() as HomeDiaryRoute.FocusSelection).sourceEntryId)
    }
}
