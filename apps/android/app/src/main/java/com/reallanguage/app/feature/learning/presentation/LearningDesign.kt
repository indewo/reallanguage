package com.reallanguage.app.feature.learning.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.reallanguage.app.core.designsystem.EditorialFont
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPaperBackground

/** The active learning flow supplies one exit action to every learning header.
 * Keeping it in the header prevents a route-level overlay from drifting over
 * titles, status bars, or sentence content on differently sized phones. */
val LocalLearningExitAction = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Feature-local primitives. Deliberately do not change the protected home/diary theme. */
@Composable
internal fun LearningPage(
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.() -> Unit,
) {
  LearningTypography {
    RlPaperBackground(showCornerCollage = false) {
        BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)
            .windowInsetsPadding(WindowInsets.navigationBars), content = content)
    }
  }
}

/** A rectangular torn sheet has a real opaque text area; the irregular collage
 * scrap's deep transparent notches must never be used underneath white copy. */
@Composable
internal fun LearningOliveNote(
    modifier: Modifier = Modifier,
    contentVerticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier) {
        Image(painterResource(R.drawable.rl_vocabulary_card_paper_r11), null,
            Modifier.matchParentSize(), contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(Color(0xFF626C50), BlendMode.Modulate))
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 24.dp),
            verticalArrangement = contentVerticalArrangement, content = content)
    }
}

@Composable
internal fun LearningArchivePage(
    modifier: Modifier = Modifier,
    startFraction: Float = .19f,
    endFraction: Float = .09f,
    backgroundRes: Int = R.drawable.rl_v73_learning_archive_background,
    content: @Composable BoxWithConstraintsScope.() -> Unit,
) {
  LearningTypography {
    Box(modifier.fillMaxSize()) {
        Image(painterResource(backgroundRes), null,
            Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)
            .windowInsetsPadding(WindowInsets.navigationBars)) {
            val inset = if (maxWidth < 600.dp) 18.dp else maxWidth * startFraction
            BoxWithConstraints(Modifier.fillMaxSize().padding(start = inset, end = maxWidth * endFraction, top = 15.dp, bottom = 15.dp), content = content)
        }
    }
  }
}

/** Apply the editorial typeface only inside learning/review; protected entry pages stay unchanged. */
@Composable
private fun LearningTypography(content: @Composable () -> Unit) {
    val base = MaterialTheme.typography
    val serif = EditorialFont.Serif
    MaterialTheme(typography = base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = serif),
        displayMedium = base.displayMedium.copy(fontFamily = serif),
        displaySmall = base.displaySmall.copy(fontFamily = serif),
        headlineLarge = base.headlineLarge.copy(fontFamily = serif),
        headlineMedium = base.headlineMedium.copy(fontFamily = serif),
        headlineSmall = base.headlineSmall.copy(fontFamily = serif),
        titleLarge = base.titleLarge.copy(fontFamily = serif),
        titleMedium = base.titleMedium.copy(fontFamily = serif),
        titleSmall = base.titleSmall.copy(fontFamily = serif),
        bodyLarge = base.bodyLarge.copy(fontFamily = serif),
        bodyMedium = base.bodyMedium.copy(fontFamily = serif),
        bodySmall = base.bodySmall.copy(fontFamily = serif),
        labelLarge = base.labelLarge.copy(fontFamily = serif),
        labelMedium = base.labelMedium.copy(fontFamily = serif),
        labelSmall = base.labelSmall.copy(fontFamily = serif),
    ), content = content)
}

