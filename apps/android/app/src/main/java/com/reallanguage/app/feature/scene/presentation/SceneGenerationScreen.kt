package com.reallanguage.app.feature.scene.presentation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.reallanguage.app.feature.formation.presentation.PaperProcessingScreen
import com.reallanguage.app.feature.formation.presentation.ProcessPaperStage
import com.reallanguage.app.feature.scene.domain.SceneStart

internal fun SceneStart.displayTopic(): String = when (this) {
    is SceneStart.DirectDescription -> description
    is SceneStart.Recommendation -> recommendation.shortTopicZh
    is SceneStart.Template -> template.titleZh
}

@Composable
fun SceneGenerationScreen(state: SceneGenerationUiState, onAction: (SceneGenerationAction) -> Unit,
    modifier: Modifier = Modifier) {
    PaperProcessingScreen(
        modifier = modifier.testTag("scene-generation-workspace"),
        title = if (state.error == null) "正在生成场景内容" else "暂时无法生成",
        topic = state.session.start.displayTopic(),
        stages = listOf(
            ProcessPaperStage("整理信息", Icons.Outlined.Description, completed = "已整理"),
            ProcessPaperStage("编排场景", Icons.Outlined.ChatBubbleOutline, "正在编排"),
            ProcessPaperStage("生成内容", Icons.Outlined.MenuBook, "正在生成", pending = "待生成"),
        ),
        active = state.stage,
        busy = state.generating,
        error = state.error?.let { "可重试生成，或返回修改回答。" },
        retryLabel = "重试生成",
        backLabel = "返回修改问答",
        onRetry = { onAction(SceneGenerationAction.RetryRequested) },
        onBack = { onAction(SceneGenerationAction.ReturnRequested) },
        progressTag = "scene-generation-progress",
    )
}
