package com.reallanguage.app.feature.auth.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlPaperBackground

/** The artwork and the editable sheet reflow as modules; text is never scaled as a bitmap. */
@Composable
internal fun AuthResponsivePage(modifier: Modifier = Modifier, leftFraction: Float = .34f,
    sheetModifier: Modifier = Modifier,
    sheetArtwork: @Composable BoxScope.(Boolean) -> Unit = {},
    illustration: @Composable (Modifier) -> Unit, body: @Composable ColumnScope.(Boolean) -> Unit) {
    RlPaperBackground(modifier, showCornerCollage = false) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val shortLandscape = maxHeight < 390.dp && maxWidth > maxHeight * 1.35f
            val wide = maxWidth >= 600.dp && !shortLandscape &&
                maxWidth > maxHeight * 1.35f && LocalDensity.current.fontScale <= 1.35f
            val panelHeight = (maxHeight - 16.dp).coerceAtLeast(300.dp)
            if (wide) Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                illustration(Modifier.weight(leftFraction).height(panelHeight).zIndex(1f))
                AuthResponsiveSheet(Modifier.weight(1f - leftFraction).heightIn(min = panelHeight).then(sheetModifier), true, sheetArtwork, body)
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val illustrationHeight = if (shortLandscape) 168.dp else 360.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                illustration(Modifier.fillMaxWidth().height(illustrationHeight))
                AuthResponsiveSheet(Modifier.fillMaxWidth().then(sheetModifier), false, sheetArtwork, body)
            }
        }
    }
}

@Composable
private fun AuthResponsiveSheet(modifier: Modifier, wide: Boolean, sheetArtwork: @Composable BoxScope.(Boolean) -> Unit, body: @Composable ColumnScope.(Boolean) -> Unit) {
    Box(modifier) {
        Image(painterResource(R.drawable.rl_vocabulary_card_paper_r11), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Image(painterResource(R.drawable.rl_editorial_top_right_collage), null, Modifier.align(Alignment.TopEnd).width(if (wide) 118.dp else 75.dp).height(48.dp), contentScale = ContentScale.Fit)
        Image(painterResource(R.drawable.rl_editorial_bottom_right_grid_botanical), null, Modifier.align(Alignment.BottomEnd).width(if (wide) 110.dp else 65.dp).height(if (wide) 100.dp else 60.dp), contentScale = ContentScale.Fit)
        sheetArtwork(wide)
        androidx.compose.material3.ProvideTextStyle(androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp, lineHeight = 20.sp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = if (wide) 42.dp else 22.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) { body(wide) }
        }
    }
}
