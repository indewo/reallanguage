package com.reallanguage.app.feature.recording.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.*
import com.reallanguage.app.feature.recording.domain.RepairReason
import com.reallanguage.app.feature.recording.domain.TranscriptSegmentChange
import com.reallanguage.app.feature.recording.domain.TranscriptSegmentReviewRules
import com.reallanguage.app.feature.recording.domain.TranscriptSegmentReviewStatus

@Composable
fun TranscriptResultScreen(
    state: TranscriptResultUiState,
    onAction: (TranscriptResultAction) -> Unit,
    modifier: Modifier = Modifier,
    onReturn: (() -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
    if (maxWidth < 640.dp || LocalDensity.current.fontScale > 1.35f) {
        RecordingEditorialScaffold(
            title = "转写结果", onBack = onReturn,
            pinActionsInStacked = true,
            artwork = { panel -> TranscriptAudioArtwork(state.playing, state.playbackBusy, { onAction(TranscriptResultAction.PreviewRequested) }, panel, state.transcript?.evidence?.durationSeconds) },
            actions = {
                if (!state.loading && state.transcript != null && state.error != TranscriptResultError.LOAD_FAILED) {
                    RlPrimaryButton(stringResource(if (state.confirming) R.string.transcript_result_confirming else R.string.transcript_result_confirm), state.canSubmitTranscript(), { onAction(TranscriptResultAction.ConfirmRequested) }, Modifier.weight(1f).testTag("transcript-result-confirm"), RlColor.ActionVivid)
                    if (onReturn != null) RlSecondaryButton(stringResource(R.string.transcript_result_later), onClick = onReturn, modifier = Modifier.weight(.6f))
                }
            },
        ) {
            when {
                state.loading -> CircularProgressIndicator()
                state.error == TranscriptResultError.LOAD_FAILED -> TranscriptLoadFailure(onAction)
                state.transcript != null -> TranscriptResultContent(state, onAction)
            }
        }
        return@BoxWithConstraints
    }
    RlPaperBackground(modifier) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = RlSpace.Space12, vertical = RlSpace.Space4),
            verticalArrangement = Arrangement.spacedBy(RlSpace.Space4),
        ) {
            Box(Modifier.fillMaxWidth().heightIn(min = 42.dp).testTag("transcript-result-header")) {
                Text(
                    "转写结果", Modifier.align(Alignment.Center), color = RlColor.Ink,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 23.sp,
                    ),
                )
                if (onReturn != null) {
                    IconButton(onClick = onReturn, modifier = Modifier.align(Alignment.CenterStart).testTag("transcript-result-return")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.transcript_result_return), tint = RlColor.Ink)
                    }
                }
            }
            Surface(Modifier.fillMaxWidth().height(1.dp), color = RlColor.Border) {}
            Row(
                Modifier.widthIn(max = 1480.dp).fillMaxWidth().weight(1f).align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.spacedBy(RlSpace.Space12),
            ) {
                TranscriptAudioArtwork(
                    state.playing, state.playbackBusy,
                    { onAction(TranscriptResultAction.PreviewRequested) },
                    Modifier.weight(.65f).fillMaxHeight(),
                    state.transcript?.evidence?.durationSeconds,
                )
                Box(
                    Modifier.weight(1.35f).fillMaxHeight(),
                ) {
                    Image(painterResource(R.drawable.rl_torn_note), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                    Column(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).testTag("transcript-result-scroll")
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                    when {
                        state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                        state.error == TranscriptResultError.LOAD_FAILED -> TranscriptLoadFailure(onAction)
                        state.transcript != null -> TranscriptResultContent(state, onAction)
                    }
                    }
                    if (!state.loading && state.transcript != null && state.error != TranscriptResultError.LOAD_FAILED) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RlPrimaryButton(
                                text = stringResource(if (state.confirming) R.string.transcript_result_confirming else R.string.transcript_result_confirm),
                                enabled = state.canSubmitTranscript(),
                                onClick = { onAction(TranscriptResultAction.ConfirmRequested) },
                                modifier = Modifier.weight(1f).testTag("transcript-result-confirm"),
                            )
                            if (onReturn != null) {
                                TextButton(onClick = onReturn, modifier = Modifier.testTag("transcript-result-later")) {
                                    Text(stringResource(R.string.transcript_result_later), color = RlColor.TextSecondary)
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun TranscriptLoadFailure(onAction: (TranscriptResultAction) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("暂时无法读取转写内容", color = RlColor.ActionVivid, fontFamily = EditorialFont.Serif,
            fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 34.sp)
        Text("请重试读取，或稍后回来继续处理。", color = RlColor.TextSecondary, fontSize = 15.sp, lineHeight = 23.sp)
        RlSecondaryButton(stringResource(R.string.diary_retry), onClick = { onAction(TranscriptResultAction.RetryLoadRequested) }, modifier = Modifier.heightIn(min = 46.dp))
    }
}

@Composable
private fun TranscriptAudioArtwork(
    playing: Boolean, busy: Boolean, onPreview: () -> Unit, modifier: Modifier = Modifier,
    durationSeconds: Int? = null,
) {
    Box(modifier.testTag("transcript-result-artwork")) {
        Image(
            painterResource(R.drawable.rl_editorial_blue_panel), null, Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Column(Modifier.fillMaxSize().padding(start = 36.dp, end = 24.dp, top = 42.dp)) {
            Text("音频证明", color = RlColor.SecondaryContainer, style = MaterialTheme.typography.labelLarge)
            Text(
                durationSeconds?.let { "本机录音 · %02d:%02d".format(it / 60, it % 60) } ?: "本机录音",
                Modifier.padding(top = RlSpace.Space16), color = Color.White,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 22.sp,
                ),
            )
            Surface(
                onClick = onPreview, enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = RlSpace.Space16).heightIn(min = 58.dp)
                    .testTag("transcript-result-preview"),
                color = RlColor.Surface.copy(alpha = .94f), shape = RlShape.Panel,
            ) {
                Row(
                    Modifier.padding(horizontal = RlSpace.Space8),
                    horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(Modifier.size(34.dp), shape = CircleShape, color = RlColor.Primary) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.PlayArrow, null, tint = Color.White)
                        }
                    }
                    Image(
                        painterResource(R.drawable.rl_voice_waveform_strip), null,
                        Modifier.weight(1f).height(28.dp), contentScale = ContentScale.FillBounds,
                    )
                    Text(
                        stringResource(if (playing) R.string.recording_confirmation_stop_preview else R.string.recording_confirmation_preview),
                        color = RlColor.Primary,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranscriptResultContent(state: TranscriptResultUiState, onAction: (TranscriptResultAction) -> Unit) {
    val reviewRequired = TranscriptSegmentReviewRules.required(state.transcript?.evidence)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(RlSpace.Space12), verticalArrangement = Arrangement.spacedBy(RlSpace.Space4)) {
        Text(
            "检查转写内容", color = RlColor.ActionVivid,
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 20.sp),
        )
        Text(if (reviewRequired) "逐段确认后继续" else "你可以直接修改文字", color = RlColor.TextSecondary, fontSize = 14.sp, lineHeight = 22.sp)
    }
    if (reviewRequired) {
        TranscriptSegmentContent(state, onAction)
    } else {
        TranscriptEditor(state.currentText, !state.confirming && !state.confirmed && !state.repairing) {
            onAction(TranscriptResultAction.TextChanged(it))
        }
        TranscriptDraftStatus(state, onAction)
    }
    if (state.transcript?.evidence?.droppedIncompleteTail == true) {
        Text("已排除截止时未说完的尾句；本机原始录音仍保留。", color = RlColor.TextSecondary,
            modifier = Modifier.testTag("transcript-result-boundary"))
    }
    stateErrorMessage(state.error)?.let { Text(stringResource(it), color = RlColor.Error) }
    if (state.repairNotice == TranscriptRepairNotice.NO_CHANGE) {
        Text(stringResource(R.string.transcript_result_no_change), color = RlColor.TextSecondary)
    }
    if (state.transcript?.evidence == null) {
        Text(stringResource(R.string.transcript_result_evidence_unavailable), color = RlColor.TextSecondary,
            modifier = Modifier.testTag("transcript-result-evidence-unavailable"))
    }
    OutlinedButton(
        onClick = { onAction(TranscriptResultAction.RepairRequested) },
        enabled = state.segmentMutationEnabled() && !state.draftSaving && !state.draftSaveFailed && state.transcript?.evidence != null,
        modifier = Modifier.testTag("transcript-result-repair"),
        border = BorderStroke(1.dp, RlColor.Primary),
        shape = RlShape.Small,
    ) {
        Icon(Icons.Outlined.AutoFixHigh, null)
        Text("  " + stringResource(if (state.repairing) R.string.transcript_result_repairing else R.string.transcript_result_repair))
    }
    state.repairComparison?.let { proposal ->
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("transcript-result-comparison"),
            color = RlColor.Surface.copy(alpha = .82f), shape = RlShape.Panel,
        ) {
            Column(Modifier.fillMaxWidth().padding(RlSpace.Space8), verticalArrangement = Arrangement.spacedBy(RlSpace.Space4)) {
                Text("原文　" + state.currentText, color = RlColor.TextSecondary)
                Text("优化建议　" + proposal.repairedText, color = RlColor.Ink)
                proposal.changes.forEach { change ->
                    Text(
                        stringResource(
                            if (change.reason == RepairReason.CONFIRMED_OMISSION) R.string.transcript_result_reason_omission
                            else R.string.transcript_result_reason_disfluency,
                        ),
                        color = RlColor.TextSecondary, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8)) {
                    OutlinedButton(
                        onClick = { onAction(TranscriptResultAction.KeepCurrentRequested) },
                        enabled = state.segmentMutationEnabled(),
                        modifier = Modifier.weight(1f).testTag("transcript-result-keep"),
                    ) { Text(stringResource(R.string.transcript_result_keep_current)) }
                    RlPrimaryButton(
                        text = stringResource(R.string.transcript_result_accept), enabled = state.segmentMutationEnabled(),
                        onClick = { onAction(TranscriptResultAction.AcceptRepairRequested) },
                        modifier = Modifier.weight(1f).testTag("transcript-result-accept"),
                        containerColor = RlColor.Secondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun TranscriptEditor(text: String, enabled: Boolean, segment: Boolean = false, onTextChanged: (String) -> Unit) {
    RlKeyboardAwareBasicTextField(
        value = text,
        enabled = enabled,
        onValueChange = onTextChanged,
        minLines = if (segment) 1 else 3,
        maxLines = Int.MAX_VALUE,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = if (segment) 16.sp else 18.sp, lineHeight = if (segment) 25.sp else 30.sp),
        cursorBrush = SolidColor(RlColor.Primary),
        modifier = Modifier.fillMaxWidth().padding(vertical = if (segment) 6.dp else 12.dp).testTag("transcript-result-editor"),
    )
}

@Composable
private fun TranscriptDraftStatus(state: TranscriptResultUiState, onAction: (TranscriptResultAction) -> Unit, segment: Boolean = false) {
    when {
        state.draftSaving -> Text(stringResource(R.string.transcript_result_draft_saving), color = RlColor.TextSecondary)
        state.draftSaveFailed -> {
            Text(
                stringResource(R.string.transcript_result_draft_failed), color = RlColor.Error,
                modifier = if (segment) Modifier.testTag("transcript-segment-error") else Modifier,
            )
            TextButton(
                onClick = { onAction(TranscriptResultAction.RetryDraftSaveRequested) },
                enabled = !state.confirming && !state.confirmed && !state.repairing && !state.segmentSaving,
                modifier = Modifier.testTag("transcript-result-draft-retry"),
            ) { Text(stringResource(R.string.transcript_result_draft_retry), color = RlColor.Primary) }
        }
        state.draftSaved -> Text(stringResource(R.string.transcript_result_draft_saved), color = RlColor.TextSecondary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranscriptSegmentContent(state: TranscriptResultUiState, onAction: (TranscriptResultAction) -> Unit) {
    val evidence = state.transcript?.evidence ?: return
    val review = state.segmentReviews.singleOrNull { it.segmentId == state.activeSegmentId }
    val source = evidence.segments.singleOrNull { it.segmentId == state.activeSegmentId }
    if (review == null || source == null) {
        // An old whole-text draft has no trustworthy per-segment mapping. Preserve it without guessing.
        SelectionContainer { Text(state.currentText, color = RlColor.Ink) }
        Text(stringResource(R.string.transcript_segment_mapping_unavailable), color = RlColor.Error, modifier = Modifier.testTag("transcript-segment-error"))
        return
    }
    val mutationEnabled = state.segmentMutationEnabled()
    val currentIndex = evidence.segments.indexOf(source)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(RlSpace.Space4), verticalArrangement = Arrangement.spacedBy(RlSpace.Space4)) {
        evidence.segments.forEachIndexed { index, segment ->
            val selected = segment.segmentId == state.activeSegmentId
            val confirmed = state.segmentReviews.singleOrNull { it.segmentId == segment.segmentId }?.status == TranscriptSegmentReviewStatus.CONFIRMED
            OutlinedButton(
                onClick = { onAction(TranscriptResultAction.SegmentSelected(segment.segmentId, state.segmentRevision)) },
                enabled = mutationEnabled,
                modifier = Modifier.testTag("transcript-segment-tab-${segment.segmentId}"),
                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) RlColor.Primary else RlColor.Border),
                shape = RlShape.Small,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (selected) RlColor.OnPrimary else RlColor.Primary,
                    containerColor = if (selected) RlColor.Primary else RlColor.SecondaryContainer.copy(alpha = .35f),
                ),
            ) {
                Text(stringResource(if (confirmed) R.string.transcript_segment_tab_confirmed else R.string.transcript_segment_tab, index + 1), fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
    }
    Text(stringResource(R.string.transcript_segment_position, currentIndex + 1, evidence.segments.size),
        color = RlColor.ActionVivid, fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 24.sp)
    Surface(color = RlColor.SecondaryContainer.copy(alpha = .28f), shape = RlShape.Small) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.transcript_segment_original_label), color = RlColor.TextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            SelectionContainer { Text(source.text, color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 15.sp, lineHeight = 23.sp,
                modifier = Modifier.testTag("transcript-segment-original")) }
        }
    }
    if (source.needsConfirmation == true) {
        Text(stringResource(R.string.transcript_segment_required), color = RlColor.ActionVivid, fontSize = 11.sp, lineHeight = 17.sp)
    }
    Surface(color = Color.Transparent, border = BorderStroke(1.dp, RlColor.BorderStrong), shape = RlShape.Small) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(stringResource(R.string.transcript_segment_current_label), color = RlColor.Secondary, fontSize = 12.sp, lineHeight = 18.sp)
            TranscriptEditor(state.segmentDraftText ?: review.text, !state.confirming && !state.confirmed && !state.repairing, segment = true) {
                onAction(TranscriptResultAction.SegmentTextEdited(source.segmentId, state.segmentRevision, it))
            }
        }
    }
    TranscriptDraftStatus(state, onAction, segment = true)
    if (review.inferenceText != null) {
        Text(
            stringResource(R.string.transcript_segment_adopted_inference, review.inferenceText),
            color = RlColor.ActionVivid, modifier = Modifier.testTag("transcript-segment-adopted-inference"),
        )
    }
    source.alternatives.forEachIndexed { index, candidate ->
        Surface(color = RlColor.SecondaryContainer.copy(alpha = .24f), shape = RlShape.Small) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(10.dp)) {
                val candidateText: @Composable (Modifier) -> Unit = { textModifier ->
                    Column(textModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.transcript_segment_candidate_label, index + 1), color = RlColor.TextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                        SelectionContainer {
                            Text(candidate, color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 15.sp, lineHeight = 23.sp,
                                modifier = Modifier.fillMaxWidth().testTag("transcript-segment-candidate-text-$index"))
                        }
                    }
                }
                val candidateButton: @Composable () -> Unit = {
                    TranscriptSegmentButton(stringResource(R.string.transcript_segment_choose_candidate, index + 1), mutationEnabled, "transcript-segment-candidate-$index") {
                        onAction(TranscriptResultAction.SegmentChanged(source.segmentId, state.segmentRevision, TranscriptSegmentChange.ChooseAlternative(index)))
                    }
                }
                if (maxWidth < 400.dp || LocalDensity.current.fontScale > 1.3f) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { candidateText(Modifier.fillMaxWidth()); candidateButton() }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        candidateText(Modifier.weight(1f)); candidateButton()
                    }
                }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8), verticalArrangement = Arrangement.spacedBy(RlSpace.Space4)) {
        TranscriptSegmentButton(stringResource(R.string.transcript_segment_choose_original), mutationEnabled, "transcript-segment-use-original") {
            onAction(TranscriptResultAction.SegmentChanged(source.segmentId, state.segmentRevision, TranscriptSegmentChange.ChooseOriginal))
        }
        TranscriptSegmentButton(stringResource(R.string.transcript_segment_cannot_confirm), mutationEnabled, "transcript-segment-cannot-confirm") {
            onAction(TranscriptResultAction.SegmentChanged(source.segmentId, state.segmentRevision, TranscriptSegmentChange.CannotConfirm))
        }
        if (!review.unableToConfirm) TranscriptSegmentButton(
            stringResource(if (review.status == TranscriptSegmentReviewStatus.CONFIRMED) R.string.transcript_segment_confirmed else R.string.transcript_segment_confirm),
            mutationEnabled && review.text.isNotBlank(), "transcript-segment-confirm", primary = true,
        ) { onAction(TranscriptResultAction.SegmentChanged(source.segmentId, state.segmentRevision, TranscriptSegmentChange.Confirm)) }
    }
    if (review.unableToConfirm) {
        if (source.mostLikelyIsInference && source.mostLikelyText != null) {
            Text(stringResource(R.string.transcript_segment_inference_warning), color = RlColor.ActionVivid,
                modifier = Modifier.testTag("transcript-segment-inference-warning"))
            SelectionContainer { Text(source.mostLikelyText, color = RlColor.Ink, modifier = Modifier.testTag("transcript-segment-inference")) }
            TranscriptSegmentButton(stringResource(R.string.transcript_segment_use_inference), mutationEnabled, "transcript-segment-use-inference") {
                onAction(TranscriptResultAction.SegmentChanged(source.segmentId, state.segmentRevision, TranscriptSegmentChange.ChooseInference))
            }
        } else {
            Text(stringResource(R.string.transcript_segment_no_inference), color = RlColor.TextSecondary)
        }
    }
    if (state.segmentError != null && !state.draftSaveFailed) {
        Text(stringResource(when (state.segmentError) {
            TranscriptSegmentError.CONFIRMATION_REQUIRED -> R.string.transcript_segment_confirmation_required
            TranscriptSegmentError.SAVE_FAILED -> R.string.transcript_result_draft_failed
            TranscriptSegmentError.REPAIR_MAPPING_FAILED -> R.string.transcript_segment_repair_mapping_failed
        }), color = RlColor.Error, modifier = Modifier.testTag("transcript-segment-error"))
    }
    if (review.unableToConfirm) TranscriptSegmentButton(
        stringResource(if (review.status == TranscriptSegmentReviewStatus.CONFIRMED) R.string.transcript_segment_confirmed else R.string.transcript_segment_confirm),
        mutationEnabled && review.text.isNotBlank(), "transcript-segment-confirm", primary = true,
    ) {
        onAction(TranscriptResultAction.SegmentChanged(source.segmentId, state.segmentRevision, TranscriptSegmentChange.Confirm))
    }
}

