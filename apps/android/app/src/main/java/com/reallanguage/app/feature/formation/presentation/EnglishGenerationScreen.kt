package com.reallanguage.app.feature.formation.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.RlSpace
import com.reallanguage.app.feature.formation.presentation.EditorialProcessWorkspace
import com.reallanguage.app.core.designsystem.RlSecondaryButton

@Composable
fun EnglishGenerationScreen(state: EnglishGenerationUiState, onAction: (EnglishGenerationAction) -> Unit) {
    EditorialProcessWorkspace(
        poster = com.reallanguage.app.R.drawable.rl_v73_english_poster_r2,
        title = if (state.failed) "英语表达生成失败" else "正在生成英语表达",
        supportingText = "",
        stages = listOf("读取中文语义", "生成自然英语", "核对是否符合原意"),
        activeStage = 1,
        failed = state.failed,
    ) {
        if (state.processing) CircularProgressIndicator(color = RlColor.Primary)
        if (state.failed) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RlPrimaryButton(text = "重试", enabled = true, onClick = { onAction(EnglishGenerationAction.RetryRequested) }, modifier = Modifier.weight(1f))
            RlSecondaryButton(text = "返回重点句选择", onClick = { onAction(EnglishGenerationAction.ReturnToSelectionRequested) }, modifier = Modifier.weight(1f))
            }
        }
    }
}
