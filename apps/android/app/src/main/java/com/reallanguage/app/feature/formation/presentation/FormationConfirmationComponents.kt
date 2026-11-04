package com.reallanguage.app.feature.formation.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.EditorialFont
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlSecondaryButton
import com.reallanguage.app.core.designsystem.EditorialCompactAction
import androidx.compose.foundation.layout.offset

/** One chapter marker for the whole formation flow: centered text in one stable paper slot. */
@Composable
internal fun FormationChapterBadge(
    text: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    renderPaper: Boolean = true,
) {
    Box(
        modifier = modifier
            .width(if (compact) 94.dp else 112.dp)
            .height(if (compact) 42.dp else 46.dp)
            .testTag("formation_chapter_badge"),
        contentAlignment = Alignment.Center,
    ) {
        if (renderPaper) {
            Image(
                painter = painterResource(R.drawable.rl_torn_blue),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
        }
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp),
            color = Color.White,
            fontFamily = EditorialFont.Serif,
            fontWeight = FontWeight.Medium,
            fontSize = if (compact) 15.sp else 17.sp,
            lineHeight = if (compact) 20.sp else 23.sp,
        )
    }
}

/** These bounds refer to the blue paper in the full-page formation artwork,
 * not to the title's layout box. Keep text centered when the page changes size. */
@Composable
internal fun FormationFrameChapterBadge(text: String, pageWidth: Dp, pageHeight: Dp) {
    BoxWithConstraints(
        Modifier.offset(x = pageWidth * .723f, y = pageHeight * .028f)
            .width(pageWidth * .099f).height(pageHeight * .095f),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, Modifier.testTag("formation_chapter_badge"), color = Color.White,
            fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Medium,
            fontSize = (maxWidth.value / 4.6f).coerceAtMost(17f).sp,
            maxLines = 1, textAlign = TextAlign.Center)
    }
}

/** Consequential confirmation pages use equal actions on one baseline. */
@Composable
internal fun FormationActionPair(
    secondaryText: String,
    secondaryEnabled: Boolean,
    onSecondary: () -> Unit,
    primaryText: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryTag: String? = null,
    primaryTag: String? = null,
) {
    Row(
        modifier = modifier.heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditorialCompactAction(
            text = secondaryText,
            enabled = secondaryEnabled,
            onClick = onSecondary,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                .then(if (secondaryTag == null) Modifier else Modifier.testTag(secondaryTag)),
        )
        EditorialCompactAction(
            text = primaryText,
            enabled = primaryEnabled,
            onClick = onPrimary,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                .then(if (primaryTag == null) Modifier else Modifier.testTag(primaryTag)),
            primary = true,
        )
    }
}
