package com.reallanguage.app.feature.diary.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlActiveAudioCaptureControl
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.rlKeyboardEditingSource
import com.reallanguage.app.core.designsystem.reconcileImeTextFieldValue
import java.util.Calendar

@Composable
fun DiaryInputScreen(
    state: DiaryInputUiState,
    onAction: (DiaryInputAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    RlPaperBackground(modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val compact = maxWidth < 700.dp
            if (compact) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DiaryHeader(state.submitting, onAction)
                    DiaryNotebook(state, onAction, Modifier.fillMaxWidth().height(390.dp))
                    DiarySidebar(state, onAction, Modifier.fillMaxWidth())
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    DiaryHeader(state.submitting, onAction)
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        DiaryNotebook(state, onAction, Modifier.weight(2.75f).fillMaxHeight())
                        DiarySidebar(state, onAction, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }

    if (state.showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { onAction(DiaryInputAction.ContinueEditingRequested) },
            title = { Text(stringResource(R.string.diary_discard_title)) },
            text = { Text(stringResource(R.string.diary_discard_explanation)) },
            confirmButton = {
                TextButton(onClick = { onAction(DiaryInputAction.ContinueEditingRequested) }) {
                    Text(stringResource(R.string.diary_continue_editing))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(DiaryInputAction.DiscardConfirmed) }) {
                    Text(stringResource(R.string.diary_confirm_discard), color = RlColor.Error)
                }
            },
        )
    }
}

@Composable
private fun DiaryHeader(submitting: Boolean, onAction: (DiaryInputAction) -> Unit) {
    Box(Modifier.fillMaxWidth().height(40.dp)) {
        IconButton(
            onClick = { onAction(DiaryInputAction.BackRequested) },
            enabled = !submitting,
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.diary_back),
                modifier = Modifier.size(28.dp),
                tint = RlColor.Ink,
            )
        }
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.diary_title),
                color = RlColor.Ink,
                fontFamily = FontFamily.Serif,
                fontSize = 30.sp,
                lineHeight = 31.sp,
                fontWeight = FontWeight.Bold,
            )
            Image(
                painter = painterResource(R.drawable.rl_handdrawn_terracotta_underline),
                contentDescription = null,
                modifier = Modifier.width(118.dp).height(8.dp),
                contentScale = ContentScale.FillBounds,
                colorFilter = ColorFilter.tint(RlColor.Ink),
            )
        }
    }
}

