package com.reallanguage.app.core.app

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Production navigation proof: semantic splitting is work, never a standalone page. */
class ProductionSilentSemanticSplitJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test(timeout = 30_000)
    fun diarySafetyLoadingRemainsMountedThenOpensFocusSelectionWithoutTheDeletedPage() =
        verifyJourney(SourceType.DIARY, SourceContentShape.ARTICLE)

    @Test(timeout = 30_000)
    fun recordingSafetyLoadingRemainsMountedThenOpensFocusSelectionWithoutTheDeletedPage() =
        verifyJourney(SourceType.RECORDING, SourceContentShape.ARTICLE)

    @Test(timeout = 30_000)
    fun sceneSafetyLoadingRemainsMountedThenOpensFocusSelectionWithoutTheDeletedPage() =
        verifyJourney(SourceType.AI_SCENE, SourceContentShape.DIALOGUE)

    private fun verifyJourney(sourceType: SourceType, contentShape: SourceContentShape) {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val splitEntered = CompletableDeferred<Unit>()
        val splitResponse = CompletableDeferred<HttpResponse>()
        val session = SessionRuntime().apply {
            update("isolated-token", "silent-split-${UUID.randomUUID()}")
        }
        val transport = HttpTransport { request ->
            when (request.path) {
                "/api/v1/safety/checks" -> HttpResponse(200, "{\"status\":\"PASSED\"}")
                "/api/v1/content-formation/semantic-units/split" -> {
                    splitEntered.complete(Unit)
                    splitResponse.await()
                }
                else -> error("Unexpected request while proving silent split: ${request.path}")
            }
        }
        val graph = ProductionAppGraph(compose.activity.applicationContext, session, transport)
        compose.setContent {
            RlTheme {
                HomeProductionFlow(
                    graph,
                    initialRoute = HomeDiaryRoute.SafetyCheck(
                        text = "今天很忙。然后我去散步。",
                        sourceType = sourceType,
                        contentShape = contentShape,
                        entryId = "silent-split-${sourceType.name}",
                    ),
                )
            }
        }

        compose.waitUntil(10_000) { splitEntered.isCompleted }
        compose.onNodeWithText("安全检查").assertIsDisplayed()
        assertDeletedPageAbsent()

        splitResponse.complete(HttpResponse(503, ""))
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("选择重点句"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("选择重点句").assertIsDisplayed()
        assertDeletedPageAbsent()
    }

    private fun assertDeletedPageAbsent() {
        listOf("整理学习内容", "正在拆分内容", "读取内容", "按语义分句", "检查完整性").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }
}
