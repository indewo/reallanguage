package com.reallanguage.app.feature.scene.presentation

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.RlSpace
import com.reallanguage.app.feature.formation.presentation.EditorialProcessWorkspace
import com.reallanguage.app.core.designsystem.RlSecondaryButton
import com.reallanguage.app.feature.scene.domain.SceneStart
import com.reallanguage.app.feature.scene.domain.SceneContentType

@Composable
fun SceneGenerationScreen(
    state: SceneGenerationUiState,
    onAction: (SceneGenerationAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    EditorialProcessWorkspace(
        poster = R.drawable.rl_v73_scene_context,
        artwork = { posterModifier ->
            val topic = when (val start = state.session.start) {
                is SceneStart.DirectDescription -> start.description
                is SceneStart.Recommendation -> start.recommendation.shortTopicZh
                is SceneStart.Template -> start.template.titleZh
            }
            SceneSummaryPoster(topic, if (state.session.contentType == SceneContentType.DIALOGUE) "对话" else "短文",
                state.session.answers.firstOrNull()?.answerZh?.takeIf(String::isNotBlank), posterModifier)
        },
        pageTitle = "生成场景内容",
        title = if (state.error != null) stringResource(R.string.scene_generation_failed_title)
        else stringResource(R.string.scene_generation_title),
        supportingText = if (state.error != null) stringResource(R.string.scene_generation_failed_explanation)
        else stringResource(R.string.scene_generation_explanation),
        stages = listOf("整理场景信息", "编排中文场景", "生成练习内容"),
        activeStage = 1,
        failed = state.error != null,
        modifier = modifier.testTag("scene-generation-workspace"),
    ) {
        if (state.generating) {
            CircularProgressIndicator(
                modifier = Modifier.testTag("scene-generation-progress"),
                color = RlColor.Primary,
            )
        }
        if (state.error != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RlPrimaryButton(
                text = stringResource(R.string.scene_generation_retry),
                enabled = true,
                onClick = { onAction(SceneGenerationAction.RetryRequested) },
                modifier = Modifier.weight(1f),
            )
            RlSecondaryButton(
                text = stringResource(R.string.scene_generation_return),
                onClick = { onAction(SceneGenerationAction.ReturnRequested) },
                modifier = Modifier.weight(1f),
            )
            }
        }
    }
}
