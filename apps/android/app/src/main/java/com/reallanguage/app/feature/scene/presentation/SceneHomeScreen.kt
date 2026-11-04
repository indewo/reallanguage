package com.reallanguage.app.feature.scene.presentation

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.feature.scene.domain.PublicSceneTemplate
import com.reallanguage.app.feature.scene.domain.RecentSceneRecommendation
import com.reallanguage.app.feature.formation.presentation.EditorialHeader
import com.reallanguage.app.feature.formation.presentation.EditorialSheet

private val SceneNavy = Color(0xFF15324A)
private val SceneBlue = Color(0xFF5C788A)
private val SceneOlive = Color(0xFF6E7967)
private val SceneTerracotta = Color(0xFFB96043)
private val SceneMustard = Color(0xFFB68A48)

@Composable
fun SceneHomeScreen(state: SceneHomeUiState, onAction: (SceneHomeAction) -> Unit) =
    SceneHomeScreen(state, onAction, onBack = {})

/** Screen 67 is laid out from ratios measured on the approved 1831 x 859 source. */
@Composable
fun SceneHomeScreen(state: SceneHomeUiState, onAction: (SceneHomeAction) -> Unit, onBack: () -> Unit) {
    RlPaperBackground {
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal),
            ),
        ) {
            val largeText = LocalDensity.current.fontScale > 1.3f
            if (largeText || maxWidth < 700.dp) {
                AdaptiveSceneHome(state, onAction, onBack)
                return@BoxWithConstraints
            }
            val narrow = maxWidth / maxHeight < 1.95f
            val left = maxWidth * .024f
            val right = maxWidth * .022f
            val gap = maxWidth * .012f
            val usableWidth = maxWidth - left - right
            val leftWidth = if (narrow) usableWidth * .355f else usableWidth * .344f
            val rightWidth = usableWidth - leftWidth - gap

            IconButton(
                onClick = onBack,
                modifier = Modifier.offset(x = maxWidth * .012f, y = maxHeight * .020f).size(48.dp).testTag("scene-home-back"),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = SceneNavy, modifier = Modifier.size(if (narrow) 27.dp else 30.dp))
            }
            SceneTitle(
                Modifier.align(Alignment.TopCenter)
                    .width(if (narrow) maxWidth * .48f else maxWidth * .36f)
                    .height(maxHeight * .17f).testTag("scene-home-header"),
                narrow,
                largeText,
            )
            SceneEntryColumn(
                state,
                onAction,
                Modifier.offset(x = left, y = maxHeight * .112f)
                    .width(leftWidth).height(maxHeight * .840f),
                narrow,
                largeText,
            )
            SceneChoicesColumn(
                state,
                onAction,
                Modifier.offset(x = left + leftWidth + gap, y = maxHeight * .176f)
                    .width(rightWidth).height(maxHeight * .764f),
                narrow,
                largeText,
            )
        }
    }
}

