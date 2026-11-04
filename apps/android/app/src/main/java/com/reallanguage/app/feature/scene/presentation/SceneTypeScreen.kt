package com.reallanguage.app.feature.scene.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.*
import com.reallanguage.app.feature.formation.presentation.EditorialHeader
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneStart

@Composable
fun SceneTypeScreen(state: SceneTypeUiState, onAction: (SceneTypeAction) -> Unit) {
    BackHandler { onAction(SceneTypeAction.BackRequested) }
    val sceneLabel = when (val start = state.start) {
        is SceneStart.DirectDescription -> start.description
        is SceneStart.Recommendation -> start.recommendation.shortTopicZh
        is SceneStart.Template -> start.template.titleZh
    }
    RlPaperBackground {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 6.dp)) {
            val fontScale = LocalDensity.current.fontScale
            val stacked = maxWidth < 650.dp || fontScale > 1.35f
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorialHeader(stringResource(R.string.scene_type_title), subtitle = stringResource(R.string.scene_type_explanation),
                    onBack = { onAction(SceneTypeAction.BackRequested) }, modifier = Modifier.testTag("scene-type-header"))
                Box(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 580.dp)) {
                    Image(painterResource(R.drawable.rl_torn_terracotta), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                    Text(stringResource(R.string.scene_type_selected, sceneLabel),
                        Modifier.padding(horizontal = 22.dp, vertical = 8.dp), color = Color.White,
                        fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp)
                }
                if (stacked) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        SceneTypeCard(true, state.selectedType == SceneContentType.DIALOGUE,
                            { onAction(SceneTypeAction.TypeSelected(SceneContentType.DIALOGUE)) }, Modifier.fillMaxWidth().height(300.dp * fontScale))
                        SceneTypeCard(false, state.selectedType == SceneContentType.ARTICLE,
                            { onAction(SceneTypeAction.TypeSelected(SceneContentType.ARTICLE)) }, Modifier.fillMaxWidth().height(300.dp * fontScale))
                    }
                } else Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    SceneTypeCard(true, state.selectedType == SceneContentType.DIALOGUE,
                        { onAction(SceneTypeAction.TypeSelected(SceneContentType.DIALOGUE)) }, Modifier.weight(1f).fillMaxHeight())
                    SceneTypeCard(false, state.selectedType == SceneContentType.ARTICLE,
                        { onAction(SceneTypeAction.TypeSelected(SceneContentType.ARTICLE)) }, Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TextButton(onClick = { onAction(SceneTypeAction.BackRequested) }, Modifier.weight(1f)) {
                        Text(stringResource(R.string.scene_type_back), color = RlColor.Ink)
                    }
                    RlPrimaryButton(stringResource(R.string.scene_type_continue), state.selectedType != null,
                        { onAction(SceneTypeAction.ContinueRequested) }, Modifier.weight(1.1f).testTag("scene-type-continue"),
                        containerColor = RlColor.ActionVivid)
                    if (!stacked) Spacer(Modifier.weight(1.2f))
                }
            }
        }
    }
}

@Composable
private fun SceneTypeCard(dialogue: Boolean, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val ink = if (dialogue) Color.White else RlColor.Secondary
    BoxWithConstraints(modifier.clickable(onClick = onClick).testTag(if (dialogue) "scene-type-dialogue" else "scene-type-article")) {
        Image(painterResource(if (dialogue) R.drawable.rl_v73_scene_type_dialogue else R.drawable.rl_v73_scene_type_article),
            null, Modifier.matchParentSize().testTag("scene-type-option-illustration"), contentScale = ContentScale.FillBounds)
        Column(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = maxHeight * .055f),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(stringResource(if (dialogue) R.string.scene_type_dialogue else R.string.scene_type_article), color = ink,
                modifier = Modifier.testTag(if (dialogue) "scene-type-dialogue-title" else "scene-type-article-title"),
                fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 26.sp)
            Text(if (dialogue) "练习真实来回交流" else "练习完整叙述一段经历", color = RlColor.NoteAccent,
                modifier = Modifier.testTag(if (dialogue) "scene-type-dialogue-summary" else "scene-type-article-summary"),
                fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
            HorizontalDivider(Modifier.fillMaxWidth(.65f).padding(vertical = 1.dp), color = ink.copy(alpha = .4f))
            Text(if (dialogue) "问候 · 回应 · 追问" else "经过 · 感受 · 结果", color = ink,
                modifier = Modifier.testTag(if (dialogue) "scene-type-dialogue-keywords" else "scene-type-article-keywords"),
                fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 10.5.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(20.dp).size(36.dp)
            .background(if (selected) RlColor.ActionVivid else Color.Transparent, CircleShape)
            .border(2.dp, if (selected) Color.White else RlColor.Secondary, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Outlined.Check, "已选择", tint = Color.White, modifier = Modifier.size(25.dp))
        }
    }
}
