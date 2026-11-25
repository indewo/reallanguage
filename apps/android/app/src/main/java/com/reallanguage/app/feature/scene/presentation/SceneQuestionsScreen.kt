package com.reallanguage.app.feature.scene.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.*
import com.reallanguage.app.feature.formation.presentation.ProcessingNotebookArtwork

@Composable
fun SceneQuestionsScreen(state: SceneQuestionsUiState, onAction: (SceneQuestionsAction) -> Unit) {
    val back = { onAction(SceneQuestionsAction.BackRequested) }
    BackHandler(onBack = back)
    val question = state.currentQuestion ?: state.history.lastOrNull()?.question.takeIf { state.loading }
    val options = question?.options.orEmpty().map(String::trim)
        .filter { it.isNotBlank() && !customAnswerOption.matches(it) }.distinct()
    var custom by rememberSaveable(question?.questionId) {
        mutableStateOf(options.isEmpty() || (state.answerText.isNotBlank() && state.answerText !in options))
    }
    var customDraft by rememberSaveable(question?.questionId) {
        mutableStateOf(state.answerText.takeIf { it !in options }.orEmpty())
    }
    val customAnswer = remember(question?.questionId) { BringIntoViewRequester() }
    LaunchedEffect(custom, question?.questionId, state.loading, state.error) {
        if (custom && options.isNotEmpty() && !state.loading && state.error == null) {
            withFrameNanos { }
            customAnswer.bringIntoView()
        }
    }
    val selected = if (state.loading && state.currentQuestion == null)
        state.history.lastOrNull()?.answer?.answerZh.orEmpty() else state.answerText
    val error = state.error ?: SceneQuestionsError.REQUEST_FAILED.takeIf { !state.loading && question == null }
    val insufficient = error == SceneQuestionsError.INSUFFICIENT_INFORMATION
    val questionNumber = (state.confirmedAnswers.size + if (state.loading && state.history.isNotEmpty()) 0 else 1)
        .coerceIn(1, 10)

    RlPaperBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
            .padding(horizontal = 18.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().testTag("scene-questions-header")) {
                EditorialPageHeader("补充场景细节", back, backLabel = "返回")
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val compact = maxWidth < 580.dp || maxHeight < 175.dp || LocalDensity.current.fontScale > 1.35f
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!compact) NotebookPanel(Modifier.weight(.28f).fillMaxHeight().testTag("scene-questions-artwork"),
                        padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), trimOuterMargin = true) {
                        LearningContextTitle()
                        ProcessingNotebookArtwork(Modifier.weight(1f).fillMaxWidth(), state.start.displayTopic())
                    }
                    NotebookPanel(Modifier.weight(if (compact) 1f else .72f).fillMaxHeight(),
                        padding = PaddingValues(start = 20.dp, end = 18.dp, top = 12.dp, bottom = 14.dp),
                        trimOuterMargin = true, header = {
                        if (!compact) QuestionHeading(questionNumber, if (error == null) question?.questionZh else null)
                    }) {
                        key(question?.questionId) {
                            val scroll = rememberScrollState()
                            Column(Modifier.fillMaxSize().drawWithContent {
                                drawContent()
                                val overflow = scroll.maxValue
                                if (overflow in 1 until Int.MAX_VALUE) {
                                    val thumb = (size.height * size.height / (size.height + overflow)).coerceAtLeast(14.dp.toPx())
                                    val top = (size.height - thumb) * scroll.value / overflow
                                    drawRoundRect(RlColor.Secondary.copy(alpha = .65f),
                                        Offset(size.width - 3.dp.toPx(), top), Size(2.dp.toPx(), thumb), CornerRadius(2.dp.toPx()))
                                }
                            }.padding(end = 6.dp).verticalScroll(scroll),
                                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                if (compact) QuestionHeading(questionNumber, if (error == null) question?.questionZh else null)
                                when {
                                    error != null -> Text(stringResource(when (error) {
                                        SceneQuestionsError.INVALID_RESPONSE -> R.string.scene_questions_invalid
                                        SceneQuestionsError.INSUFFICIENT_INFORMATION -> R.string.scene_questions_insufficient
                                        SceneQuestionsError.REQUEST_FAILED -> R.string.scene_questions_failed
                                    }), Modifier.testTag("scene-questions-error"), color = RlColor.Error)
                                    question != null -> {
                                        SceneAnswerOptions(options, selected, custom, !state.loading,
                                            onPreset = {
                                                custom = false
                                                onAction(SceneQuestionsAction.AnswerChanged(it))
                                            },
                                            onCustom = {
                                                custom = true
                                                onAction(SceneQuestionsAction.AnswerChanged(customDraft))
                                            })
                                        if (custom) {
                                            RlKeyboardAwareOutlinedTextField(
                                                value = customDraft,
                                                readOnly = state.loading,
                                                onValueChange = {
                                                    customDraft = it
                                                    onAction(SceneQuestionsAction.AnswerChanged(it))
                                                },
                                                label = { Text("我的回答") },
                                                modifier = Modifier.fillMaxWidth().bringIntoViewRequester(customAnswer)
                                                    .testTag("scene-questions-answer"),
                                            )
                                        }
                                    }
                                }
                                if (state.loading) Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                                    Text(stringResource(R.string.scene_questions_loading))
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (LocalDensity.current.fontScale <= 1.3f) Spacer(Modifier.weight(.95f))
                NotebookAction("退出", { onAction(SceneQuestionsAction.ExitRequested) }, Modifier.weight(.65f), neutral = true)
                if (!insufficient) {
                    NotebookAction("上一问", { onAction(SceneQuestionsAction.PreviousRequested) },
                        Modifier.weight(.8f).testTag("scene-questions-previous"),
                        enabled = !state.loading && state.confirmedAnswers.isNotEmpty())
                }
                val label = when {
                    state.loading -> stringResource(R.string.scene_questions_loading)
                    insufficient -> stringResource(R.string.scene_questions_return_edit)
                    error != null -> stringResource(R.string.diary_retry)
                    else -> "确认回答"
                }
                NotebookAction(label, {
                    onAction(when {
                        insufficient -> SceneQuestionsAction.PreviousRequested
                        error != null -> SceneQuestionsAction.RetryRequested
                        else -> SceneQuestionsAction.AnswerSubmitted
                    })
                }, Modifier.weight(1.2f).testTag("scene-questions-submit"), primary = true,
                    enabled = !state.loading && (error != null || state.answerText.isNotBlank()),
                    leadingIcon = Icons.AutoMirrored.Outlined.ArrowForward)
            }
        }
    }
}