/** Keep the collage language but let each ticket and section measure its actual text. */
@Composable
private fun AdaptiveSceneHome(state: SceneHomeUiState, onAction: (SceneHomeAction) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EditorialHeader(stringResource(R.string.scene_home_title), subtitle = "练习你真正会遇到的表达", onBack = onBack)
        EditorialSheet(Modifier.fillMaxWidth().clickable { onAction(SceneHomeAction.DirectDescriptionRequested) }.testTag("scene-home-direct-panel"), contentPadding = 22.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Image(painterResource(R.drawable.rl_scene_woman_v2), null, Modifier.width(64.dp).height(90.dp), contentScale = ContentScale.Fit)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("直接描述你的场景", color = SceneNavy, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
                    Text("例如：第一次和国外同事吃午饭", color = RlColor.TextSecondary, fontSize = 14.sp, lineHeight = 23.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("点击进入完整页描述", Modifier.weight(1f), color = SceneTerracotta, fontSize = 14.sp)
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowForward,
                            "选择内容类型",
                            tint = SceneTerracotta,
                            modifier = Modifier.testTag("scene-home-direct-entry"),
                        )
                    }
                }
            }
        }
        if (state.showRecentSection) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("最近推荐", color = SceneTerracotta, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                SceneStartPrompt(
                    largeText = false,
                    narrow = false,
                    adaptive = true,
                    modifier = Modifier.testTag("scene-home-recommendation-prompt"),
                )
            }
            state.recommendations.forEachIndexed { index, item ->
                AdaptiveSceneTicket(item.shortTopicZh, R.drawable.rl_scene_recent_ticket_sage_neutral_v3, listOf(R.drawable.rl_scene_recent_icon_review_v2, R.drawable.rl_scene_recent_icon_meeting_v2, R.drawable.rl_scene_recent_icon_restaurant_v2, R.drawable.rl_scene_recent_icon_luggage_v2)[index % 4]) {
                    onAction(SceneHomeAction.RecommendationSelected(item.recommendationId))
                }
            }
        }
        Text("公开场景模板", color = SceneBlue, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        state.templates.take(8).forEach { template ->
            val visual = templateVisual(template.templateId)
            AdaptiveSceneTicket(template.titleZh, visual.background, visual.icon) { onAction(SceneHomeAction.TemplateSelected(template.templateId)) }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AdaptiveSceneTicket(title: String, @DrawableRes background: Int, @DrawableRes icon: Int, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Image(painterResource(background), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Row(Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Image(painterResource(icon), null, Modifier.size(40.dp))
            Text(title, Modifier.weight(1f), color = SceneNavy, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 17.sp, lineHeight = 27.sp)
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = SceneNavy)
        }
    }
}

@Composable
private fun SceneTitle(modifier: Modifier, narrow: Boolean, largeText: Boolean) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.offset(y = if (largeText) 2.dp else 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(painterResource(R.drawable.rl_diary_botanical_sprig), null, Modifier.size(if (largeText) 18.dp else if (narrow) 24.dp else 27.dp))
            Text(
                stringResource(R.string.scene_home_title), color = SceneNavy,
                fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold,
                fontSize = if (largeText) 13.sp else if (narrow) 25.sp else 28.sp,
                lineHeight = if (largeText) 16.sp else if (narrow) 30.sp else 34.sp,
                maxLines = 1,
            )
            Image(painterResource(R.drawable.rl_diary_botanical_sprig), null, Modifier.size(if (largeText) 18.dp else if (narrow) 24.dp else 27.dp))
        }
        if (!largeText) Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            HorizontalDivider(Modifier.width(if (narrow) 23.dp else 30.dp), .6.dp, RlColor.BorderStrong)
            Text(
                "练习你真正会遇到的表达",
                color = RlColor.TextSecondary,
                fontSize = if (largeText) 6.sp else if (narrow) 10.sp else 12.sp,
                lineHeight = if (largeText) 8.sp else if (narrow) 12.sp else 15.sp,
                maxLines = 1,
            )
            HorizontalDivider(Modifier.width(if (narrow) 23.dp else 30.dp), .6.dp, RlColor.BorderStrong)
        }
    }
}

