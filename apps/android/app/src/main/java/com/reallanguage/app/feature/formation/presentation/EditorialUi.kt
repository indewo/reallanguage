package com.reallanguage.app.feature.formation.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor

/** Decorative paper is measured behind real content, never used as a screenshot overlay. */
@Composable
internal fun EditorialSheet(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 20.dp,
    contentSpacing: Dp = 12.dp,
    contentArrangement: Arrangement.Vertical = Arrangement.spacedBy(contentSpacing),
    contentModifier: Modifier = Modifier,
    showSpine: Boolean = false,
    roundSpineHoles: Boolean = false,
    showTopAccent: Boolean = false,
    renderPaper: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier) {
        if (renderPaper) Image(painterResource(R.drawable.rl_vocabulary_card_paper_r11), null,
            Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        if (showSpine) Box(Modifier.matchParentSize().padding(vertical = 12.dp)) {
            if (roundSpineHoles) {
                Column(
                    Modifier.width(18.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    repeat(7) { index ->
                        Box(
                            Modifier.size(10.dp)
                                .background(Color(0xFFF1E7D5), CircleShape)
                                .border(0.8.dp, Color(0xFFCBBEA8), CircleShape)
                                .testTag("process_spine_hole_$index"),
                        )
                    }
                }
            } else {
                Image(painterResource(R.drawable.rl_focus_punch_holes_v2), null,
                    Modifier.width(12.dp).fillMaxHeight(), contentScale = ContentScale.FillBounds)
            }
        }
        if (showTopAccent) Image(painterResource(R.drawable.rl_vocabulary_beige_tape_r11), null,
            Modifier.align(Alignment.TopEnd).width(64.dp).height(20.dp), contentScale = ContentScale.FillBounds)
        Column(Modifier.fillMaxWidth().padding(contentPadding).then(contentModifier),
            verticalArrangement = contentArrangement, content = content)
    }
}

/** At enlarged text the editorial columns become independently measured stacked modules. */
@Composable
internal fun EditorialSplit(
    modifier: Modifier = Modifier,
    leftWeight: Float = .72f,
    rightWeight: Float = 1.28f,
    gap: Dp = 20.dp,
    left: @Composable (Modifier) -> Unit,
    right: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val stacked = maxWidth < 620.dp || LocalDensity.current.fontScale > 1.35f
        if (stacked) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(gap)) {
                left(Modifier.fillMaxWidth().height(240.dp))
                right(Modifier.fillMaxWidth().heightIn(min = 360.dp))
            }
        } else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            left(Modifier.weight(leftWeight).fillMaxHeight())
            right(Modifier.weight(rightWeight).fillMaxHeight())
        }
    }
}

@Composable
internal fun EditorialHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        if (onBack != null) com.reallanguage.app.core.designsystem.EditorialPageHeader(title, onBack)
        else Text(title, Modifier.align(Alignment.CenterHorizontally), fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 22.sp)
        subtitle?.let { Text(it, Modifier.fillMaxWidth(), color = RlColor.TextSecondary,
            fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center) }
    }
}
