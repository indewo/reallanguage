package com.reallanguage.app.development

import androidx.compose.runtime.Composable
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.feature.learning.domain.PatternPracticeExample
import com.reallanguage.app.feature.learning.presentation.CompleteWritingPhase
import com.reallanguage.app.feature.learning.presentation.CompleteWritingScreen
import com.reallanguage.app.feature.learning.presentation.CompleteWritingUiState
import com.reallanguage.app.feature.learning.presentation.PatternSpeakingScreen
import com.reallanguage.app.feature.learning.presentation.PatternSpeakingStage
import com.reallanguage.app.feature.learning.presentation.PatternSpeakingUiState

internal val learningNormalEdgeDestinations = setOf(
    "COMPLETE_WRITING_AUDIBLE", "COMPLETE_WRITING_SILENT", "PATTERN_READING",
)

/** Normal production phases absent from the original 72-screen catalogue. */
@Composable
internal fun LearningNormalEdgesPreview(destinationName: String) {
    val chapter = FixedLearningChapter("normal-edge-chapter", 1, listOf(
        FixedLearningSentence("s1", "I was a little nervous during today’s meeting.", "今天开会时，我本来有点紧张。", false),
        FixedLearningSentence("s2", "But in the end, I worked up the courage to explain my idea clearly.", "但最后还是鼓起勇气把想法讲清楚了。", true),
        FixedLearningSentence("s3", "My colleague nodded after listening.", "同事听完后点了点头。", false),
    ))
    when (destinationName) {
        "COMPLETE_WRITING_AUDIBLE", "COMPLETE_WRITING_SILENT" -> CompleteWritingScreen(
            CompleteWritingUiState(
                chapter = chapter,
                lockedTargets = emptyList(),
                phase = if (destinationName == "COMPLETE_WRITING_AUDIBLE") CompleteWritingPhase.AUDIBLE_SINGLE else CompleteWritingPhase.SILENT_SINGLE,
                audioPlaying = destinationName == "COMPLETE_WRITING_AUDIBLE",
                input = "But in the end, I worked up the courage",
            ),
            onAction = {},
        )
        "PATTERN_READING" -> PatternSpeakingScreen(
            PatternSpeakingUiState(
                chapter = chapter,
                currentFocusSentenceId = "s2",
                lockedPatterns = listOf(LockedSpeakingPattern("p1", "s2", "work up the courage to + 动词", "鼓起勇气做某事")),
                stage = PatternSpeakingStage.READING,
                examples = listOf(
                    PatternPracticeExample("e1", "你决定申请一个机会。", "What did you finally decide to do?", "I worked up the courage to apply.", "我鼓起勇气提交了申请。"),
                    PatternPracticeExample("e2", "你决定分享自己的建议。", "What did you finally decide to do?", "I worked up the courage to share my suggestion.", "我鼓起勇气分享了自己的建议。"),
                    PatternPracticeExample("e3", "你决定在会议上发言。", "What did you finally decide to do?", "I worked up the courage to speak at the meeting.", "我鼓起勇气在会议上发了言。"),
                ),
                completedReadingExampleIds = setOf("e1"),
            ),
            onAction = {},
        )
        else -> error("Unknown normal learning preview: $destinationName")
    }
}
