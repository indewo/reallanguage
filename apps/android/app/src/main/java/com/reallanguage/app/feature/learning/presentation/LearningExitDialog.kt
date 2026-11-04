package com.reallanguage.app.feature.learning.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.reallanguage.app.core.designsystem.EditorialFont
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlImmersiveDialogWindow
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.RlSpace
import kotlin.math.ceil

@Composable
fun LearningExitDialog(
    state: LearningExitUiState,
    onAction: (LearningExitAction) -> Unit,
) {
    Dialog(
        onDismissRequest = {
            if (!state.saving) onAction(LearningExitAction.ContinueLearningRequested)
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        RlImmersiveDialogWindow()
        Box(Modifier.fillMaxSize()) {
            Image(painterResource(R.drawable.rl_v73_learning_exit_background), null,
                Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)
                .windowInsetsPadding(WindowInsets.navigationBars)) {
                val reference = exitReferenceLayout(maxWidth, maxHeight, state.saveFailed)
                if (reference != null) {
                    LearningExitReferenceContent(state, onAction, reference)
                } else Column(
                    modifier = Modifier.fillMaxSize().padding(
                        horizontal = RlSpace.Space32,
                        vertical = RlSpace.Space16,
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(RlSpace.Space8),
                ) {
                    Text(
                        text = stringResource(R.string.learning_exit_page_title),
                        color = RlColor.Ink,
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontFamily = EditorialFont.Serif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 30.sp,
                            lineHeight = 38.sp,
                        ),
                    )
                    // Keep the heading fixed and retain the existing common natural
                    // image/text slots. A failed fit includes artwork, not only copy:
                    // no fallback may squeeze weighted illustrations to avoid scrolling.
                    Column(
                        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(RlSpace.Space8),
                    ) {
                        LearningExitIntroduction(state.saveFailed)
                        LearningExitChoices(state, onAction, naturalHeight = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun LearningExitIntroduction(saveFailed: Boolean, modifier: Modifier = Modifier, textAlign: TextAlign = TextAlign.Unspecified) {
    Text(
        text = stringResource(R.string.learning_exit_explanation),
        modifier = modifier,
        color = RlColor.TextSecondary,
        textAlign = textAlign,
        style = MaterialTheme.typography.bodyMedium,
    )
    if (saveFailed) Text(stringResource(R.string.learning_exit_save_failed), modifier = modifier, color = RlColor.Error, textAlign = textAlign)
}

private data class ExitReferenceLayout(
    val left: Dp,
    val contentWidth: Dp,
    val columnWidths: List<Dp>,
    val gaps: List<Dp>,
    val headerTop: Dp,
    val headerOffsetX: Dp,
    val bottomInset: Dp,
    val paintedButtonHeight: Dp,
    val titleSlotHeight: Dp,
    val explanationSlotHeight: Dp,
)

/** Page 31's measured 1832 x 859 painted geometry; not a shared button/theme change. */
@Composable
private fun exitReferenceLayout(width: Dp, height: Dp, saveFailed: Boolean): ExitReferenceLayout? {
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val measurer = rememberTextMeasurer()
    fun sourceX(pixels: Float) = width * (pixels / 1832f)
    fun sourceY(pixels: Float) = height * (pixels / 859f)
    val columnWidths = listOf(327f, 346f, 347f).map(::sourceX)
    val contentWidth = sourceX(1229f)
    val labels = listOf(
        stringResource(R.string.learning_exit_continue) to stringResource(R.string.learning_exit_continue_explanation),
        stringResource(R.string.learning_exit_pause) to stringResource(R.string.learning_exit_pause_explanation),
        stringResource(R.string.learning_exit_abandon) to stringResource(R.string.learning_exit_abandon_explanation),
    )
    fun textHeight(text: String, style: TextStyle, available: Dp): Dp = with(density) {
        measurer.measure(text, style, constraints = Constraints(maxWidth = available.roundToPx().coerceAtLeast(1))).size.height.toDp()
    }
    val labelHeights = labels.mapIndexed { index, (title, _) ->
        textHeight(title, typography.labelLarge, columnWidths[index] - 16.dp) + 8.dp
    }
    val paintedButtonHeight = maxOf(sourceY(75f), labelHeights.maxOrNull() ?: return null)
    val buttonSlotHeight = maxOf(48.dp, paintedButtonHeight)
    val headerTop = sourceY(66f)
    val bottomInset = height - sourceY(741.5f) - buttonSlotHeight / 2
    val headerHeight = textHeight(
        stringResource(R.string.learning_exit_page_title),
        typography.displaySmall.copy(fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp),
        width,
    )
    val introductionHeight = textHeight(stringResource(R.string.learning_exit_explanation), typography.bodyMedium, contentWidth)
    val errorHeight = if (saveFailed) textHeight(stringResource(R.string.learning_exit_save_failed), LocalTextStyle.current, contentWidth) else 0.dp
    val titleSlotHeight = labels.mapIndexed { index, (title, _) ->
        textHeight(title, typography.titleMedium.copy(fontFamily = EditorialFont.Serif), columnWidths[index])
    }.maxOrNull() ?: return null
    val explanationSlotHeight = labels.mapIndexed { index, (_, explanation) ->
        textHeight(explanation, typography.bodySmall, columnWidths[index])
    }.maxOrNull() ?: return null
    // The illustrations are decorative and already render with ContentScale.Fit. They
    // may give up unused vertical space before this page becomes scrollable; copy and
    // all three actions must remain visible together. This is especially important at
    // the real 2340 x 1080 phone viewport with a larger system font, where requiring
    // every bitmap's natural height used to select the oversized scrolling fallback.
    val minimumIllustrationHeight = maxOf(80.dp, sourceY(150f))
    val choiceContentHeight = minimumIllustrationHeight + titleSlotHeight + explanationSlotHeight +
        buttonSlotHeight + RlSpace.Space8 * 3
    val requiredHeight = headerTop + bottomInset + headerHeight + introductionHeight + errorHeight + choiceContentHeight +
        RlSpace.Space8 * (if (saveFailed) 3 else 2)
    // Keep the accessibility fallback only when the complete copy and touch targets
    // themselves cannot fit. At ordinary and moderately enlarged phone settings the
    // fixed reference layout scales artwork inside its slot, so the right-hand card,
    // "放弃本章" label and action stay inside the safe page instead of drifting out.
    if (bottomInset < 0.dp || requiredHeight > height) return null
    return ExitReferenceLayout(
        left = sourceX(296f), contentWidth = contentWidth, columnWidths = columnWidths,
        gaps = listOf(sourceX(96f), sourceX(113f)), headerTop = headerTop,
        // Current unmodified Serif token's measured ink center is 16px left of its
        // centered layout at this baseline; retain that type and position its ink.
        // The reference's different ink size remains a separately unaccepted issue.
        headerOffsetX = sourceX(29.5f), bottomInset = bottomInset,
        paintedButtonHeight = paintedButtonHeight,
        titleSlotHeight = titleSlotHeight, explanationSlotHeight = explanationSlotHeight,
    )
}

@Composable
private fun LearningExitReferenceContent(state: LearningExitUiState, onAction: (LearningExitAction) -> Unit, reference: ExitReferenceLayout) {
    Column(
        Modifier.fillMaxSize().padding(top = reference.headerTop, bottom = reference.bottomInset),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RlSpace.Space8),
    ) {
        Text(
            stringResource(R.string.learning_exit_page_title), Modifier.offset(x = reference.headerOffsetX), color = RlColor.Ink,
            style = MaterialTheme.typography.displaySmall.copy(
                fontFamily = EditorialFont.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                lineHeight = 38.sp,
            ),
        )
        LearningExitIntroduction(state.saveFailed, Modifier.width(reference.contentWidth), textAlign = TextAlign.Center)
        LearningExitChoiceRow(
            state, onAction,
            Modifier.width(reference.contentWidth).weight(1f).align(Alignment.Start).offset(x = reference.left),
            reference = reference,
        )
    }
}

@Composable
private fun LearningExitChoices(
    state: LearningExitUiState,
    onAction: (LearningExitAction) -> Unit,
    naturalHeight: Boolean,
    modifier: Modifier,
) {
    if (naturalHeight) {
        BoxWithConstraints(modifier) {
            if (maxWidth < 560.dp * LocalDensity.current.fontScale) {
                val slots = exitChoiceNaturalSlots(maxWidth * 3f + RlSpace.Space24 * 2f)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    LearningExitChoice(stringResource(R.string.learning_exit_continue), stringResource(R.string.learning_exit_continue_explanation),
                        R.drawable.rl_learning_exit_continue_v2, RlColor.Secondary, !state.saving,
                        { onAction(LearningExitAction.ContinueLearningRequested) }, "learning-exit-continue", naturalSlots = slots)
                    LearningExitChoice(stringResource(R.string.learning_exit_pause), stringResource(R.string.learning_exit_pause_explanation),
                        R.drawable.rl_learning_exit_pause_v2, RlColor.Primary, !state.saving,
                        { onAction(LearningExitAction.PauseLearningRequested) }, "learning-exit-pause", naturalSlots = slots)
                    LearningExitChoice(stringResource(R.string.learning_exit_abandon), stringResource(R.string.learning_exit_abandon_explanation),
                        R.drawable.rl_v73_learning_abandon_papers, RlColor.ActionVivid, !state.saving,
                        { onAction(LearningExitAction.AbandonChapterRequested) }, "learning-exit-abandon", naturalSlots = slots)
                }
            } else LearningExitChoiceRow(state, onAction, Modifier.fillMaxWidth(), naturalSlots = exitChoiceNaturalSlots(maxWidth))
        }
    } else {
        // Preserve the original bounded layout when it fits; the shared natural slots
        // only belong to the already-scrollable overflow state.
        LearningExitChoiceRow(state, onAction, modifier)
    }
}

private data class ExitChoiceNaturalSlots(val image: Dp, val title: Dp, val explanation: Dp)

@Composable
private fun exitChoiceNaturalSlots(width: Dp): ExitChoiceNaturalSlots {
    val density = LocalDensity.current
    val columnWidth = with(density) { ((width - RlSpace.Space24 * 2).roundToPx() / 3).coerceAtLeast(1) }
    val measurer = rememberTextMeasurer()
    val typography = MaterialTheme.typography
    val labels = listOf(
        stringResource(R.string.learning_exit_continue) to stringResource(R.string.learning_exit_continue_explanation),
        stringResource(R.string.learning_exit_pause) to stringResource(R.string.learning_exit_pause_explanation),
        stringResource(R.string.learning_exit_abandon) to stringResource(R.string.learning_exit_abandon_explanation),
    )
    val illustrations = listOf(
        painterResource(R.drawable.rl_learning_exit_continue_v2),
        painterResource(R.drawable.rl_learning_exit_pause_v2),
        painterResource(R.drawable.rl_v73_learning_abandon_papers),
    )
    // One common slot fits every existing image at this column width. ContentScale.Fit
    // below retains each original aspect ratio; the short note does not lift its title.
    val imageHeight = illustrations.maxOf {
        ceil(columnWidth * it.intrinsicSize.height / it.intrinsicSize.width).toInt()
    }
    fun textHeight(text: String, style: TextStyle) = measurer.measure(
        text, style = style, constraints = Constraints(maxWidth = columnWidth),
    ).size.height
    val titleHeight = labels.maxOf { textHeight(it.first, typography.titleMedium.copy(fontFamily = EditorialFont.Serif)) }
    val explanationHeight = labels.maxOf { textHeight(it.second, typography.bodySmall) }
    return with(density) { ExitChoiceNaturalSlots(imageHeight.toDp(), titleHeight.toDp(), explanationHeight.toDp()) }
}

@Composable
private fun LearningExitChoiceRow(
    state: LearningExitUiState,
    onAction: (LearningExitAction) -> Unit,
    modifier: Modifier,
    naturalSlots: ExitChoiceNaturalSlots? = null,
    reference: ExitReferenceLayout? = null,
) {
    Row(modifier, horizontalArrangement = if (reference == null) Arrangement.spacedBy(RlSpace.Space24) else Arrangement.Start) {
        LearningExitChoice(
            title = stringResource(R.string.learning_exit_continue),
            explanation = stringResource(R.string.learning_exit_continue_explanation),
            image = R.drawable.rl_learning_exit_continue_v2,
            color = RlColor.Secondary,
            enabled = !state.saving,
            onClick = { onAction(LearningExitAction.ContinueLearningRequested) },
            testTag = "learning-exit-continue",
            naturalSlots = naturalSlots,
            reference = reference,
            modifier = if (reference == null) Modifier.weight(1f) else Modifier.width(reference.columnWidths[0]),
        )
        if (reference != null) Spacer(Modifier.width(reference.gaps[0]))
        LearningExitChoice(
            title = stringResource(R.string.learning_exit_pause),
            explanation = stringResource(R.string.learning_exit_pause_explanation),
            image = R.drawable.rl_learning_exit_pause_v2,
            color = RlColor.Primary,
            enabled = !state.saving,
            onClick = { onAction(LearningExitAction.PauseLearningRequested) },
            testTag = "learning-exit-pause",
            naturalSlots = naturalSlots,
            reference = reference,
            modifier = if (reference == null) Modifier.weight(1f) else Modifier.width(reference.columnWidths[1]),
        )
        if (reference != null) Spacer(Modifier.width(reference.gaps[1]))
        LearningExitChoice(
            title = stringResource(R.string.learning_exit_abandon),
            explanation = stringResource(R.string.learning_exit_abandon_explanation),
            image = R.drawable.rl_v73_learning_abandon_papers,
            color = RlColor.ActionVivid,
            enabled = !state.saving,
            onClick = { onAction(LearningExitAction.AbandonChapterRequested) },
            testTag = "learning-exit-abandon",
            showAbandonMark = false,
            naturalSlots = naturalSlots,
            reference = reference,
            modifier = if (reference == null) Modifier.weight(1f) else Modifier.width(reference.columnWidths[2]),
        )
    }
}

@Composable
private fun LearningExitChoice(
    title: String,
    explanation: String,
    image: Int,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String,
    showAbandonMark: Boolean = false,
    naturalSlots: ExitChoiceNaturalSlots? = null,
    reference: ExitReferenceLayout? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = if (naturalSlots != null) modifier else modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RlSpace.Space8),
    ) {
        val illustration = painterResource(image)
        val imageModifier = if (naturalSlots != null) {
            Modifier.fillMaxWidth().height(naturalSlots.image)
        } else Modifier.fillMaxWidth().weight(1f)
        Box(modifier = imageModifier, contentAlignment = Alignment.Center) {
            Image(
                painter = illustration,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            if (showAbandonMark) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(
                        top = RlSpace.Space24,
                        end = RlSpace.Space32,
                    ).size(42.dp),
                    color = RlColor.ActionVivid.copy(alpha = .92f),
                    shape = androidx.compose.foundation.shape.CircleShape,
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.padding(RlSpace.Space8),
                        tint = Color.White,
                    )
                }
            }
        }
        LearningExitTextSlot(naturalSlots?.title ?: reference?.titleSlotHeight) {
            Text(
                title,
                color = RlColor.Ink,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = EditorialFont.Serif),
            )
        }
        LearningExitTextSlot(naturalSlots?.explanation ?: reference?.explanationSlotHeight) {
            Text(
                explanation,
                color = RlColor.TextSecondary,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (reference == null) {
            RlPrimaryButton(
                text = title, enabled = enabled, onClick = onClick,
                modifier = Modifier.testTag(testTag), containerColor = color,
            )
        } else LearningExitReferenceButton(title, enabled, onClick, color, reference.paintedButtonHeight, Modifier.testTag(testTag))
    }
}

@Composable
private fun LearningExitReferenceButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    color: Color,
    paintedHeight: Dp,
    modifier: Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        // This page's painted 75px body remains inside a minimum 48dp touch target.
        // Height already includes the actual unshrunk label plus 8dp when needed.
        modifier = modifier.fillMaxWidth().minimumInteractiveComponentSize().height(paintedHeight),
        shape = RlShape.Input,
        colors = ButtonDefaults.buttonColors(
            containerColor = color, contentColor = RlColor.OnPrimary,
            disabledContainerColor = RlColor.DisabledContainer, disabledContentColor = RlColor.TextSecondary,
        ),
        border = BorderStroke(1.dp, color.copy(alpha = .48f)),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) {
        Text(text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun LearningExitTextSlot(minHeight: Dp?, content: @Composable () -> Unit) {
    if (minHeight == null) content() else {
        Box(Modifier.fillMaxWidth().heightIn(min = minHeight), contentAlignment = Alignment.TopCenter) { content() }
    }
}
