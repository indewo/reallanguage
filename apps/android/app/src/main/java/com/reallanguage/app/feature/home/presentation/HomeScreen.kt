package com.reallanguage.app.feature.home.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlFont
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.RlSpace
import com.reallanguage.app.core.designsystem.RlPaperBackground

@Composable
fun HomeScreen(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
    recordingContent: (@Composable () -> Unit)? = null,
) {
    RlPaperBackground(modifier, showCornerCollage = false) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().safeDrawingPadding()
                .padding(horizontal = 28.dp, vertical = 12.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            val footerHeight = (maxHeight * .14f).coerceIn(48.dp, 64.dp)
            val sectionGap = (maxHeight * .018f).coerceIn(6.dp, 8.dp)
            val cardGap = (maxHeight * .022f).coerceIn(6.dp, 10.dp)
            val columnGap = (maxWidth * .012f).coerceIn(12.dp, 18.dp)
            Column(
                modifier = Modifier.widthIn(max = 1440.dp).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(sectionGap),
            ) {
            HomeHeader(onAction)
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(columnGap),
            ) {
                Column(
                    modifier = Modifier.weight(2.32f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(cardGap),
                ) {
                    HomeFeatureCard(
                        label = stringResource(R.string.home_diary),
                        supportingText = stringResource(R.string.home_diary_supporting),
                        image = R.drawable.home_diary_collage_v2,
                        paper = R.drawable.rl_torn_terracotta,
                        paperFilter = ColorFilter.tint(Color(0xFFC47458), BlendMode.Color),
                        topDecoration = R.drawable.rl_home_diary_scribble,
                        supportingDecoration = R.drawable.rl_home_white_underline_v2,
                        supportingDecorationTag = "home-diary-underline",
                        supportingDecorationWidth = 78.dp,
                        arrowColor = RlColor.ActionVivid,
                        titleSize = 36f,
                        onClick = { onAction(HomeAction.DiaryRequested) },
                        imageTranslationX = (-12).dp,
                        imageScaleX = 1.30f,
                        imageScaleY = 1.18f,
                        imageTranslationY = (-2).dp,
                        paperOffsetY = (-3).dp,
                        contentOffsetY = (-6).dp,
                        arrowTag = "home-diary-arrow",
                        modifier = Modifier.weight(1.1f).testTag("home-diary-card"),
                    )
                    HomeFeatureCard(
                        label = stringResource(R.string.home_ai_scene),
                        supportingText = stringResource(R.string.home_scene_supporting),
                        image = R.drawable.home_scene_collage_v2,
                        paper = R.drawable.rl_home_scene_torn_blue_transparent,
                        paperFilter = paperToneFilter(.75f, .71f, .66f),
                        cornerDecoration = R.drawable.rl_home_scene_paperclip,
                        cornerDecorationTag = "home-scene-paperclip",
                        supportingDecoration = R.drawable.rl_home_white_underline_v2,
                        supportingDecorationTag = "home-scene-underline",
                        supportingDecorationWidth = 90.dp,
                        arrowColor = RlColor.Primary,
                        titleSize = 32f,
                        onClick = { onAction(HomeAction.AiSceneRequested) },
                        imageScaleX = 1.24f,
                        imageScaleY = 1.19f,
                        imageTranslationX = 8.5.dp,
                        imageTranslationY = 0.dp,
                        paperScaleY = 1f,
                        paperOffsetY = 0.dp,
                        contentOffsetY = (-9).dp,
                        arrowTag = "home-scene-arrow",
                        modifier = Modifier.weight(1f).testTag("home-scene-card"),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(cardGap),
                ) {
                    HomeTaskCard(
                        title = stringResource(R.string.home_due_review),
                        accent = RlColor.ActionVivid,
                        actionLabel = stringResource(R.string.home_start_review),
                        decoration = R.drawable.rl_home_review_pin,
                        decorationTag = "home-review-pin",
                        decorationWidth = 20.dp,
                        decorationHeight = 23.dp,
                        decorationAlignment = Alignment.TopCenter,
                        decorationOffsetY = (-0.5).dp,
                        actionTag = "home-review-action",
                        paperTag = "home-review-paper",
                        onClick = { onAction(HomeAction.DueReviewRequested) },
                        modifier = Modifier.weight(1f).testTag("home-review-card"),
                    )
                    HomeTaskCard(
                        title = stringResource(R.string.home_incomplete_learning),
                        accent = RlColor.Secondary,
                        actionLabel = stringResource(R.string.settings_continue),
                        decoration = R.drawable.rl_home_incomplete_green_tape,
                        decorationTag = "home-incomplete-green-tape",
                        decorationWidth = 54.dp,
                        decorationHeight = 30.dp,
                        decorationAlignment = Alignment.TopEnd,
                        decorationOffsetX = 7.dp,
                        decorationOffsetY = (-4).dp,
                        actionTag = "home-incomplete-action",
                        paperTag = "home-incomplete-paper",
                        onClick = { onAction(HomeAction.IncompleteLearningRequested) },
                        modifier = Modifier.weight(1f).testTag("home-incomplete-card"),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(footerHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeKnowledgeEntry(
                    label = stringResource(R.string.home_vocabulary),
                    art = R.drawable.rl_home_vocabulary_book_v4,
                    artWidth = 45.dp,
                    artHeight = 41.dp,
                    onClick = { onAction(HomeAction.VocabularyRequested) },
                    height = footerHeight,
                    artTag = "home-vocabulary-art",
                    ruleTag = "home-vocabulary-rule",
                    modifier = Modifier.weight(1.05f),
                )
                HomeFooterDivider("home-footer-divider-left", footerHeight)
                if (recordingContent == null) {
                    HomeCompactAction(
                        label = stringResource(R.string.home_quick_recording),
                        color = RlColor.ActionVivid,
                        onClick = { onAction(HomeAction.QuickRecordingRequested) },
                        height = footerHeight,
                        modifier = Modifier.weight(1.25f).testTag("home-quick-recording"),
                    )
                } else {
                    Spacer(Modifier.weight(1.25f))
                }
                HomeFooterDivider("home-footer-divider-right", footerHeight)
                HomeKnowledgeEntry(
                    label = stringResource(R.string.home_sentence_patterns),
                    art = R.drawable.rl_home_pattern_book_v2,
                    artWidth = 38.dp,
                    artHeight = 42.dp,
                    onClick = { onAction(HomeAction.SentencePatternsRequested) },
                    height = footerHeight,
                    artTag = "home-pattern-art",
                    ruleTag = "home-pattern-rule",
                    modifier = Modifier.weight(.97f),
                )
            }
            }
            if (recordingContent != null) {
                Box(
                    modifier = Modifier.align(Alignment.BottomCenter).width(250.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) { recordingContent() }
            }
        }
    }
}

@Composable
private fun HomeHeader(onAction: (HomeAction) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 700.dp
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                color = RlColor.Ink,
                style = if (compact) {
                    MaterialTheme.typography.titleLarge.copy(fontFamily = RlFont.Brand, fontSize = 26.sp)
                } else {
                    MaterialTheme.typography.displaySmall.copy(
                        fontFamily = RlFont.Brand,
                        fontSize = 37.sp,
                        lineHeight = 43.sp,
                        fontWeight = FontWeight.Bold,
                    )
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else RlSpace.Space4)) {
                HeaderAction(stringResource(R.string.home_messages), Icons.Outlined.ChatBubbleOutline, compact, 8.dp) {
                    onAction(HomeAction.MessagesRequested)
                }
                HeaderAction(stringResource(R.string.home_settings), Icons.Outlined.Settings, compact, 11.dp) {
                    onAction(HomeAction.SettingsRequested)
                }
                HeaderAction(stringResource(R.string.home_account), Icons.Outlined.AccountCircle, compact) {
                    onAction(HomeAction.AccountRequested)
                }
            }
        }
    }
}

@Composable
private fun HeaderAction(
    label: String,
    icon: ImageVector,
    compact: Boolean,
    endPaddingExtra: Dp = 0.dp,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(
            start = if (compact) 4.dp else 12.dp,
            end = if (compact) 4.dp else 12.dp + endPaddingExtra,
            top = 0.dp,
            bottom = 0.dp,
        ),
        colors = ButtonDefaults.textButtonColors(contentColor = RlColor.TextPrimary),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(if (compact) 14.dp else 18.dp),
            tint = RlColor.Ink,
        )
        Spacer(Modifier.width(if (compact) 2.dp else RlSpace.Space4))
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 11.sp else 14.sp)
    }
}