@Composable
internal fun LearningHeading(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onExit: (() -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val exitAction = onExit ?: LocalLearningExitAction.current
        val hasHeaderActions = trailing != null || exitAction != null
        val actionSlotWidth = when {
            trailing != null && exitAction != null -> 232.dp
            exitAction != null -> 132.dp
            trailing != null -> 108.dp
            else -> 48.dp
        }
        val stacked = hasHeaderActions && maxWidth < 560.dp * LocalDensity.current.fontScale
        val headerActions: (@Composable () -> Unit)? = if (hasHeaderActions) {{
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                trailing?.invoke()
                exitAction?.let { LearningHeaderExitAction(it) }
            }
        }} else null
        Column(Modifier.fillMaxWidth().testTag("learning-heading")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.width(if (stacked) 48.dp else actionSlotWidth),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    onBack?.let { callback -> IconButton(onClick = callback) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = RlColor.Ink, modifier = Modifier.size(25.dp))
                    } }
                }
                Column(Modifier.weight(1f).padding(vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, Modifier.testTag("learning-heading-title"), color = RlColor.Ink, fontFamily = EditorialFont.Serif,
                        fontWeight = FontWeight.Bold, fontSize = 23.sp, lineHeight = 29.sp, textAlign = TextAlign.Center)
                    Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null,
                        Modifier.width(140.dp).height(7.dp), contentScale = ContentScale.FillBounds)
                }
                if (!stacked && headerActions != null) Box(
                    Modifier.width(actionSlotWidth),
                    contentAlignment = Alignment.CenterEnd,
                ) { headerActions() }
                else Spacer(Modifier.width(48.dp))
            }
            if (stacked && headerActions != null) Box(
                Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { headerActions() }
        }
    }
}

@Composable
private fun LearningHeaderExitAction(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(36.dp).widthIn(min = 116.dp)
            .testTag("learning-header-exit"),
        shape = RoundedCornerShape(5.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, RlColor.Primary.copy(alpha = .72f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = RlColor.Surface.copy(alpha = .82f),
            contentColor = RlColor.Primary,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.learning_exit_open),
            fontFamily = EditorialFont.Serif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
internal fun LearningPaper(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    ruled: Boolean = false,
    tint: Color? = null,
    contentSpacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier) {
        Image(painterResource(if (ruled) R.drawable.rl_ruled_handwriting_paper else R.drawable.rl_vocabulary_card_paper_r11),
            null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds,
            colorFilter = tint?.let(ColorFilter::tint))
        Column(Modifier.fillMaxWidth().padding(padding), verticalArrangement = Arrangement.spacedBy(contentSpacing), content = content)
    }
}

@Composable
internal fun learningLabelPainter() = BitmapPainter(
    ImageBitmap.imageResource(R.drawable.rl_torn_heading_label),
    // The original 2000×744 bitmap has transparent top/bottom gutters; use the
    // actual paper instead of shrinking its opaque centre underneath white text.
    srcOffset = IntOffset(60, 132), srcSize = IntSize(1930, 476),
)

@Composable
internal fun LearningRibbon(text: String, modifier: Modifier = Modifier, color: Color = RlColor.Secondary, textModifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Image(learningLabelPainter(), null, Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.tint(color))
        Text(text, textModifier.padding(horizontal = 12.dp, vertical = 4.dp), color = Color.White,
            fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun LearningAction(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, primary: Boolean = false, color: Color = RlColor.ActionVivid,
) {
    com.reallanguage.app.core.designsystem.EditorialCompactAction(
        text, onClick, modifier, enabled, primary, color)

}

@Composable
internal fun LearningSeal(icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(34.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = Color.White)
    }
}

/** At small widths the illustration becomes a short horizontal block, not a squeezed column. */
@Composable
internal fun LearningTwoPane(
    modifier: Modifier = Modifier,
    leftWeight: Float = .36f,
    contentFirstWhenStacked: Boolean = false,
    minimumTwoPaneWidth: Dp = 600.dp,
    fillAvailableHeight: Boolean = false,
    left: @Composable (Modifier) -> Unit,
    right: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier) {
        if (maxWidth < minimumTwoPaneWidth * LocalDensity.current.fontScale.coerceAtMost(1.5f)) {
            Column(if (fillAvailableHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (contentFirstWhenStacked) {
                    right(Modifier.fillMaxWidth())
                    left(Modifier.fillMaxWidth())
                } else {
                    left(Modifier.fillMaxWidth())
                    right(Modifier.fillMaxWidth())
                }
            }
        } else Row(if (fillAvailableHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            left(Modifier.weight(leftWeight))
            right(Modifier.weight(1f - leftWeight))
        }
    }
}