@Composable
private fun DiaryNotebook(
    state: DiaryInputUiState,
    onAction: (DiaryInputAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.testTag("diary-open-notebook")) {
        Image(
            painter = painterResource(R.drawable.rl_diary_open_notebook),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 18.dp, end = 24.dp, top = 54.dp, bottom = 26.dp),
        ) {
            Spacer(Modifier.weight(0.18f))
            DiaryTextInput(
                state = state,
                onAction = onAction,
                modifier = Modifier.weight(0.82f).fillMaxHeight().padding(bottom = 68.dp),
            )
        }
        DiaryDictationControl(
            state = state,
            onAction = onAction,
            modifier = Modifier.align(Alignment.BottomCenter).offset(x = 68.dp)
                .padding(bottom = 40.dp).width(278.dp),
        )
        Image(
            painter = painterResource(R.drawable.rl_diary_fountain_pen),
            contentDescription = null,
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 14.dp, y = 80.dp)
                .width(106.dp).height(330.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun DiaryTextInput(
    state: DiaryInputUiState,
    onAction: (DiaryInputAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val writingStyle = TextStyle(
        color = RlColor.TextPrimary,
        fontFamily = FontFamily.Serif,
        fontSize = 16.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.8.sp,
    )
    var fieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = state.text,
                selection = TextRange(
                    state.selectionStart.coerceIn(0, state.text.length),
                    state.selectionEnd.coerceIn(0, state.text.length),
                ),
            ),
        )
    }
    LaunchedEffect(state.text, state.selectionStart, state.selectionEnd) {
        fieldValue = reconcileImeTextFieldValue(
            fieldValue,
            state.text,
            state.selectionStart,
            state.selectionEnd,
        )
    }
    val update: (TextFieldValue) -> Unit = { incoming ->
        fieldValue = incoming
        if (incoming.text != state.text || incoming.selection.start != state.selectionStart ||
            incoming.selection.end != state.selectionEnd) {
            onAction(DiaryInputAction.TextChanged(
                incoming.text,
                incoming.selection.start,
                incoming.selection.end,
            ))
        }
    }
    BasicTextField(
        value = fieldValue,
        onValueChange = update,
        modifier = modifier.rlKeyboardEditingSource(fieldValue, update = update)
            .testTag("diary-text-input"),
        enabled = !state.submitting && state.dictationState != DiaryDictationState.RECORDING &&
            state.dictationState != DiaryDictationState.RECOGNIZING,
        textStyle = writingStyle,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(RlColor.ActionVivid),
        decorationBox = { innerTextField ->
            Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp)) {
                if (state.text.isEmpty()) {
                    Text(
                        "在此写下你想表达的内容",
                        color = RlColor.TextMuted.copy(alpha = 0.72f),
                        style = writingStyle,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun DiaryDictationControl(
    state: DiaryInputUiState,
    onAction: (DiaryInputAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentAction by rememberUpdatedState(onAction)
    val currentState by rememberUpdatedState(state.dictationState)
    Box(
        modifier = modifier.testTag("diary-dictation-control").pointerInput(Unit) {
            detectTapGestures(onPress = {
                if (currentState == DiaryDictationState.IDLE ||
                    currentState == DiaryDictationState.FAILED) {
                    currentAction(DiaryInputAction.DictationHoldStarted)
                    if (tryAwaitRelease()) currentAction(DiaryInputAction.DictationReleased)
                    else currentAction(DiaryInputAction.DictationInterrupted)
                }
            })
        },
    ) {
        when (state.dictationState) {
            DiaryDictationState.RECORDING -> RlActiveAudioCaptureControl(
                statusLabel = "正在听写",
                elapsedSeconds = state.dictationElapsedSeconds,
                actionLabel = "松开识别",
                actionEnabled = true,
                onAction = { onAction(DiaryInputAction.DictationReleased) },
                modifier = Modifier.fillMaxWidth(),
                testTagPrefix = "diary-dictation",
            )
            DiaryDictationState.RECOGNIZING -> Surface(
                modifier = Modifier.fillMaxWidth().height(54.dp).testTag("diary-dictation-recognizing"),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                color = Color(0xFFEDE3D7),
                shadowElevation = 2.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("正在识别…", color = RlColor.Ink, fontFamily = FontFamily.Serif,
                        fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
            DiaryDictationState.IDLE, DiaryDictationState.FAILED -> DiaryHoldToDictateControl(
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DiaryHoldToDictateControl(
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(54.dp).testTag("diary-dictation-idle")
            .semantics { role = Role.Button },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        color = Color(0xFFEDE3D7),
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(42.dp).background(RlColor.ActionVivid, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Mic, contentDescription = null, modifier = Modifier.size(24.dp), tint = Color.White)
            }
            Spacer(Modifier.width(16.dp))
            Text("按住听写", color = RlColor.Ink, fontFamily = FontFamily.Serif,
                fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier.width(88.dp).height(24.dp).clipToBounds(),
                contentAlignment = Alignment.CenterStart,
            ) {
                Image(
                    painter = painterResource(R.drawable.rl_home_quick_recording_waveform),
                    contentDescription = null,
                    modifier = Modifier.width(88.dp).height(64.dp),
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
    }
}

@Composable
private fun DiarySidebar(
    state: DiaryInputUiState,
    onAction: (DiaryInputAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.testTag("diary-sidebar")) {
        val shortLandscape = maxHeight < 380.dp
        val gap = if (shortLandscape) 3.dp else 6.dp
        val dateHeight = if (shortLandscape) 28.dp else 38.dp
        val promptHeight = if (shortLandscape) 136.dp else 156.dp
        val privacyHeight = if (shortLandscape) 42.dp else 74.dp
        val submitHeight = if (shortLandscape) 38.dp else 48.dp
        Column(
            modifier = Modifier.fillMaxSize().padding(top = 3.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            DiaryDateTicket(Modifier.fillMaxWidth().height(dateHeight), shortLandscape)
            DiaryPromptNote(Modifier.fillMaxWidth().height(promptHeight), shortLandscape)
            Column(
                modifier = Modifier.fillMaxWidth()
                    .offset(y = if (shortLandscape) (-8).dp else (-16).dp),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                DiaryPrivacyNote(Modifier.fillMaxWidth().height(privacyHeight), shortLandscape)
                Text(
                    text = "${state.text.length} / ${ConfirmedDiaryInputValidator.MAX_CHARACTERS}",
                    color = if (state.error == null) RlColor.TextSecondary else RlColor.Error,
                    fontSize = if (shortLandscape) 14.sp else 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp),
                )
                if (state.dictationState == DiaryDictationState.FAILED) {
                    Text(
                        "听写识别失败，原有内容已保留，请重新按住听写。",
                        color = RlColor.Error,
                        fontSize = if (shortLandscape) 9.sp else 10.sp,
                        lineHeight = if (shortLandscape) 11.sp else 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                DiarySubmitButton(
                    enabled = state.submitEnabled && !state.submitting &&
                        state.dictationState != DiaryDictationState.RECORDING &&
                        state.dictationState != DiaryDictationState.RECOGNIZING,
                    onClick = { onAction(DiaryInputAction.SubmitRequested) },
                    modifier = Modifier.fillMaxWidth().height(submitHeight),
                    shortLandscape = shortLandscape,
                )
            }
        }
    }
}

@Composable
private fun DiaryDateTicket(modifier: Modifier = Modifier, shortLandscape: Boolean = false) {
    val dateText = remember {
        val calendar = Calendar.getInstance()
        "今天 · ${calendar.get(Calendar.MONTH) + 1} 月 ${calendar.get(Calendar.DAY_OF_MONTH)} 日"
    }
    Box(modifier.testTag("diary-date-ticket")) {
        Image(
            painter = painterResource(R.drawable.rl_torn_heading_label),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(RlColor.NoteContainer),
        )
        Text(
            dateText,
            modifier = Modifier.align(Alignment.Center),
            color = RlColor.Ink,
            fontFamily = FontFamily.Serif,
            fontSize = if (shortLandscape) 12.sp else 14.sp,
            letterSpacing = if (shortLandscape) 1.1.sp else 1.6.sp,
        )
        Icon(
            Icons.Outlined.AttachFile,
            contentDescription = null,
            modifier = Modifier.align(Alignment.CenterEnd).offset(x = (-8).dp)
                .size(if (shortLandscape) 30.dp else 36.dp).rotate(14f),
            tint = RlColor.Ink,
        )
    }
}

@Composable
private fun DiaryPromptNote(modifier: Modifier = Modifier, shortLandscape: Boolean = false) {
    Box(modifier.testTag("diary-prompt-note")) {
        Image(
            painter = painterResource(R.drawable.rl_torn_note),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Image(
            painter = painterResource(R.drawable.rl_diary_botanical_sprig),
            contentDescription = null,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 9.dp)
                .width(26.dp).height(34.dp),
            contentScale = ContentScale.Fit,
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(
                start = if (shortLandscape) 34.dp else 38.dp,
                end = 14.dp,
                top = if (shortLandscape) 12.dp else 15.dp,
                bottom = if (shortLandscape) 15.dp else 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(if (shortLandscape) 4.dp else 5.dp),
        ) {
            Text(
                "今天你想表达什么？",
                color = RlColor.ActionVivid,
                fontFamily = FontFamily.Serif,
                fontSize = if (shortLandscape) 12.sp else 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text("最多 10 句", color = RlColor.TextMuted,
                fontSize = if (shortLandscape) 9.sp else 11.sp, fontWeight = FontWeight.Medium)
            Text(
                "用中文写下你想表达的内容，我们会用AI帮你形成真正你想表达的英语。",
                color = RlColor.TextSecondary,
                fontSize = if (shortLandscape) 9.sp else 11.sp,
                lineHeight = if (shortLandscape) 13.sp else 16.sp,
            )
        }
    }
}

@Composable
private fun DiaryPrivacyNote(modifier: Modifier = Modifier, shortLandscape: Boolean = false) {
    Box(modifier.testTag("diary-privacy-note")) {
        Image(
            painter = painterResource(R.drawable.rl_torn_heading_label),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = if (shortLandscape) 10.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = RlColor.Ink,
                modifier = Modifier.size(if (shortLandscape) 16.dp else 18.dp))
            Text(
                "请不要填写密码、证件号等敏感信息。",
                color = RlColor.Ink,
                fontSize = if (shortLandscape) 9.sp else 11.sp,
                lineHeight = if (shortLandscape) 12.sp else 15.sp,
            )
        }
    }
}

@Composable
private fun DiarySubmitButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shortLandscape: Boolean = false,
) {
    Box(modifier = modifier) {
        Image(
            painter = painterResource(R.drawable.rl_torn_terracotta),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
            alpha = 1f,
            colorFilter = if (enabled) null else ColorFilter.tint(Color(0xFF949392)),
        )
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxSize().testTag("diary-submit"),
            shape = RectangleShape,
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                contentColor = Color.White,
                disabledContentColor = Color.White,
            ),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
                focusedElevation = 0.dp,
                hoveredElevation = 0.dp,
                disabledElevation = 0.dp,
            ),
        ) {
            Text(
                stringResource(R.string.diary_submit),
                fontFamily = FontFamily.Serif,
                fontSize = if (shortLandscape) 16.sp else 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