@Composable
private fun HomeFeatureCard(
    label: String,
    supportingText: String,
    image: Int,
    paper: Int,
    paperFilter: ColorFilter,
    arrowColor: Color,
    titleSize: Float,
    onClick: () -> Unit,
    topDecoration: Int? = null,
    cornerDecoration: Int? = null,
    cornerDecorationTag: String? = null,
    supportingDecoration: Int? = null,
    supportingDecorationTag: String? = null,
    supportingDecorationWidth: Dp = 88.dp,
    imageScaleX: Float = 1.15f,
    imageScaleY: Float = 1.15f,
    imageTranslationX: Dp = 0.dp,
    imageTranslationY: Dp = 0.dp,
    paperScaleY: Float = 1f,
    paperOffsetY: Dp = 0.dp,
    contentOffsetY: Dp = 0.dp,
    arrowTag: String,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        val compact = maxHeight < 120.dp
        val desiredArrowOffset = maxHeight * .18f
        val containedArrowOffset = ((maxHeight - 41.dp) / 2f - 4.dp).coerceAtLeast(0.dp)
        val responsiveArrowOffset = minOf(desiredArrowOffset, containedArrowOffset)
        Image(
            painter = painterResource(paper),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().offset(y = if (compact) 0.dp else paperOffsetY)
                .graphicsLayer { scaleY = if (compact) 1f else paperScaleY },
            contentScale = ContentScale.FillBounds,
            colorFilter = paperFilter,
        )
        if (topDecoration != null) {
            Image(
                painter = painterResource(topDecoration),
                contentDescription = null,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = (-15).dp)
                    .width(110.dp).height(30.dp).testTag("home-diary-scribble"),
                contentScale = ContentScale.Fit,
            )
        }
        if (cornerDecoration != null) {
            Image(
                painter = painterResource(cornerDecoration),
                contentDescription = null,
                modifier = Modifier.align(Alignment.TopEnd)
                    .offset(x = (-22).dp, y = (-8).dp)
                    .width(20.dp).height(48.dp)
                    .then(
                        if (cornerDecorationTag != null) Modifier.testTag(cornerDecorationTag)
                        else Modifier,
                    ),
                contentScale = ContentScale.Fit,
            )
        }
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = RlSpace.Space16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(image),
                contentDescription = null,
                modifier = Modifier.weight(1f).fillMaxHeight().graphicsLayer {
                    scaleX = imageScaleX
                    scaleY = imageScaleY
                    translationX = imageTranslationX.toPx()
                    translationY = imageTranslationY.toPx()
                },
                contentScale = ContentScale.Fit,
            )
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = RlSpace.Space12)
                    .offset(y = if (compact) 0.dp else contentOffsetY),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = label,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = (if (compact) 24f else titleSize).sp,
                        lineHeight = (if (compact) 27f else titleSize + 3f).sp,
                    ),
                )
                Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
                Box {
                    Text(
                        text = supportingText,
                        color = Color.White.copy(alpha = 0.88f),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = if (compact) 12.sp else 14.sp,
                            lineHeight = if (compact) 16.sp else 20.sp,
                        ),
                    )
                    if (supportingDecoration != null) {
                        Image(
                            painter = painterResource(supportingDecoration),
                            contentDescription = null,
                            modifier = Modifier.align(Alignment.BottomStart)
                                .offset(y = if (compact) 8.dp else 18.dp)
                                .width(if (compact) supportingDecorationWidth * .72f else supportingDecorationWidth)
                                .height(if (compact) 5.dp else 7.dp)
                                .then(
                                    if (supportingDecorationTag != null) Modifier.testTag(supportingDecorationTag)
                                    else Modifier,
                                ),
                            contentScale = ContentScale.FillBounds,
                            colorFilter = ColorFilter.tint(Color.White.copy(alpha = .9f)),
                        )
                    }
                }
            }
            Surface(
                modifier = Modifier.size(41.dp).offset(y = responsiveArrowOffset).testTag(arrowTag),
                shape = CircleShape,
                color = RlColor.Surface,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_arrow_forward),
                    contentDescription = null,
                    modifier = Modifier.padding(RlSpace.Space8),
                    colorFilter = ColorFilter.tint(arrowColor),
                )
            }
        }
    }
}

