package com.reallanguage.app.feature.scene.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Forum
import com.reallanguage.app.feature.formation.presentation.EditorialHeader
import com.reallanguage.app.feature.formation.presentation.EditorialSheet
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.rlKeyboardEditingSource
import com.reallanguage.app.core.designsystem.reconcileImeTextFieldValue

@Composable
fun SceneDirectDescriptionScreen(
    state: SceneDirectDescriptionUiState,
    onAction: (SceneDirectDescriptionAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    RlPaperBackground(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val compact = maxWidth < 700.dp || LocalDensity.current.fontScale > 1.35f
            if (compact) {
                Column(
                    Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SceneDescriptionHeader(onAction)
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SceneDescriptionNotebook(state, onAction, Modifier.fillMaxWidth().height(300.dp))
                        SceneDescriptionSidebar(state, onAction, Modifier.fillMaxWidth().heightIn(min = 300.dp),
                            bounded = false, showContinue = false)
                    }
                    SceneDescriptionContinue(state, onAction)
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    SceneDescriptionHeader(onAction)
                    Row(
                        Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SceneDescriptionNotebook(state, onAction, Modifier.weight(2.4f).fillMaxHeight())
                        SceneDescriptionSidebar(state, onAction, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
    if (state.showDiscardConfirmation) {
        SceneDescriptionDiscardDialog(onAction)
    }
}

@Composable
private fun SceneDescriptionDiscardDialog(onAction: (SceneDirectDescriptionAction) -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { onAction(SceneDirectDescriptionAction.ContinueEditingRequested) },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        com.reallanguage.app.core.designsystem.RlImmersiveDialogWindow(dimAmount = .57f)
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), contentAlignment = Alignment.Center) {
            val stackedActions = maxWidth < 420.dp || LocalDensity.current.fontScale > 1.35f
            EditorialSheet(Modifier.widthIn(max = 500.dp).fillMaxWidth(), contentPadding = 24.dp,
                contentModifier = Modifier.verticalScroll(rememberScrollState()), contentSpacing = 14.dp) {
                Text("放弃当前场景描述？", color = RlColor.ActionVivid,
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 30.sp)
                Text("已输入的内容尚未进入下一步。放弃后不会保留当前描述。", color = RlColor.Ink,
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = 16.sp, lineHeight = 25.sp)
                val cancel: @Composable (Modifier) -> Unit = { buttonModifier ->
                    com.reallanguage.app.core.designsystem.RlSecondaryButton("继续编辑", onClick = {
                        onAction(SceneDirectDescriptionAction.ContinueEditingRequested)
                    }, modifier = buttonModifier.heightIn(min = 48.dp).testTag("scene-description-discard-cancel"))
                }
                val confirm: @Composable (Modifier) -> Unit = { buttonModifier ->
                    com.reallanguage.app.core.designsystem.RlPrimaryButton("放弃返回", true, {
                        onAction(SceneDirectDescriptionAction.DiscardConfirmed)
                    }, modifier = buttonModifier.heightIn(min = 48.dp).testTag("scene-description-discard-confirm"), containerColor = RlColor.ActionVivid)
                }
                if (stackedActions) {
                    cancel(Modifier.fillMaxWidth())
                    confirm(Modifier.fillMaxWidth())
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    cancel(Modifier.weight(1f))
                    confirm(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SceneDescriptionHeader(onAction: (SceneDirectDescriptionAction) -> Unit) {
    EditorialHeader("描述场景", subtitle = "告诉 AI 你想练习的真实场景",
        onBack = { onAction(SceneDirectDescriptionAction.BackRequested) },
        modifier = Modifier.testTag("scene-description-back"))
}

@Composable
private fun SceneDescriptionNotebook(
    state: SceneDirectDescriptionUiState,
    onAction: (SceneDirectDescriptionAction) -> Unit,
    modifier: Modifier,
) {
    Box(modifier.testTag("scene-description-notebook")) {
        Image(painterResource(R.drawable.rl_v73_scene_description_notebook), null,
            Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        SceneDescriptionInput(state, onAction,
            Modifier.fillMaxSize().padding(start = 52.dp, end = 120.dp, top = 44.dp, bottom = 55.dp))
    }
}

@Composable
private fun SceneDescriptionInput(
    state: SceneDirectDescriptionUiState,
    onAction: (SceneDirectDescriptionAction) -> Unit,
    modifier: Modifier,
) {
    val style = TextStyle(
        color = RlColor.TextPrimary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
        fontSize = 16.sp, lineHeight = 32.sp, letterSpacing = .8.sp,
    )
    var value by remember {
        mutableStateOf(TextFieldValue(
            state.text,
            TextRange(state.selectionStart.coerceIn(0, state.text.length), state.selectionEnd.coerceIn(0, state.text.length)),
        ))
    }
    LaunchedEffect(state.text, state.selectionStart, state.selectionEnd) {
        value = reconcileImeTextFieldValue(value, state.text, state.selectionStart, state.selectionEnd)
    }
    val update: (TextFieldValue) -> Unit = { incoming ->
        value = incoming
        if (incoming.text != state.text || incoming.selection.start != state.selectionStart ||
            incoming.selection.end != state.selectionEnd) {
            onAction(SceneDirectDescriptionAction.TextChanged(
                incoming.text,
                incoming.selection.start,
                incoming.selection.end,
            ))
        }
    }
    BasicTextField(
        value = value,
        onValueChange = update,
        modifier = modifier.rlKeyboardEditingSource(value, update = update)
            .testTag("scene-description-input"),
        textStyle = style,
        cursorBrush = SolidColor(RlColor.ActionVivid),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp)) {
                if (state.text.isEmpty()) Text(
                    "在此写下你想练习的场景",
                    color = RlColor.TextMuted.copy(alpha = .72f), style = style,
                )
                inner()
            }
        },
    )
}

@Composable
private fun SceneDescriptionSidebar(
    state: SceneDirectDescriptionUiState,
    onAction: (SceneDirectDescriptionAction) -> Unit,
    modifier: Modifier,
    bounded: Boolean = true,
    showContinue: Boolean = true,
) {
    EditorialSheet(modifier.testTag("scene-description-sidebar"), contentPadding = 16.dp, contentSpacing = 10.dp,
        contentModifier = if (bounded) Modifier.fillMaxSize() else Modifier) {
        Column(if (bounded) Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()) else Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth()) {
            Image(painterResource(R.drawable.rl_editorial_blue_panel), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            Text("告诉 AI 你的场景", Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = Color.White, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 26.sp)
        }
        Text("用中文描述人物、地点和你想沟通的事情。", color = RlColor.Ink,
            fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 14.sp, lineHeight = 21.sp)
        listOf(
            Triple(Icons.Outlined.People, "人物", "例如：你、同事、服务员"),
            Triple(Icons.Outlined.Place, "地点", "例如：餐厅、机场、酒店"),
            Triple(Icons.Outlined.Forum, "想沟通的事情", "例如：点餐、问路、办理入住"),
        ).forEachIndexed { index, (icon, title, detail) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null, tint = listOf(RlColor.Primary, RlColor.ActionVivid, RlColor.Secondary)[index],
                    modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 15.sp, lineHeight = 23.sp)
                    Text(detail, color = RlColor.TextSecondary, fontSize = 11.sp, lineHeight = 18.sp)
                }
            }
        }
        }
        if (showContinue) SceneDescriptionContinue(state, onAction)
    }
}

@Composable
private fun SceneDescriptionContinue(state: SceneDirectDescriptionUiState, onAction: (SceneDirectDescriptionAction) -> Unit) {
    com.reallanguage.app.core.designsystem.RlPrimaryButton("继续", state.continueEnabled,
        { onAction(SceneDirectDescriptionAction.ContinueRequested) },
        Modifier.fillMaxWidth().testTag("scene-description-continue"), containerColor = RlColor.ActionVivid)
}
