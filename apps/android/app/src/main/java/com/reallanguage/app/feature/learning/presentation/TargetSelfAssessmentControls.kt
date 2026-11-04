package com.reallanguage.app.feature.learning.presentation

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.feature.learning.domain.SelfAssessmentAbility

@Composable
fun TargetSelfAssessmentControls(
    state: TargetSelfAssessmentUiState,
    onAction: (TargetSelfAssessmentAction) -> Unit,
    display: String? = null,
    targetId: String? = null,
    enabled: Boolean = true,
    cardAction: Boolean = false,
) {
    val targets = state.targets.filter {
        if (targetId != null) it.targetId == targetId
        else display != null && it.display.trim().equals(display.trim(), ignoreCase = true)
    }
    if (state.failed) {
        Text("自评未能保存或读取，请重试。", color = RlColor.OnPrimary, fontSize = 12.sp)
        if (cardAction) {
            LearningAction(
                text = "重试自评",
                onClick = { onAction(TargetSelfAssessmentAction.Retry) },
                enabled = enabled && !state.busy,
                primary = true,
                modifier = Modifier.widthIn(min = 96.dp).testTag("expression_assessment_action"),
            )
        } else OutlinedButton(onClick = { onAction(TargetSelfAssessmentAction.Retry) }, enabled = enabled && !state.busy,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RlColor.OnPrimary)) { Text("重试自评") }
    }
    targets.forEach { target ->
        if (cardAction) {
          Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                target.abilities.forEach { ability ->
                    val assessed = ability in target.assessedAbilities
                    val label = when (ability) {
                        SelfAssessmentAbility.MEANING_WRITING -> if (assessed) "暂定已会" else "会了"
                        SelfAssessmentAbility.PATTERN_WRITING -> if (assessed) "会写：暂定已会" else "我会写"
                        SelfAssessmentAbility.PATTERN_SPEAKING -> if (assessed) "会说：暂定已会" else "我会说"
                    }
                    LearningAction(
                        text = label,
                        onClick = { onAction(TargetSelfAssessmentAction.Mark(target.targetId, ability)) },
                        enabled = enabled && !state.busy && !state.failed && !assessed,
                        primary = true,
                        modifier = Modifier.widthIn(min = 88.dp, max = 146.dp)
                            .testTag("expression_assessment_action"),
                    )
                }
            }
          }
        } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(target.explanationZh, color = RlColor.OnPrimary, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                target.abilities.forEach { ability ->
                    val assessed = ability in target.assessedAbilities
                    val label = when (ability) {
                        SelfAssessmentAbility.MEANING_WRITING -> if (assessed) "暂定已会" else "会了"
                        SelfAssessmentAbility.PATTERN_WRITING -> if (assessed) "会写：暂定已会" else "我会写"
                        SelfAssessmentAbility.PATTERN_SPEAKING -> if (assessed) "会说：暂定已会" else "我会说"
                    }
                    OutlinedButton(
                        onClick = { onAction(TargetSelfAssessmentAction.Mark(target.targetId, ability)) },
                        enabled = enabled && !state.busy && !state.failed && !assessed,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RlColor.OnPrimary,
                            disabledContentColor = RlColor.OnPrimary.copy(alpha = .72f)),
                    ) { Text(label, fontSize = 12.sp) }
                }
            }
        }
    }
}
