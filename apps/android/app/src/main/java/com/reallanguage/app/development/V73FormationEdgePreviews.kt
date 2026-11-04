package com.reallanguage.app.development

import androidx.compose.runtime.Composable
import com.reallanguage.app.feature.recording.domain.*
import com.reallanguage.app.feature.recording.presentation.*
import com.reallanguage.app.feature.safety.domain.*
import com.reallanguage.app.feature.safety.presentation.*
import com.reallanguage.app.feature.scene.domain.*
import com.reallanguage.app.feature.scene.presentation.*

/** Debug-catalog fixtures only: no repository, network, navigation or persistent writes. */
@Composable
internal fun V73FormationEdgePreview(destination: String) {
    when (destination) {
        "SCENE_DIRECT_DISCARD" -> SceneDirectDescriptionScreen(
            SceneDirectDescriptionUiState(
                text = "我在去见朋友的路上，手机没电了。我想找个地方充电，再给朋友发微信。",
                showDiscardConfirmation = true,
            ), {},
        )
        "SCENE_GENERATION_FAILED" -> SceneGenerationScreen(
            SceneGenerationUiState(
                session = edgeSceneSession(), generating = false,
                error = SceneGenerationError.GENERATION_FAILED,
            ), {},
        )
        "SAFETY_CHECK_FAILED" -> SafetyCheckScreen(
            SafetyCheckUiState(
                source = ConfirmedSource("今天下班后，我和同事讨论了明天的工作安排。", SourceType.DIARY, SourceContentShape.ARTICLE),
                processing = false, failed = true,
            ), {},
        )
        "LOCAL_RECORDINGS_EMPTY", "LOCAL_RECORDINGS_FAILED" -> LocalRecordingsScreen(
            LocalRecordingsUiState(loading = false, loadFailed = destination == "LOCAL_RECORDINGS_FAILED"), {},
        )
        "RECORDING_CONFIRMATION_FAILED" -> RecordingConfirmationScreen(
            RecordingConfirmationUiState(loading = false, error = RecordingConfirmationError.LOAD_FAILED), {},
        )
        "TRANSCRIPT_LOAD_FAILED" -> TranscriptResultScreen(
            TranscriptResultUiState(loading = false, error = TranscriptResultError.LOAD_FAILED), {}, onReturn = {},
        )
        "TRANSCRIPT_SEGMENT_REVIEW" -> TranscriptResultScreen(edgeSegmentTranscript(), {}, onReturn = {})
        "SCENE_QUESTIONS_INVALID", "SCENE_QUESTIONS_INSUFFICIENT" -> SceneQuestionsScreen(
            SceneQuestionsUiState(
                start = edgeSceneSession().start,
                contentType = SceneContentType.DIALOGUE,
                confirmedAnswers = edgeSceneSession().answers,
                history = listOf(AnsweredSceneQuestion(
                    SceneQuestion("q1", "你想和谁交流？", listOf("同事", "朋友")),
                    SceneAnswer("q1", "朋友"),
                )),
                loading = false,
                error = if (destination == "SCENE_QUESTIONS_INVALID") SceneQuestionsError.INVALID_RESPONSE
                    else SceneQuestionsError.INSUFFICIENT_INFORMATION,
            ), {},
        )
        else -> error("Unsupported edge preview: $destination")
    }
}

private fun edgeSceneSession() = SceneQuestionSession(
    start = SceneStart.DirectDescription("手机没电后，给朋友发消息说明自己会晚一点到"),
    contentType = SceneContentType.DIALOGUE,
    answers = listOf(SceneAnswer("q1", "朋友")),
    operationId = "preview-edge-scene",
)

private fun edgeSegmentTranscript(): TranscriptResultUiState {
    val segments = listOf(
        RecordingTranscriptSegment("s1", 0, 18000, "我给朋友打了一个电话。", "我", .97, emptyList(), null, false, false),
        RecordingTranscriptSegment("s2", 18000, 53000, "我们在苏州街附近见面。", "我", .67,
            listOf("我们在苏州桥附近见面。"), "我们在苏州街附近见面。", true, true),
        RecordingTranscriptSegment("s3", 53000, 84000, "他说会等我一会儿。", "我", .95, emptyList(), null, false, false),
    )
    val reviews = segments.mapIndexed { index, segment ->
        TranscriptSegmentReview(segment.segmentId, segment.text,
            status = if (index == 0) TranscriptSegmentReviewStatus.CONFIRMED else TranscriptSegmentReviewStatus.PENDING)
    }
    val original = segments.joinToString(" ") { it.text }
    val evidence = RecordingTranscriptEvidence(original, "zh", 84, RecordingEndReason.USER_STOPPED, segments, 84000, false)
    return TranscriptResultUiState(
        transcript = RecordingTranscript("preview-segments", "preview-audio", original, evidence = evidence, segmentReviews = reviews),
        currentText = original, loading = false, segmentReviews = reviews, activeSegmentId = "s2", segmentRevision = 1,
    )
}