@Composable
private fun TranscriptSegmentButton(text: String, enabled: Boolean, tag: String, primary: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 40.dp).testTag(tag),
        border = BorderStroke(1.dp, if (primary) RlColor.ActionVivid else RlColor.Primary), shape = RlShape.Small,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (primary) RlColor.OnPrimary else RlColor.Primary,
            containerColor = if (primary) RlColor.ActionVivid else Color.Transparent),
    ) { Text(text, fontFamily = EditorialFont.Serif, fontSize = 13.sp, lineHeight = 20.sp) }
}

private fun TranscriptResultUiState.segmentMutationEnabled(): Boolean =
    !confirming && !confirmed && !repairing && !segmentSaving && !segmentDraftDirty

private fun TranscriptResultUiState.canSubmitTranscript(): Boolean {
    if (!segmentMutationEnabled() || currentText.isBlank()) return false
    val evidence = transcript?.evidence
    return !TranscriptSegmentReviewRules.required(evidence) ||
        (!draftSaving && !draftSaveFailed && evidence != null && TranscriptSegmentReviewRules.canConfirm(evidence, segmentReviews))
}

private fun stateErrorMessage(error: TranscriptResultError?): Int? = when (error) {
    TranscriptResultError.PLAYBACK_FAILED -> R.string.recording_confirmation_playback_failed
    TranscriptResultError.REPAIR_FAILED -> R.string.transcript_result_repair_failed
    TranscriptResultError.CONFIRM_FAILED -> R.string.transcript_result_confirm_failed
    TranscriptResultError.LOAD_FAILED, null -> null
}
