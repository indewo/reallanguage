package com.reallanguage.app.feature.home.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.*

@Composable
internal fun IncompleteLearningFrame(count: Int?, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    RlPaperBackground(showCornerCollage = false) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 20.dp, vertical = 4.dp)) {
            val wide = maxWidth >= 640.dp && LocalDensity.current.fontScale <= 1.35f
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EditorialPageHeader("未完成学习", onBack, backContentDescription = "返回首页")
                if (wide) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    IncompleteBookStatistics(count, Modifier.weight(.23f).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight().testTag("incomplete-learning-content"), content = content)
                } else {
                    Text(if (count == null) "数量待确认" else "$count 进行中 · 从上次停下的位置继续", color = RlColor.TextSecondary,
                        fontFamily = EditorialFont.Serif, fontSize = 12.sp)
                    Column(Modifier.weight(1f).fillMaxWidth().testTag("incomplete-learning-content"), content = content)
                }
            }
        }
    }
}

@Composable
private fun IncompleteBookStatistics(count: Int?, modifier: Modifier) {
    BoxWithConstraints(modifier.testTag("incomplete-learning-artwork"), contentAlignment = Alignment.Center) {
        val scale = minOf(maxWidth / 1312f, maxHeight / 1199f)
        val width = scale * 1312f
        val height = scale * 1199f
        Box(Modifier.width(width).height(height)) {
            Image(painterResource(R.drawable.rl_incomplete_learning_book_collage_v2), null,
                Modifier.matchParentSize().testTag("incomplete-learning-book-image"), contentScale = ContentScale.Fit)
            Column(Modifier.offset(x = scale * 650f, y = scale * 310f).width(scale * 430f).height(scale * 690f)
                .clipToBounds().testTag("incomplete-book-statistics"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(count?.toString() ?: "—", Modifier.fillMaxWidth(), color = Color.White, fontFamily = EditorialFont.Serif,
                    fontSize = 26.sp, lineHeight = 34.sp, textAlign = TextAlign.Center)
                Text(if (count == null) "数量待确认" else "进行中", Modifier.fillMaxWidth(), color = Color.White,
                    fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
            }
        }
    }
}
