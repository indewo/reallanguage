package com.reallanguage.app.feature.scene.presentation

import com.reallanguage.app.feature.formation.presentation.EditorialSheet

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import com.reallanguage.app.feature.formation.presentation.EditorialHeader
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.*
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneSpeaker
import com.reallanguage.app.feature.scene.domain.SceneStart

@Composable
fun SceneChineseReviewScreen(
    state: SceneChineseReviewUiState,
    onAction: (SceneChineseReviewAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    RlPaperBackground(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val stacked = maxWidth < 700.dp || LocalDensity.current.fontScale > 1.4f
        Column(
            Modifier.fillMaxSize().safeDrawingPadding()
                .padding(horizontal = RlSpace.Space12, vertical = RlSpace.Space4),
            verticalArrangement = Arrangement.spacedBy(RlSpace.Space4),
        ) {
            ReviewHeader(
                onBack = { onAction(SceneChineseReviewAction.ReturnToQuestionsRequested) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("scene-review-header"),
            )
            if (stacked) ReviewEditor(state, onAction,
                Modifier.fillMaxWidth().weight(1f).testTag("scene-review-editor"), appendContext = true,
            ) else Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(RlSpace.Space12),
            ) {
                ReviewContext(
                    state = state,
                    modifier = Modifier.weight(.67f).fillMaxHeight().testTag("scene-review-context"),
                )
                ReviewEditor(
                    state = state,
                    onAction = onAction,
                    modifier = Modifier.weight(1.33f).fillMaxHeight().testTag("scene-review-editor"),
                )
            }
        }
        }
    }
}

@Composable
private fun ReviewHeader(onBack: () -> Unit, modifier: Modifier = Modifier) {
    EditorialHeader(stringResource(R.string.scene_review_title), modifier,
        subtitle = "固定前还可以修改", onBack = onBack)
}

@Composable
private fun ReviewContext(state: SceneChineseReviewUiState, modifier: Modifier = Modifier) {
    val topic = when (val start = state.session.start) {
        is SceneStart.DirectDescription -> start.description
        is SceneStart.Recommendation -> start.recommendation.shortTopicZh
        is SceneStart.Template -> start.template.titleZh
    }
    SceneSummaryPoster(topic, if (state.session.contentType == SceneContentType.DIALOGUE) "对话" else "短文",
        state.session.answers.firstOrNull()?.answerZh?.takeIf(String::isNotBlank), modifier)
}

@Composable
private fun ReviewEditor(
    state: SceneChineseReviewUiState,
    onAction: (SceneChineseReviewAction) -> Unit,
    modifier: Modifier = Modifier,
    appendContext: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(RlSpace.Space4)) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
        Image(painterResource(R.drawable.rl_v73_scene_review_paper), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        EditorialSheet(
            modifier = Modifier.fillMaxSize(),
            contentPadding = 24.dp,
            // A dialogue is one continuous exchange.  Keep adjacent turns close
            // enough to read as a conversation instead of separate form cards.
            contentSpacing = 6.dp,
            contentModifier = Modifier.fillMaxSize().padding(start = 28.dp, end = 8.dp).verticalScroll(rememberScrollState()),
            showSpine = false,
            renderPaper = false,
        ) {
            Text(
                "中文场景内容", modifier = Modifier.align(Alignment.CenterHorizontally), color = RlColor.ActionVivid,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold),
            )
            Text(
                stringResource(R.string.scene_review_explanation), color = RlColor.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            state.units.forEach { unit ->
                AdaptiveReviewTextField(unit, state.reviewing, onAction)
            }
            if (state.issues.isNotEmpty()) {
                Text(stringResource(R.string.scene_review_issues_title), color = RlColor.Error, fontWeight = FontWeight.Bold)
                state.issues.forEach { Text("• ${it.explanationZh}", color = RlColor.Error) }
            }
            when (state.error) {
                SceneChineseReviewError.EMPTY_CONTENT -> Text(stringResource(R.string.scene_review_empty), color = RlColor.Error)
                SceneChineseReviewError.REVIEW_FAILED -> Text(stringResource(R.string.scene_review_failed), color = RlColor.Error)
                null -> Unit
            }
            if (appendContext) ReviewContext(state,
                Modifier.fillMaxWidth().height(320.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)).testTag("scene-review-context"))
        }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RlSecondaryButton(
                text = stringResource(R.string.scene_review_return_questions),
                onClick = { onAction(SceneChineseReviewAction.ReturnToQuestionsRequested) },
                enabled = !state.reviewing,
                modifier = Modifier.weight(1f).height(50.dp).testTag("scene-review-return"),
                contentPadding = PaddingValues(horizontal = RlSpace.Space4),
            )
            RlSecondaryButton(
                text = stringResource(R.string.scene_review_regenerate),
                onClick = { onAction(SceneChineseReviewAction.RegenerateRequested) },
                enabled = !state.reviewing,
                modifier = Modifier.weight(1f).height(50.dp).testTag("scene-review-regenerate"),
                contentPadding = PaddingValues(horizontal = RlSpace.Space4),
            )
            RlPrimaryButton(
                text = stringResource(if (state.changedUnitIds.isEmpty()) R.string.scene_review_confirm else R.string.scene_review_ai_review),
                enabled = !state.reviewing,
                onClick = { onAction(SceneChineseReviewAction.ConfirmRequested) },
                modifier = Modifier.weight(1f).height(50.dp).testTag("scene-review-confirm"),
                containerColor = RlColor.ActionVivid,
                contentPadding = PaddingValues(horizontal = RlSpace.Space4),
            )
        }
    }
}

@Composable
private fun AdaptiveReviewTextField(
    unit: com.reallanguage.app.feature.scene.domain.SceneDraftUnit,
    reviewing: Boolean,
    onAction: (SceneChineseReviewAction) -> Unit,
) {
    // Keep the same IME-safe editing state, but remove Material's floating label and
    // internal padding: the ruled manuscript grows to every actual line of the turn.
    val role = when (unit.speaker) {
        SceneSpeaker.USER -> "我"
        SceneSpeaker.OTHER -> "对方"
        null -> "段落"
    }
    Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top) {
    Text("$role：", color = if (unit.speaker == SceneSpeaker.OTHER) RlColor.ActionVivid else RlColor.Primary,
        fontFamily = EditorialFont.Serif, fontSize = 16.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.widthIn(min = 42.dp))
    RlKeyboardAwareBasicTextField(
        value = unit.textZh,
        onValueChange = { onAction(SceneChineseReviewAction.UnitChanged(unit.unitId, it)) },
        enabled = !reviewing,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 18.sp, lineHeight = 29.sp),
        cursorBrush = SolidColor(RlColor.Primary),
        minLines = 1,
        maxLines = Int.MAX_VALUE,
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 29.dp)
            .semantics { contentDescription = "${role}的中文内容" }
            .testTag("scene-review-unit-${unit.unitId}"),
    )
    }
    HorizontalDivider(color = RlColor.Border.copy(alpha = .48f), thickness = .6.dp)
    }
}