@Composable
private fun SceneEntryColumn(
    state: SceneHomeUiState,
    onAction: (SceneHomeAction) -> Unit,
    modifier: Modifier,
    narrow: Boolean,
    largeText: Boolean,
) {
    BoxWithConstraints(modifier) {
        val entryWidth = maxWidth
        val entryHeight = maxHeight
        Image(
            painterResource(R.drawable.rl_home_scene_torn_blue_transparent), null,
            Modifier.fillMaxWidth().height(maxHeight + 34.dp),
            contentScale = ContentScale.FillBounds,
        )
        Image(
            painterResource(R.drawable.rl_scene_notebook_spiral_v2), null,
            Modifier.align(Alignment.TopStart).offset(x = (-54).dp, y = 40.dp).width(110.dp).height(180.dp),
            contentScale = ContentScale.FillBounds,
        )
        Box(Modifier.fillMaxWidth().height(maxHeight * .635f).testTag("scene-home-artwork")) {
            Image(
                painterResource(R.drawable.rl_scene_handwritten_title_v2), "你想练什么？",
                Modifier.align(Alignment.TopStart)
                    .offset(x = if (narrow) 58.dp else 64.dp, y = if (narrow) 20.dp else 36.dp)
                    .width(if (narrow) 163.dp else 181.dp).height(if (narrow) 47.dp else 52.dp),
                contentScale = ContentScale.Fit,
            )
            Image(
                painterResource(R.drawable.rl_scene_travel_ephemera), null,
                Modifier.align(Alignment.BottomCenter).offset(y = 7.dp).fillMaxWidth().height(if (narrow) 86.dp else 96.dp)
                    .graphicsLayer { scaleY = 1.30f; transformOrigin = TransformOrigin(.5f, 1f) },
                contentScale = ContentScale.FillBounds,
            )
            Image(
                painterResource(R.drawable.rl_scene_left_botanical_v2), null,
                Modifier.offset(x = if (narrow) 5.dp else 8.dp, y = if (narrow) 76.dp else 88.dp)
                    .width(if (narrow) 54.dp else 62.dp).height(if (narrow) 102.dp else 118.dp),
                contentScale = ContentScale.Fit,
            )
            Image(
                painterResource(R.drawable.rl_scene_woman_v2), null,
                Modifier.offset(x = entryWidth * .163f, y = entryHeight * .258f)
                    .width(entryWidth * .294f).height(entryHeight * .310f),
                contentScale = ContentScale.Fit,
            )
            Image(
                painterResource(R.drawable.rl_scene_speech_v2), null,
                Modifier.offset(x = entryWidth * .421f, y = entryHeight * .283f)
                    .width(entryWidth * .199f).height(entryHeight * .156f),
                contentScale = ContentScale.Fit,
            )
            Image(
                painterResource(R.drawable.rl_scene_man_v2), null,
                Modifier.offset(x = entryWidth * .620f, y = entryHeight * .221f)
                    .width(entryWidth * .238f).height(entryHeight * .307f),
                contentScale = ContentScale.Fit,
            )
            Image(
                painterResource(R.drawable.rl_scene_travel_ticket_foreground_v2), null,
                Modifier.offset(x = entryWidth * .083f, y = entryHeight * .361f)
                    .width(entryWidth * .479f).height(entryHeight * .294f),
                contentScale = ContentScale.FillBounds,
            )
            Image(
                painterResource(R.drawable.rl_scene_airmail_foreground_v2), null,
                Modifier.offset(x = entryWidth * .631f, y = entryHeight * .366f)
                    .width(entryWidth * .369f).height(entryHeight * .289f),
                contentScale = ContentScale.FillBounds,
            )
            Image(
                painterResource(R.drawable.rl_scene_double_paperclip_v2), null,
                Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = 22.dp).width(52.dp).height(83.dp),
                contentScale = ContentScale.Fit,
            )
            Image(
                painterResource(R.drawable.rl_button_masking_tape), null,
                Modifier.align(Alignment.TopStart).offset(x = 18.dp, y = 15.dp).width(84.dp).height(27.dp),
                contentScale = ContentScale.FillBounds,
            )
        }
        SceneDirectDescription(
            onAction,
            Modifier.align(Alignment.BottomCenter).padding(start = 15.dp, end = 15.dp, bottom = if (narrow) 17.dp else 18.5.dp)
                .fillMaxWidth().height(if (narrow) 103.dp else 112.dp),
            narrow,
            largeText,
        )
    }
}

