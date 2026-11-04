package com.reallanguage.app.feature.recording.presentation

import com.reallanguage.app.feature.formation.presentation.EditorialSheet

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlSpace

@Composable
internal fun RecordingPageFrame(
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(RlColor.Background.copy(alpha = 0f)).safeDrawingPadding()
            .padding(RlSpace.Space12),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            modifier = Modifier.widthIn(max = 1480.dp).fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(RlSpace.Space12),
        ) {
            Box(modifier = Modifier.weight(0.72f).fillMaxHeight()) {
                Image(
                    painter = painterResource(R.drawable.rl_v73_recording_poster),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                Column(
                    modifier = Modifier.fillMaxSize().padding(
                        start = RlSpace.Space24,
                        end = RlSpace.Space64,
                        top = RlSpace.Space24,
                        bottom = RlSpace.Space24,
                    ),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "本机录音",
                        color = RlColor.OnPrimary,
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    Text(
                        "LOCAL · PRIVATE",
                        modifier = Modifier.padding(top = RlSpace.Space12),
                        color = RlColor.OnPrimary.copy(alpha = 0.76f),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        "把真实发生的事先说下来。\n录音只保存在本机。",
                        modifier = Modifier.padding(top = RlSpace.Space24),
                        color = RlColor.OnPrimary.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1.28f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(RlSpace.Space4),
            ) {
                EditorialSheet(
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .verticalScroll(rememberScrollState()),
                    contentPadding = RlSpace.Space12,
                    contentSpacing = RlSpace.Space8,
                    content = content,
                )
                footer?.let {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8),
                        content = it,
                    )
                }
            }
        }
    }
}
