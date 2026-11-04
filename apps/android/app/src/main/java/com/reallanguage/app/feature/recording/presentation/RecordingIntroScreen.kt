package com.reallanguage.app.feature.recording.presentation

import com.reallanguage.app.feature.formation.presentation.EditorialSheet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlSecondaryButton
import com.reallanguage.app.core.designsystem.RlSpace

@Composable
fun RecordingIntroScreen(state: RecordingIntroUiState, onAction: (RecordingIntroAction) -> Unit) {
    BackHandler { onAction(RecordingIntroAction.ReturnHomeRequested) }
    RecordingEditorialScaffold(
        title = stringResource(R.string.recording_intro_title),
        headerTag = "recording-intro-header",
        onBack = { onAction(RecordingIntroAction.ReturnHomeRequested) },
        paperBody = false,
        artwork = { modifier ->
            Box(modifier.testTag("recording-intro-artwork")) {
                Image(painterResource(R.drawable.rl_v73_recording_poster), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                Text("把真实发生的事\n先说下来", Modifier.fillMaxWidth(.75f).padding(start = 36.dp, top = 42.dp),
                    color = Color.White, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 38.sp)
            }
        },
        actions = {
            RlSecondaryButton(stringResource(R.string.incomplete_learning_return), !state.requestingPermission,
                { onAction(RecordingIntroAction.ReturnHomeRequested) }, Modifier.weight(.85f).testTag("recording-intro-home"))
            RlPrimaryButton(stringResource(if (state.requestingPermission) R.string.recording_intro_requesting else R.string.recording_intro_agree),
                !state.requestingPermission, { onAction(RecordingIntroAction.AgreeRequested) },
                Modifier.weight(1f).testTag("recording-intro-agree"), containerColor = RlColor.ActionVivid)
        },
    ) {
        RecordingIntroStep("1", "录音保存在本机", "你可以稍后继续处理")
        RecordingIntroStep("2", "录音将用于转写", "开始转写后，录音会发送到处理服务", showStamp = true)
        RecordingIntroStep("3", "麦克风权限", "仅用于你主动开始的录音")
        Text("继续后才会请求麦克风权限", Modifier.align(Alignment.End).padding(12.dp),
            color = RlColor.Primary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 14.sp, lineHeight = 22.sp)
    }
}

@Composable
private fun RecordingIntroStep(number: String, title: String, supporting: String, showStamp: Boolean = false) {
    EditorialSheet(
        modifier = Modifier.fillMaxWidth().heightIn(min = 74.dp).testTag("recording-intro-step"),
        contentPadding = 14.dp,
        contentSpacing = RlSpace.Space4,
        showSpine = false,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(RlSpace.Space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(42.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)),
                color = RlColor.ActionVivid,
                shape = CircleShape,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        number,
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 22.sp, lineHeight = 30.sp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(title, color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 26.sp)
                Text(supporting, color = RlColor.TextSecondary, fontSize = 13.sp, lineHeight = 21.sp)
            }
            if (showStamp) {
                Image(
                    painter = painterResource(R.drawable.rl_safety_privacy_stamp),
                    contentDescription = null,
                    modifier = Modifier.size(42.dp),
                )
            }
        }
    }
}