private val customAnswerOption = Regex("^(?:其他|其它|自己填写|自行填写|自定义(?:回答)?|自行回答)(?:[（(](?:请)?(?:说明|填写|补充|自填)[）)])?[：:]?$")

@Composable
private fun LearningContextTitle() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text("当前场景", Modifier.background(RlColor.Secondary, RoundedCornerShape(5.dp))
            .padding(horizontal = 12.dp, vertical = 3.dp), color = androidx.compose.ui.graphics.Color.White,
            fontFamily = EditorialFont.Serif, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun QuestionHeading(number: Int, question: String?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("第${number}问 / 最多10问", Modifier.align(Alignment.End).testTag("scene-questions-progress"),
            color = RlColor.TextSecondary, fontFamily = EditorialFont.Serif, fontSize = 13.sp)
        if (question != null) Text(question, Modifier.fillMaxWidth().heightIn(max = 78.dp)
            .verticalScroll(rememberScrollState()).testTag("scene-questions-prompt"),
            color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 22.sp,
            lineHeight = 30.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun SceneAnswerOptions(options: List<String>, selected: String, custom: Boolean, enabled: Boolean,
    onPreset: (String) -> Unit, onCustom: () -> Unit) {
    if (options.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup().testTag("scene-questions-options")) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val style = MaterialTheme.typography.bodyLarge.copy(fontFamily = EditorialFont.Serif, fontSize = 16.sp, lineHeight = 23.sp)
        val maximumHalfWidth = with(density) { ((maxWidth - 8.dp) / 2 - 56.dp).roundToPx() }
        val rows = buildList {
            var pending: String? = null
            for (option in options) {
                val fullWidth = maximumHalfWidth <= 0 ||
                    measurer.measure(option, style, maxLines = 1).size.width > maximumHalfWidth
                if (fullWidth) {
                    pending?.let { add(listOf(it)) }
                    pending = null
                    add(listOf(option))
                } else if (pending == null) {
                    pending = option
                } else {
                    add(listOf(pending, option))
                    pending = null
                }
            }
            pending?.let { add(listOf(it)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { option ->
                        AnswerPaper(option, !custom && selected == option, enabled, Modifier.weight(1f)
                            .testTag("scene-option-$option")) { onPreset(option) }
                    }
                }
            }
            AnswerPaper("自己填写", custom, enabled, Modifier.fillMaxWidth().testTag("scene-questions-custom-answer"), onCustom)
        }
    }
}

@Composable
private fun AnswerPaper(text: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 44.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)) {
        Image(painterResource(R.drawable.rl_notebook_panel_20261124), null, Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds, colorFilter = if (selected)
                ColorFilter.tint(androidx.compose.ui.graphics.Color(0xFFD7DDB8), BlendMode.Modulate) else NeutralNotebookPaper)
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
                Modifier.size(20.dp), tint = RlColor.Secondary)
            Text(text, Modifier.weight(1f), fontFamily = EditorialFont.Serif, fontSize = 16.sp, lineHeight = 23.sp, color = RlColor.Ink)
        }
    }
}
