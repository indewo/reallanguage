package com.reallanguage.app.feature.formation.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlSpace
import com.reallanguage.app.feature.formation.presentation.EditorialProcessWorkspace
import com.reallanguage.app.core.designsystem.RlSecondaryButton

@Composable
fun LearningTargetLoadingScreen(state: LearningTargetLoadingUiState, onAction: (LearningTargetLoadingAction) -> Unit) {
    EditorialProcessWorkspace(
        poster = com.reallanguage.app.R.drawable.rl_v73_target_poster_r2,
        pageTitle = "正在提取学习目标",
        title = if (state.failed) "学习目标提取失败" else "从真实表达中寻找学习目标",
        supportingText = if (state.failed) "已保留你确认的中英文和候选选择。" else "已确认的中英文内容会完整保留。",
        stages = listOf("分析重点句", "提取词汇与句式", "建立学习目标"),
        footerNote = "只使用你已确认的表达与当前词汇范围。",
        activeStage = 1,
        failed = state.failed,
        fixedStages = true,
    ) {
        if (state.processing) CircularProgressIndicator(color = RlColor.Primary)
        if (state.failed) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RlPrimaryButton(text = "重试", enabled = true, onClick = { onAction(LearningTargetLoadingAction.RetryRequested) }, modifier = Modifier.weight(1f))
            RlSecondaryButton(text = "返回英语确认", onClick = { onAction(LearningTargetLoadingAction.BackRequested) }, modifier = Modifier.weight(1f))
            }
        }
    }
}
