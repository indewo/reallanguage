package com.reallanguage.app.core.app

import androidx.compose.runtime.*
import com.reallanguage.app.feature.learning.presentation.ExplanationPreparationAction
import com.reallanguage.app.feature.learning.presentation.ExplanationPreparationScreen
import com.reallanguage.app.feature.learning.presentation.ExplanationPreparationUiState
import kotlinx.coroutines.CancellationException

@Composable
internal fun LearningRouteLoader(
    request: HomeDiaryRoute.ResumeLearning,
    load: suspend () -> HomeDiaryRoute?,
    onReady: (HomeDiaryRoute) -> Unit,
    onReturn: () -> Unit,
) {
    val currentReady by rememberUpdatedState(onReady)
    LearningContentLoader(request, load, onReturn,
        loadingMessage = "正在恢复学习进度…", failureMessage = "学习进度恢复失败",
        failureDisplay = if (request.moduleId.startsWith("FORMATION:"))
            com.reallanguage.app.feature.learning.presentation.ExplanationPreparationFailureDisplay(
                detail = null, backLabel = "返回未完成学习",
                title = "恢复学习进度",
                supporting = "从上次保存的位置继续；读取完成后进入学习。",
                stageLabels = listOf("读取已保存内容", "恢复学习位置", "继续上次学习"),
            ) else com.reallanguage.app.feature.learning.presentation.ExplanationPreparationFailureDisplay(
                title = "恢复学习进度",
                supporting = "从上次保存的位置继续；读取完成后进入学习。",
                stageLabels = listOf("读取已保存内容", "恢复学习位置", "继续上次学习"),
            ),
    ) { result ->
        LaunchedEffect(request, result) { currentReady(result) }
    }
}

/** Read-only local content gate shared with review; a missing value is failure, not endless loading. */
@Composable
internal fun <T : Any> LearningContentLoader(
    requestKey: Any,
    load: suspend () -> T?,
    onReturn: () -> Unit,
    loadingMessage: String = "正在读取学习内容…",
    failureMessage: String = "学习内容读取失败",
    failureDisplay: com.reallanguage.app.feature.learning.presentation.ExplanationPreparationFailureDisplay = com.reallanguage.app.feature.learning.presentation.ExplanationPreparationFailureDisplay(
        title = "读取学习内容",
        supporting = "读取当前内容后继续，不会改变已有学习记录。",
        stageLabels = listOf("读取当前内容", "确认当前位置", "进入学习页面"),
    ),
    content: @Composable (T) -> Unit,
) {
    var attempt by remember(requestKey) { mutableIntStateOf(0) }
    var failed by remember(requestKey) { mutableStateOf(false) }
    var loaded by remember(requestKey) { mutableStateOf<T?>(null) }
    val currentLoad by rememberUpdatedState(load)
    LaunchedEffect(requestKey, attempt) {
        failed = false
        val result = try { currentLoad() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        if (result == null) failed = true else loaded = result
    }
    val ready = loaded
    if (ready != null) { content(ready); return }
    // Reuse the existing preparation/error layout; keep the same requested chapter and sentence.
    ExplanationPreparationScreen(ExplanationPreparationUiState(loading = !failed, failed = failed,
        loadingMessage = loadingMessage, failureMessage = failureMessage), failureDisplay) {
        when (it) {
            ExplanationPreparationAction.RetryRequested -> if (failed) attempt++
            ExplanationPreparationAction.BackRequested -> onReturn()
        }
    }
}
