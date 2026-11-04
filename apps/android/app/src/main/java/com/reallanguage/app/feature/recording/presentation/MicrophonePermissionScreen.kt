package com.reallanguage.app.feature.recording.presentation

import com.reallanguage.app.feature.formation.presentation.EditorialSheet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.*

@Composable
fun MicrophonePermissionScreen(
    state: MicrophonePermissionUiState,
    onAction: (MicrophonePermissionAction) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    BackHandler { onBack?.invoke() ?: onAction(MicrophonePermissionAction.ReturnHomeRequested) }
    RecordingEditorialScaffold(
        title = "麦克风权限",
        headerTag = "microphone-permission-header",
        artwork = { PermissionArtwork(it) },
        actions = {
            RlSecondaryButton(stringResource(R.string.microphone_permission_return_home),
                onClick = { onAction(MicrophonePermissionAction.ReturnHomeRequested) },
                modifier = Modifier.weight(1f).testTag("microphone-permission-home"))
            RlPrimaryButton(stringResource(if (state.canRequestDirectly) R.string.microphone_permission_retry else R.string.microphone_permission_open_settings),
                !state.requestingPermission, { onAction(MicrophonePermissionAction.AuthorizeRequested) },
                Modifier.weight(1.2f).testTag("microphone-permission-authorize"), containerColor = RlColor.ActionVivid)
        },
    ) {
        Text(stringResource(R.string.microphone_permission_title), color = RlColor.Ink,
            fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 38.sp)
        Text(stringResource(R.string.microphone_permission_explanation), color = RlColor.TextPrimary, fontSize = 15.sp, lineHeight = 24.sp)
        HorizontalDivider(color = RlColor.Border)
        Column(Modifier.fillMaxWidth().testTag("microphone-permission-steps"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            PermissionStep("1", if (state.canRequestDirectly) "允许权限请求" else "打开系统设置",
                if (state.canRequestDirectly) "在系统弹窗中选择允许" else "前往手机设置中的应用权限") {
                Icon(Icons.Outlined.Settings, null, tint = RlColor.Primary)
            }
            PermissionStep("2", "允许麦克风访问", "找到 RealLanguage 并开启麦克风权限") {
                Icon(Icons.Outlined.Mic, null, tint = RlColor.Secondary)
            }
        }
        Surface(color = RlColor.NoteContainer, shape = RlShape.Small) {
            Text("授权后返回 RealLanguage 即可继续", Modifier.fillMaxWidth().padding(14.dp),
                color = RlColor.Primary, fontSize = 14.sp, lineHeight = 22.sp)
        }
    }
}

@Composable
private fun PermissionArtwork(modifier: Modifier) {
    Surface(
        modifier = modifier.testTag("microphone-permission-artwork"),
        color = Color.Transparent,
        shape = RlShape.Feature,
    ) {
        Box(Modifier.fillMaxSize()) {
            Image(painterResource(R.drawable.rl_v73_permission_poster), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            Column(
                Modifier.fillMaxSize().padding(start = 42.dp, end = 32.dp, top = 54.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "只在你主动录音时使用",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 23.sp,
                        lineHeight = 34.sp,
                    ),
                )
                Text(
                    "RealLanguage 不会在后台录音",
                    Modifier.padding(top = RlSpace.Space8),
                    color = RlColor.SecondaryContainer,
                    fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 23.sp,
                )
            }
        }
    }
}

@Composable
private fun PermissionStep(
    number: String,
    title: String,
    detail: String,
    icon: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = RlSpace.Space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8),
    ) {
        Surface(color = RlColor.ActionVivid, shape = RlShape.Panel) {
            Box(Modifier.size(38.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)), contentAlignment = Alignment.Center) {
                Text(number, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 28.sp)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = RlColor.Ink, fontWeight = FontWeight.Bold)
            Text(detail, color = RlColor.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Surface(color = RlColor.Surface.copy(alpha = .9f), shape = RlShape.Panel) {
            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) { icon() }
        }
    }
}
