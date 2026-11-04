package com.reallanguage.app.feature.knowledge.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowUp
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.EditorialFont
import com.reallanguage.app.core.designsystem.RlColor

/** Decorative layers only: native text, actions and reading order remain outside the bitmap. */
@Composable
internal fun PatternDetailPaper(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.border(.5.dp, RlColor.Border.copy(alpha = .6f))) {
        Image(painterResource(R.drawable.rl_vocabulary_card_paper_r11), null,
            Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        content()
    }
}

@Composable
internal fun PatternDetailSectionTitle(text: String) {
    val verticalPadding = if (LocalDensity.current.fontScale > 1.3f) 4.dp else 1.dp
    Box(contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.rl_olive_torn_paper), null,
            Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = verticalPadding), color = Color.White,
            fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
internal fun PatternDetailAudioIcon(description: String) {
    Box(Modifier.size(26.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
        Image(patternDetailTexture(false), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        Icon(Icons.Outlined.VolumeUp, description, Modifier.size(17.dp), tint = Color.White)
    }
}

@Composable
internal fun PatternDetailStatistic(text: String, icon: ImageVector, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = color)
        Text(text, Modifier.weight(1f), color = color, fontFamily = EditorialFont.Serif,
            fontSize = 13.sp, lineHeight = 20.sp)
    }
}

@Composable
internal fun PatternDetailOccurrenceStatistic(text: String) =
    PatternDetailStatistic(text, Icons.Outlined.Visibility, RlColor.TextSecondary)

@Composable
internal fun PatternDetailAction(text: String, destructive: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).widthIn(min = 112.dp), propagateMinConstraints = true) {
        Image(patternDetailTexture(destructive), null,
            Modifier.matchParentSize().clip(RoundedCornerShape(3.dp)), contentScale = ContentScale.FillBounds)
        Button(onClick, modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(3.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.White),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
            Icon(if (destructive) Icons.Outlined.RemoveCircleOutline else Icons.Outlined.KeyboardDoubleArrowUp,
                null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, color = Color.White, fontFamily = EditorialFont.Serif,
                fontSize = 16.sp, lineHeight = 24.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun patternDetailTexture(terracotta: Boolean): BitmapPainter {
    val paper = ImageBitmap.imageResource(if (terracotta) R.drawable.rl_torn_terracotta else R.drawable.rl_olive_torn_paper)
    // Sample only an existing blank material's inner texture, never a screenshot or UI text.
    return BitmapPainter(paper,
        srcOffset = IntOffset((paper.width * .18f).toInt(), (paper.height * .2f).toInt()),
        srcSize = IntSize((paper.width * .56f).toInt(), (paper.height * .6f).toInt()))
}
