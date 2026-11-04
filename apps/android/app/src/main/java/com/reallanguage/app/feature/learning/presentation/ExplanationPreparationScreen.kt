package com.reallanguage.app.feature.learning.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import com.reallanguage.app.core.designsystem.EditorialFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlSpace

data class ExplanationPreparationFailureDisplay(
    val detail: String? = "已固定内容不受影响。",
    val backLabel: String = "返回章节",
    val showRetry: Boolean = true,
    val title: String = "准备基础讲解",
    val supporting: String = "当前章节已固定，正在准备本句讲解。",
    val stageLabels: List<String> = listOf("确认当前句子", "整理用法与例句", "准备自然发音"),
)

/** Keep the existing two-argument/trailing-lambda API and its exact default presentation. */
@Composable fun ExplanationPreparationScreen(state: ExplanationPreparationUiState, onAction: (ExplanationPreparationAction) -> Unit) =
    ExplanationPreparationScreen(state, ExplanationPreparationFailureDisplay(), onAction)

@Composable fun ExplanationPreparationScreen(
    state: ExplanationPreparationUiState,
    failureDisplay: ExplanationPreparationFailureDisplay,
    onAction: (ExplanationPreparationAction) -> Unit,
) {
    LearningPage {
        val density = LocalDensity.current
        var headingHeightPx by remember { mutableIntStateOf(0) }
        var footerHeightPx by remember { mutableIntStateOf(0) }
        // Budget the illustration from the actual measured header/footer, not a
        // fixed 294dp that pushes its torn lower edge below the scroll viewport.
        val paperHeight = (maxHeight - 32.dp - with(density) {
            (headingHeightPx + footerHeightPx).toDp()
        }).coerceIn(120.dp, 294.dp)
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
              verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth().onSizeChanged { headingHeightPx = it.height },
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LearningHeading(failureDisplay.title, onBack = { onAction(ExplanationPreparationAction.BackRequested) })
            Text(failureDisplay.supporting, Modifier.align(Alignment.CenterHorizontally),
                color = RlColor.TextSecondary, fontFamily = EditorialFont.Serif, fontSize = 15.sp, lineHeight = 21.sp)
            // The real state belongs above decorative art and generic stage labels,
            // especially when this presentation is reused for a save gate.
            if (state.loading) Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), color = RlColor.Secondary, strokeWidth = 2.dp)
                Text(state.loadingMessage, color = RlColor.TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
            }
            if (state.failed) Text(state.failureMessage, Modifier.align(Alignment.CenterHorizontally), color = RlColor.Error,
                fontSize = 16.sp, lineHeight = 22.sp)
            }
            LearningTwoPane(leftWeight = .4f, contentFirstWhenStacked = true,
                left = { pane -> Image(painterResource(R.drawable.rl_v73_learning_preparation_notebook), null,
                    pane.height(paperHeight), contentScale = ContentScale.FillBounds) },
                right = { pane -> LearningPaper(pane, padding = PaddingValues(20.dp)) {
                    failureDisplay.stageLabels.forEachIndexed { index, label ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            LearningSeal(listOf(Icons.Outlined.Check, Icons.Outlined.MenuBook, Icons.Outlined.VolumeUp)[index % 3], RlColor.Secondary)
                            Text(label, fontFamily = EditorialFont.Serif, fontSize = 19.sp, lineHeight = 26.sp, color = RlColor.Ink)
                        }
                        if (index < failureDisplay.stageLabels.lastIndex) androidx.compose.material3.HorizontalDivider(color = RlColor.Border)
                    }
                    failureDisplay.detail?.let { LearningRibbon(it, Modifier.fillMaxWidth()) }
                } })
          }
            Row(Modifier.fillMaxWidth().onSizeChanged { footerHeightPx = it.height }, horizontalArrangement = Arrangement.End) {
                if (state.failed && failureDisplay.showRetry) LearningAction("重试", { onAction(ExplanationPreparationAction.RetryRequested) }, primary = true)
                Spacer(Modifier.width(12.dp))
                LearningAction(failureDisplay.backLabel, { onAction(ExplanationPreparationAction.BackRequested) })
            }
        }
    }
}