@Composable
private fun HomeTaskCard(
    title: String,
    accent: Color,
    actionLabel: String,
    decoration: Int,
    decorationTag: String,
    decorationWidth: Dp,
    decorationHeight: Dp,
    decorationAlignment: Alignment,
    decorationOffsetX: Dp = 0.dp,
    decorationOffsetY: Dp = 0.dp,
    actionTag: String,
    paperTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        val compact = maxHeight < 130.dp || maxWidth < 225.dp
        val horizontalInset = (maxWidth * .055f).coerceIn(12.dp, 20.dp)
        val verticalInset = (maxHeight * .085f).coerceIn(6.dp, 14.dp)
        val contentGap = (maxHeight * .035f).coerceIn(3.dp, 8.dp)
        val actionHeight = (maxHeight * .235f).coerceIn(32.dp, 42.dp)
        val iconContainerSize = (maxHeight * .225f).coerceIn(24.dp, 40.dp)
        val iconSize = (iconContainerSize * .65f).coerceIn(18.dp, 26.dp)
        // 5px at the 320dpi reference equals 2.5dp. Basing the nudge on the
        // current card height preserves that relationship across screen sizes.
        val paperVerticalNudge = (maxHeight * .0125f).coerceIn(2.dp, 3.dp)
        Image(
            painter = painterResource(R.drawable.rl_home_task_note_adaptive),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                // The source paper has a soft transparent torn-edge inset. Scale from
                // the top by a proportion so its visible lower edge reaches the shared
                // card baseline on every screen ratio without a device-specific offset.
                scaleY = 1.035f
                transformOrigin = TransformOrigin(.5f, 0f)
                translationY = paperVerticalNudge.toPx()
            }.testTag(paperTag),
            contentScale = ContentScale.FillBounds,
            colorFilter = paperToneFilter(.955f, .932f, .912f),
        )
        Image(
            painter = painterResource(decoration),
            contentDescription = null,
            modifier = Modifier.align(decorationAlignment)
                .offset(x = decorationOffsetX, y = decorationOffsetY + paperVerticalNudge)
                .width(if (compact) decorationWidth * .72f else decorationWidth)
                .height(if (compact) decorationHeight * .72f else decorationHeight)
                .testTag(decorationTag),
            contentScale = ContentScale.Fit,
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(
                start = horizontalInset,
                end = horizontalInset,
                top = verticalInset,
                bottom = verticalInset,
            ),
            verticalArrangement = Arrangement.spacedBy(contentGap),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(iconContainerSize).background(accent, RlShape.Small),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (accent == RlColor.ActionVivid) {
                            Icons.Outlined.CalendarMonth
                        } else {
                            Icons.Outlined.Assignment
                        },
                        contentDescription = null,
                        modifier = Modifier.size(iconSize),
                        tint = Color.White,
                    )
                }
                Text(
                    title,
                    modifier = Modifier.padding(start = if (compact) 6.dp else RlSpace.Space12),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Serif,
                        fontSize = if (compact) 14.sp else 17.sp,
                        lineHeight = if (compact) 20.sp else 24.sp,
                    ),
                    color = RlColor.Ink,
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(
                modifier = Modifier.fillMaxWidth().height(actionHeight)
                    .background(accent, RlShape.Small).testTag(actionTag),
                contentAlignment = Alignment.Center,
            ) {
                Text(actionLabel, color = Color.White, fontSize = if (compact) 14.sp else 17.sp,
                    lineHeight = if (compact) 20.sp else 24.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun HomeCompactAction(
    label: String,
    color: Color,
    onClick: () -> Unit,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val sizeProgress = ((height.value - 48f) / 16f).coerceIn(0f, 1f)
    val tapeWidth = (36f + 8f * sizeProgress).dp
    val tapeHeight = (12f + 3f * sizeProgress).dp
    val surfaceTopInset = tapeHeight * .34f
    val tapeTopInset = tapeHeight * .25f
    val micSize = (34f + 8f * sizeProgress).dp
    val micIconSize = (20f + 4f * sizeProgress).dp
    val waveformWidth = (82f + 10f * sizeProgress).dp
    val waveformWindowHeight = (24f + 4f * sizeProgress).dp
    val waveformSourceHeight = (54f + 30f * sizeProgress).dp
    Box(
        modifier = modifier.padding(start = 16.dp, end = 16.dp).fillMaxWidth()
            .height(height),
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxSize().padding(top = surfaceTopInset)
                .testTag("home-recorder-surface"),
            shape = RoundedCornerShape(50),
            color = Color(0xFFEDE3D7),
            shadowElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(start = 19.dp, end = RlSpace.Space16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(micSize)
                        .background(color, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(micIconSize),
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.width(17.5.dp))
                Text(
                    label,
                    color = RlColor.Ink,
                    fontFamily = FontFamily.Serif,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .width(waveformWidth)
                        .height(waveformWindowHeight)
                        .clipToBounds()
                        .testTag("home-recorder-wave-window"),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Image(
                        painter = painterResource(R.drawable.rl_home_quick_recording_waveform),
                        contentDescription = null,
                        modifier = Modifier
                            .width(waveformWidth)
                            .height(waveformSourceHeight)
                            .testTag("home-recorder-wave-source"),
                        contentScale = ContentScale.FillBounds,
                    )
                }
            }
        }
        Image(
            painter = painterResource(R.drawable.rl_home_quick_recording_tape),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.align(Alignment.TopEnd).offset(y = tapeTopInset).width(tapeWidth)
                .height(tapeHeight)
                .graphicsLayer {
                    rotationZ = 25f
                    translationX = -10.dp.toPx()
                }
                .testTag("home-recorder-tape"),
        )
    }
}

@Composable
private fun HomeKnowledgeEntry(
    label: String,
    art: Int,
    artWidth: Dp,
    artHeight: Dp,
    onClick: () -> Unit,
    height: Dp,
    artTag: String,
    ruleTag: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(height),
        color = Color.Transparent,
    ) {
        Box(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxSize().padding(start = 9.dp, end = RlSpace.Space12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            Image(
                painter = painterResource(art),
                contentDescription = null,
                modifier = Modifier.width(artWidth).height(artHeight).testTag(artTag),
                contentScale = ContentScale.FillBounds,
            )
            Spacer(Modifier.width(20.dp))
            Text(
                label,
                color = RlColor.Ink,
                fontFamily = FontFamily.Serif,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.Outlined.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = RlColor.Ink,
            )
            }
            HomeDashedRule(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(horizontal = RlSpace.Space8).offset(y = (-12).dp)
                    .height(3.dp).testTag(ruleTag),
            )
        }
    }
}

@Composable
private fun HomeFooterDivider(tag: String, height: Dp) {
    Canvas(
        Modifier.width(24.dp).height(height).padding(horizontal = 11.dp, vertical = 4.dp)
            .testTag(tag),
    ) {
        drawLine(
            color = RlColor.TextSecondary.copy(alpha = .55f),
            start = androidx.compose.ui.geometry.Offset(size.width / 2f, 0f),
            end = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
        )
    }
}

@Composable
private fun HomeDashedRule(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawLine(
            color = RlColor.TextSecondary.copy(alpha = .55f),
            start = androidx.compose.ui.geometry.Offset(0f, size.height / 2f),
            end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2f),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
        )
    }
}

private fun paperToneFilter(red: Float, green: Float, blue: Float): ColorFilter =
    ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                red, 0f, 0f, 0f, 0f,
                0f, green, 0f, 0f, 0f,
                0f, 0f, blue, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