@Composable
private fun SceneDirectDescription(
    onAction: (SceneHomeAction) -> Unit,
    modifier: Modifier,
    narrow: Boolean,
    largeText: Boolean,
) {
    Box(
        modifier.testTag("scene-home-direct-panel")
            .clickable { onAction(SceneHomeAction.DirectDescriptionRequested) },
    ) {
        Image(painterResource(R.drawable.rl_ruled_handwriting_paper), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Column(Modifier.fillMaxSize().padding(start = 29.dp, end = if (narrow) 15.dp else 12.dp, top = 8.dp, bottom = 10.dp)) {
            Text(
                "直接描述你的场景", color = SceneNavy, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold,
                fontSize = if (largeText) 8.sp else if (narrow) 11.sp else 14.sp, maxLines = 1,
            )
            Spacer(Modifier.height(if (narrow) 2.dp else 4.dp))
            Text(
                "例如：第一次和国外同事吃午饭", color = RlColor.TextSecondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                fontSize = if (largeText) 6.sp else if (narrow) 8.sp else 11.sp, maxLines = 1,
                modifier = Modifier.offset(y = (-6).dp),
            )
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().requiredHeight(48.dp).offset(y = if (narrow) (-6).dp else (-4.5).dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "点击进入完整页描述",
                    color = RlColor.TextMuted,
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = if (largeText) 7.sp else if (narrow) 9.sp else 11.sp,
                    modifier = Modifier.weight(1f).padding(start = 12.dp).testTag("scene-home-direct-entry"),
                )
                Spacer(Modifier.width(7.dp))
                Box(
                    Modifier.requiredSize(48.dp).testTag("scene-home-continue"),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Surface(
                        color = Color.Transparent,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(7.dp),
                        modifier = Modifier.size(39.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Image(
                                painterResource(R.drawable.rl_torn_terracotta),
                                null,
                                Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, "选择内容类型", tint = Color.White, modifier = Modifier.size(25.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SceneChoicesColumn(
    state: SceneHomeUiState,
    onAction: (SceneHomeAction) -> Unit,
    modifier: Modifier,
    narrow: Boolean,
    largeText: Boolean,
) {
    Column(modifier) {
        if (state.showRecentSection) {
            RecommendationPanel(state, onAction, Modifier.fillMaxWidth().weight(.46f), narrow, largeText)
        } else {
            Spacer(Modifier.fillMaxWidth().weight(.46f))
        }
        Spacer(Modifier.height(4.dp))
        TemplatePanel(state.templates, onAction, Modifier.fillMaxWidth().weight(.54f), narrow, largeText)
    }
}

@Composable
private fun RecommendationPanel(
    state: SceneHomeUiState,
    onAction: (SceneHomeAction) -> Unit,
    modifier: Modifier,
    narrow: Boolean,
    largeText: Boolean,
) {
    Box(modifier) {
        Image(painterResource(R.drawable.rl_torn_note), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Column(Modifier.fillMaxSize().padding(start = 14.dp, end = if (narrow) 14.dp else 23.dp, top = 7.dp, bottom = 7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SceneSectionHeading("最近推荐", SceneTerracotta, Modifier.testTag("scene-home-recommendations"), largeText)
                Spacer(Modifier.weight(1f))
                SceneStartPrompt(
                    largeText = largeText,
                    narrow = narrow,
                    modifier = Modifier.testTag("scene-home-recommendation-prompt"),
                )
            }
            Spacer(Modifier.height(0.dp))
            Row(
                Modifier.fillMaxSize().testTag("scene-home-recommendation-grid"),
                horizontalArrangement = Arrangement.spacedBy(if (narrow) 5.dp else 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                repeat(4) { index ->
                    val item = state.recommendations.getOrNull(index)
                    if (item != null) {
                        RecentTicket(item, index, Modifier.weight(1f).aspectRatio(1.389f)
                            .testTag("scene-home-recommendation-$index"), largeText) {
                            onAction(SceneHomeAction.RecommendationSelected(item.recommendationId))
                        }
                    } else {
                        Spacer(Modifier.weight(1f).aspectRatio(1.389f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplatePanel(
    templates: List<PublicSceneTemplate>,
    onAction: (SceneHomeAction) -> Unit,
    modifier: Modifier,
    narrow: Boolean,
    largeText: Boolean,
) {
    Box(modifier) {
        Image(painterResource(R.drawable.rl_torn_note), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Column(Modifier.fillMaxSize().padding(start = 14.dp, end = if (narrow) 14.dp else 23.dp, top = 7.dp, bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SceneSectionHeading("公开场景模板", SceneBlue, Modifier.testTag("scene-home-templates"), largeText)
                Spacer(Modifier.weight(1f))
                SceneStartPrompt(
                    largeText = largeText,
                    narrow = narrow,
                    modifier = Modifier.testTag("scene-home-template-prompt"),
                )
            }
            Spacer(Modifier.height(if (narrow) 1.dp else 2.dp))
            SceneTemplateGrid(
                templates,
                onAction,
                Modifier.fillMaxSize().padding(top = if (narrow) 3.dp else 5.dp, bottom = if (narrow) 1.dp else 3.dp),
                narrow,
                largeText,
            )
        }
    }
}

@Composable
private fun SceneSectionLabel(text: String, color: Color, modifier: Modifier, largeText: Boolean) {
    Surface(color = color, shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp), modifier = modifier.height(24.dp)) {
        Box(Modifier.padding(horizontal = 17.dp), contentAlignment = Alignment.Center) {
            Text(
                text, color = Color.White, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                fontWeight = FontWeight.Bold, fontSize = if (largeText) 8.sp else 13.sp,
                lineHeight = if (largeText) 10.sp else 16.sp, maxLines = 1,
                modifier = Modifier.offset(y = (-1).dp),
            )
        }
    }
}

@Composable
private fun SceneSectionHeading(text: String, color: Color, modifier: Modifier, largeText: Boolean) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        SceneSectionLabel(text, color, Modifier, largeText)
        Image(
            painterResource(R.drawable.rl_home_diary_scribble), null,
            Modifier.padding(start = 7.dp).width(if (largeText) 48.dp else 70.dp).height(if (largeText) 10.dp else 16.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun SceneStartPrompt(largeText: Boolean, narrow: Boolean, modifier: Modifier = Modifier, adaptive: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("选择一个开始", color = SceneNavy, fontSize = if (adaptive) 14.sp else if (largeText) 7.sp else if (narrow) 8.sp else 10.sp)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Outlined.Check, null, tint = SceneNavy, modifier = Modifier.size(if (adaptive) 18.dp else if (narrow) 13.dp else 16.dp))
    }
}

@Composable
private fun RecentTicket(item: RecentSceneRecommendation, index: Int, modifier: Modifier, largeText: Boolean, onClick: () -> Unit) {
    val icons = listOf(
        R.drawable.rl_scene_recent_icon_review_v2,
        R.drawable.rl_scene_recent_icon_meeting_v2,
        R.drawable.rl_scene_recent_icon_restaurant_v2,
        R.drawable.rl_scene_recent_icon_luggage_v2,
    )
    val backgrounds = listOf(
        R.drawable.rl_scene_recent_ticket_sage_neutral_v3,
        R.drawable.rl_scene_recent_ticket_sand_v2,
        R.drawable.rl_scene_recent_ticket_terracotta_v2,
        R.drawable.rl_scene_recent_ticket_blue_v2,
    )
    Box(
        modifier.clickable(onClick = onClick).padding(1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(backgrounds[index % backgrounds.size]), null,
            Modifier.fillMaxSize().scale(1.18f),
            contentScale = ContentScale.FillBounds,
        )
        Column(
            Modifier.fillMaxSize().padding(horizontal = 9.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painterResource(icons[index % icons.size]), null,
                modifier = Modifier.size(if (largeText) 20.dp else 30.dp),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                item.shortTopicZh, color = SceneNavy, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Medium,
                fontSize = if (largeText) 6.2.sp else 9.5.sp, lineHeight = if (largeText) 7.5.sp else 11.sp,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SceneTemplateGrid(
    templates: List<PublicSceneTemplate>,
    onAction: (SceneHomeAction) -> Unit,
    modifier: Modifier,
    narrow: Boolean,
    largeText: Boolean,
) {
    val contentModifier = if (narrow && largeText) modifier.verticalScroll(rememberScrollState()) else modifier
    Column(contentModifier, verticalArrangement = Arrangement.spacedBy(if (narrow) 4.dp else 7.dp)) {
        templates.take(8).chunked(4).forEach { row ->
            val rowModifier = if (narrow && largeText) Modifier.fillMaxWidth().height(49.dp) else Modifier.fillMaxWidth().weight(1f)
            Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(if (narrow) 5.dp else 12.dp)) {
                row.forEach { template ->
                    TemplateTicket(template, Modifier.weight(1f).fillMaxHeight(), narrow, largeText) {
                        onAction(SceneHomeAction.TemplateSelected(template.templateId))
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun TemplateTicket(
    template: PublicSceneTemplate,
    modifier: Modifier,
    narrow: Boolean,
    largeText: Boolean,
    onClick: () -> Unit,
) {
    val visual = templateVisual(template.templateId)
    Box(
        modifier.heightIn(min = 48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(visual.background), null,
            Modifier.fillMaxSize().scale(1.18f),
            contentScale = ContentScale.FillBounds,
        )
        Row(
            Modifier.fillMaxSize().padding(
                horizontal = if (largeText) 4.dp else if (narrow) 7.dp else 8.dp,
                vertical = if (largeText) 4.dp else 7.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (largeText) 2.dp else if (narrow) 4.dp else 5.dp),
        ) {
            Image(
                painterResource(visual.icon), null,
                modifier = Modifier.size(if (largeText) 16.dp else if (narrow) 23.dp else 27.dp),
                contentScale = ContentScale.Fit,
            )
            Text(
                template.titleZh, color = SceneNavy, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Medium,
                fontSize = if (largeText) 5.2.sp else if (narrow) 8.sp else 9.5.sp,
                lineHeight = if (largeText) 6.5.sp else if (narrow) 9.5.sp else 11.5.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class TemplateVisual(@DrawableRes val background: Int, @DrawableRes val icon: Int)

private fun templateVisual(id: String): TemplateVisual = when (id) {
    "work" -> TemplateVisual(R.drawable.rl_scene_template_ticket_blue_v2, R.drawable.rl_scene_template_icon_work_v2)
    "learning" -> TemplateVisual(R.drawable.rl_scene_template_ticket_sage_v2, R.drawable.rl_scene_template_icon_learning_v2)
    "social" -> TemplateVisual(R.drawable.rl_scene_template_ticket_terracotta_v2, R.drawable.rl_scene_template_icon_social_v2)
    "planning" -> TemplateVisual(R.drawable.rl_scene_template_ticket_mustard_v2, R.drawable.rl_scene_template_icon_planning_v2)
    "travel" -> TemplateVisual(R.drawable.rl_scene_template_ticket_sage_v2, R.drawable.rl_scene_template_icon_travel_v2)
    "shopping" -> TemplateVisual(R.drawable.rl_scene_template_ticket_terracotta_v2, R.drawable.rl_scene_template_icon_restaurant_v2)
    "help" -> TemplateVisual(R.drawable.rl_scene_template_ticket_blue_v2, R.drawable.rl_scene_template_icon_help_v2)
    "services" -> TemplateVisual(R.drawable.rl_scene_template_ticket_mustard_v2, R.drawable.rl_scene_template_icon_services_v2)
    else -> TemplateVisual(R.drawable.rl_scene_template_ticket_blue_v2, R.drawable.rl_scene_template_icon_work_v2)
}
