package com.reallanguage.app.feature.safety.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.safeDrawingPadding
import com.reallanguage.app.feature.formation.presentation.EditorialProcessWorkspace
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlProcessWorkspace
import java.util.Calendar

@Composable
fun SafetyCheckScreen(state: SafetyCheckUiState, onAction: (SafetyCheckAction) -> Unit, modifier: Modifier = Modifier) {
    if (state.failed) {
        EditorialProcessWorkspace(
            title = stringResource(R.string.safety_check_failed_title),
            supportingText = stringResource(R.string.safety_check_failed_explanation),
            stages = listOf("读取待检查内容", "完成安全检查", "进入内容形成"),
            activeStage = 1,
            failed = true,
            modifier = modifier,
        ) {
            RlPrimaryButton(
                text = stringResource(R.string.diary_retry),
                enabled = true,
                onClick = { onAction(SafetyCheckAction.RetryRequested) },
            )
        }
        return
    }
    RlPaperBackground(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val canvasWidth = maxWidth
            val canvasHeight = maxHeight
            val compact = maxWidth < 600.dp || maxWidth <= maxHeight * 1.35f || LocalDensity.current.fontScale > 1.2f
            if (compact) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    SafetyCopy(Modifier.fillMaxWidth())
                    SafetyChecklist(Modifier.fillMaxWidth().height(340.dp), independentlyScrollable = false)
                    SafetySourcePaper(state.source.text, Modifier.fillMaxWidth().height(520.dp))
                }
            } else {
                SafetyCopy(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = canvasWidth * .042f, top = canvasHeight * .085f)
                        .width(canvasWidth * .44f),
                )
                SafetyChecklist(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = canvasWidth * .225f, top = canvasHeight * .425f)
                        .width(canvasWidth * .295f)
                        .height(canvasHeight * .495f),
                    independentlyScrollable = true,
                )
                SafetySourcePaper(
                    state.source.text,
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = canvasHeight * .035f, end = canvasWidth * .035f)
                        .width(canvasWidth * .49f)
                        .height(canvasHeight * .93f),
                )
            }
        }
    }
}

@Composable
private fun SafetyCopy(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("安全检查", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 25.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
        Image(
            com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null,
            Modifier.width(132.dp).height(8.dp), contentScale = ContentScale.FillBounds,
        )
        Text(
            "正在确认这份内容可以进入学习",
            color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 19.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold,
        )
        Text(
            "我们只检查本次确认的中文来源，不会改变你的原意。",
            color = RlColor.TextPrimary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp, lineHeight = 18.sp,
        )
        Text("每份新内容都会独立检查。", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 11.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun SafetyChecklist(modifier: Modifier = Modifier, independentlyScrollable: Boolean = true) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier.testTag("safety-check-list")) {
        Image(
            painterResource(R.drawable.rl_diary_processing_analysis_note), null,
            Modifier.matchParentSize(), contentScale = ContentScale.FillBounds,
        )
        val listModifier = Modifier.fillMaxSize().padding(
                start = maxWidth * .13f,
                end = maxWidth * .12f,
                top = maxHeight * .09f * fontScale,
                bottom = maxHeight * .10f * fontScale,
            ).let { if (independentlyScrollable) it.verticalScroll(rememberScrollState()) else it }
        Column(
            listModifier,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            SafetyCheckRow("来源识别", "确认中文来源", Icons.Outlined.Search, true)
            SafetyCheckRow("内容安全", "检查不当或敏感信息", Icons.Outlined.Security, true)
            SafetyCheckRow("隐私保护", "隐藏个人信息", Icons.Outlined.EditNote, true)
            SafetyCheckRow("完整性确认", "保留原意与上下文", Icons.Outlined.Eco, false)
        }
        Image(
            painterResource(R.drawable.rl_black_binder_clip), null,
            Modifier.align(Alignment.TopEnd).offset(x = (-8).dp, y = (-18).dp).size(58.dp),
        )
    }
}

@Composable
private fun SafetyCheckRow(title: String, subtitle: String, icon: ImageVector, complete: Boolean) {
    Row(Modifier.heightIn(min = 34.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = RlColor.Secondary, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = RlColor.TextSecondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 11.sp, lineHeight = 17.sp)
        }
        if (complete) {
            Icon(Icons.Outlined.CheckCircle, null, tint = RlColor.Secondary, modifier = Modifier.size(24.dp))
        } else {
            CircularProgressIndicator(color = RlColor.Secondary, strokeWidth = 1.5.dp, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun SafetySourcePaper(text: String, modifier: Modifier = Modifier) {
    val calendar = Calendar.getInstance()
    Box(modifier.testTag("safety-check-source")) {
        Image(painterResource(R.drawable.rl_v73_safety_source_paper), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Column(Modifier.fillMaxSize().padding(start = 60.dp, end = 52.dp, top = 66.dp, bottom = 42.dp).verticalScroll(rememberScrollState())) {
            Text(
                "来源 · ${calendar.get(Calendar.MONTH) + 1}月${calendar.get(Calendar.DAY_OF_MONTH)}日 日记",
                color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
            Image(
                com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null,
                Modifier.width(150.dp).height(7.dp), contentScale = ContentScale.FillBounds,
            )
            Text(
                text.ifBlank { "正在读取本次确认的来源……" },
                color = RlColor.TextSecondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 15.sp,
                lineHeight = 27.sp, modifier = Modifier.padding(top = 18.dp, end = 32.dp, bottom = 60.dp),
            )
        }
        Image(
            painterResource(R.drawable.rl_safety_privacy_stamp), null,
            Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 26.dp).size(66.dp),
        )
        Image(
            painterResource(R.drawable.rl_safety_magnifying_glass), null,
            Modifier.align(Alignment.BottomEnd).fillMaxWidth(.55f).fillMaxHeight(.6f).offset(x = 16.dp, y = 12.dp),
            contentScale = ContentScale.Fit,
        )
    }
}
