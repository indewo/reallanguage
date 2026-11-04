package com.reallanguage.app.feature.safety.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.*
import com.reallanguage.app.feature.formation.presentation.EditorialSheet

@Composable
fun SafetyHandlingLoaderScreen(state: SafetyHandlingLoaderUiState, onRetry: () -> Unit) {
    RlPaperBackground {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
            val compact = maxWidth < 650.dp || LocalDensity.current.fontScale > 1.35f
            val heading: @Composable () -> Unit = {
                Text(stringResource(if (state.loading) R.string.safety_preparing_deidentified else R.string.safety_preparing_failed), color = if (state.loading) RlColor.Ink else RlColor.Error,
                    fontFamily = EditorialFont.Serif, fontSize = 29.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
                Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null, Modifier.width(230.dp).height(9.dp), contentScale = ContentScale.FillBounds)
                Text("本次内容将用于下一步处理。", color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 18.sp, lineHeight = 29.sp)
            }
            val steps: @Composable ColumnScope.() -> Unit = {
                listOf("整理原始内容" to "提取并规范内容格式", "准备处理结果" to if (state.loading) "正在进行安全处理…" else "原内容已保留，可以重试", "返回确认界面" to "完成后供你确认查看").forEachIndexed { index, (title, detail) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.size(48.dp).background(if (index == 1) RlColor.ActionVivid else RlColor.Secondary, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(listOf(Icons.Outlined.Description, Icons.Outlined.Settings, Icons.AutoMirrored.Outlined.Undo)[index], null, tint = RlColor.Surface, modifier = Modifier.size(28.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(title, color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 20.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold)
                            Text(detail, color = RlColor.TextSecondary, fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 23.sp)
                        }
                        if (index == 1 && state.loading) CircularProgressIndicator(Modifier.size(25.dp), color = RlColor.ActionVivid, strokeWidth = 2.dp)
                    }
                    if (index < 2) HorizontalDivider(color = RlColor.Border)
                }
                Text("请稍候，原始内容仍然保留。", color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 23.sp)
            }
            if (compact) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                heading()
                EditorialSheet(contentPadding = 24.dp, showSpine = true, content = steps)
                if (!state.loading) RlPrimaryButton(stringResource(R.string.diary_retry), true, onRetry)
            } else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Column(Modifier.weight(.94f).fillMaxHeight().padding(top = 15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    heading()
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Image(painterResource(R.drawable.rl_process_documents_collage), null, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
                        Image(painterResource(R.drawable.rl_safety_magnifying_glass), null, Modifier.align(Alignment.BottomEnd).fillMaxWidth(.82f).fillMaxHeight(.9f), contentScale = ContentScale.Fit)
                        Image(painterResource(R.drawable.rl_safety_privacy_stamp), null, Modifier.align(Alignment.TopEnd).padding(top = 15.dp).size(80.dp), contentScale = ContentScale.Fit)
                    }
                }
                Column(Modifier.weight(1.06f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EditorialSheet(Modifier.fillMaxWidth().weight(1f), contentPadding = 24.dp, showSpine = true,
                        contentModifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), content = steps)
                    if (!state.loading) RlPrimaryButton(stringResource(R.string.diary_retry), true, onRetry)
                }
            }
        }
    }
}
