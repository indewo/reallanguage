package com.reallanguage.app.feature.home.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.onConsumedWindowInsetsChanged
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlPaperPanel

/** Page 56 regional geometry. The frozen separate book/paper assets are not exact raster matches. */
@Composable
internal fun IncompleteLearningFrame(
    count: Int?,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    RlPaperBackground(showCornerCollage = false) {
        var consumedInsets by remember { mutableStateOf<WindowInsets>(WindowInsets(0, 0, 0, 0)) }
        BoxWithConstraints(Modifier.fillMaxSize().onConsumedWindowInsetsChanged { consumedInsets = it }) {
            val density = LocalDensity.current
            val direction = LocalLayoutDirection.current
            val safe = WindowInsets.safeDrawing.exclude(consumedInsets).asPaddingValues()
            val safeLeft = safe.calculateLeftPadding(direction)
            val safeRight = safe.calculateRightPadding(direction)
            if (maxWidth < 700.dp || maxHeight < 340.dp || density.fontScale > 1.35f) {
                Column(Modifier.fillMaxSize().padding(safe).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = RlColor.Ink) }
                        Column(Modifier.weight(1f)) {
                            Text("未完成学习", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 23.sp, lineHeight = 31.sp)
                            Text(if (count == null) "数量待确认" else "$count 个进行中 · 从上次停下的位置继续", color = RlColor.TextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                        Image(painterResource(R.drawable.rl_incomplete_learning_book_collage_v2), null, Modifier.size(72.dp), contentScale = ContentScale.Fit)
                    }
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
                }
                return@BoxWithConstraints
            }
            val headerLeft = maxOf(maxWidth * (196f / 1831f), safeLeft + 50.dp)
            val headerTop = maxOf(maxHeight * (36f / 859f), safe.calculateTopPadding() + 4.dp)
            val contentLeft = maxWidth * (664f / 1831f)
            val contentRight = minOf(maxWidth * (1780f / 1831f), maxWidth - safeRight - 8.dp)
            val contentTop = maxOf(maxHeight * (126f / 859f), safe.calculateTopPadding() + 8.dp)
            val contentBottom = maxHeight - maxOf(maxHeight * (50f / 859f), safe.calculateBottomPadding() + 8.dp)
            val underlineWidth = maxWidth * (132f / 1831f)
            var measuredHeaderHeight by remember { mutableStateOf(0.dp) }

            IconButton(onBack, Modifier.offset(x = maxOf(maxWidth * (40f / 1831f), safeLeft + 4.dp),
                y = maxOf(maxHeight * (32f / 859f), safe.calculateTopPadding() + 4.dp)).size(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.settings_back), tint = RlColor.Ink)
            }
            Column(Modifier.offset(x = headerLeft, y = headerTop)
                .width((contentLeft - headerLeft - 4.dp).coerceAtLeast(1.dp))
                .onSizeChanged { measuredHeaderHeight = with(density) { it.height.toDp() } }) {
                Text("未完成学习", color = RlColor.Ink,
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                        fontWeight = FontWeight.Bold, fontSize = 23.sp, lineHeight = 26.sp))
                Text("从上次停下的位置继续", color = RlColor.TextSecondary, style = MaterialTheme.typography.bodySmall)
                Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null,
                    Modifier.width(underlineWidth).height(underlineWidth * (385f / 2062f)), contentScale = ContentScale.Fit)
            }

            val bookTop = maxOf(maxHeight * (169f / 859f), headerTop + measuredHeaderHeight + 2.dp)
            val bookBottom = maxHeight - maxOf(maxHeight * (70f / 859f), safe.calculateBottomPadding() + 8.dp)
            IncompleteBookStatistics(count, Modifier.offset(y = bookTop)
                .width(maxWidth * (678f / 1831f)).height((bookBottom - bookTop).coerceAtLeast(1.dp)))

            val contentWidth = (contentRight - contentLeft).coerceAtLeast(1.dp)
            val contentHeight = (contentBottom - contentTop).coerceAtLeast(1.dp)
            Column(Modifier.offset(x = contentLeft, y = contentTop).width(contentWidth).height(contentHeight),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

@Composable
private fun IncompleteBookStatistics(count: Int?, modifier: Modifier) {
    BoxWithConstraints(modifier.testTag("incomplete-learning-artwork")) {
        // Use the actual Fit transform of the 1312×1199 immutable bitmap. The blue writing
        // area is x650..1080/y310..1000, not the full Box nor the tan botanical ribbon.
        val scale = minOf(maxWidth / 1312f, maxHeight / 1199f)
        val imageLeft = (maxWidth - scale * 1312f) / 2f
        val imageTop = (maxHeight - scale * 1199f) / 2f
        Image(painterResource(R.drawable.rl_incomplete_learning_book_collage_v2), null,
            Modifier.fillMaxSize().testTag("incomplete-learning-book-image"), contentScale = ContentScale.Fit)
        val statisticsHeight = scale * 690f
        Box(Modifier.offset(x = imageLeft + scale * 650f, y = imageTop + scale * 310f)
            .width(scale * 430f).height(statisticsHeight).clipToBounds()) {
            Column(Modifier.fillMaxSize().testTag("incomplete-book-statistics"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                Text("继续你的表达", Modifier.fillMaxWidth(), color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 14.sp, lineHeight = 21.sp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(count?.toString() ?: "—", Modifier.fillMaxWidth(), color = Color.White,
                    style = MaterialTheme.typography.displaySmall.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 34.sp, lineHeight = 42.sp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text(if (count == null) "数量待确认" else "个进行中", Modifier.fillMaxWidth(), color = Color.White.copy(alpha = .82f),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}
