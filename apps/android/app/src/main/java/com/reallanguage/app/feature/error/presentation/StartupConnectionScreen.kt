package com.reallanguage.app.feature.error.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.EditorialFont
import com.reallanguage.app.core.designsystem.RlColor

/** Startup has no preceding learning page or return action. Only its original retry callback is exposed. */
@Composable
fun StartupConnectionScreen(onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().background(RlColor.Background)) {
        Image(painterResource(R.drawable.rl_paper_background), null,
            Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        // The startup reference has a dimmed paper canvas, not a bright empty page.
        // Keep the paper and its controls undimmed, independently of the backdrop.
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .15f)))
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center) {
            val compact = maxHeight < 340.dp || LocalDensity.current.fontScale > 1.3f
            Box(Modifier.widthIn(max = 450.dp).fillMaxWidth().height(
                if (compact) (maxHeight - 12.dp).coerceAtLeast(160.dp) else maxHeight * .78f)) {
                Image(painterResource(R.drawable.rl_v73_error_connection_paper), null,
                    Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 24.dp else 48.dp,
                    vertical = if (compact) 16.dp else 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp)) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Image(painterResource(R.drawable.rl_connection_unavailable), null, Modifier.size(38.dp))
                        Text("连接不可用", color = RlColor.Ink, fontFamily = EditorialFont.Serif,
                            fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp)
                        HorizontalDivider(color = RlColor.Border)
                        Text("当前暂时无法连接服务。\n请检查网络后重新连接。", color = RlColor.TextPrimary,
                            fontFamily = EditorialFont.Serif, fontSize = 13.sp, lineHeight = 21.sp,
                            textAlign = TextAlign.Center)
                        HorizontalDivider(color = RlColor.Border)
                        Text("已有内容不会因此被删除。", color = RlColor.TextSecondary,
                            fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 19.sp,
                            textAlign = TextAlign.Center)
                    }
                    ErrorPaperAction("重新连接", onClick = onRetry,
                        modifier = Modifier.fillMaxWidth(.72f).heightIn(min = 48.dp)
                            .testTag("startup-connection-retry"))
                }
            }
        }
    }
}
