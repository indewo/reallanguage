package com.reallanguage.app.feature.learning.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import com.reallanguage.app.feature.learning.domain.normalizedEnglishTypography

internal data class ReadingSpeechPart(val text: String, val languageTag: String)

internal fun explanationSpeechParts(state: ExplanationReadingUiState): List<ReadingSpeechPart>? {
    if (!state.audioPlaying || !state.voiceEnabled || state.progressFailed ||
        (state.loading && state.pronunciationText == null)) return emptyList()
    state.pronunciationText?.let { pronunciation ->
        // A deliberate tap on “发音” is one replay, never another automatic loop.
        return listOf(ReadingSpeechPart(pronunciation.normalizedEnglishTypography(), "en-US"))
    }
    val focus = state.chapter.sentences.firstOrNull { it.sentenceId == state.currentFocusSentenceId && it.isFocus }
        ?: return null
    // On entry (and after moving to the next sentence), demonstrate the complete
    // English sentence exactly twice, then stop until the learner explicitly replays it.
    val sentence = focus.textEn.normalizedEnglishTypography()
    return List(2) { ReadingSpeechPart(sentence, "en-US") }
}

/** Uses real completion callbacks; it never confirms a user read or creates a result. */
@Composable
internal fun ExplanationAudioPlayback(
    state: ExplanationReadingUiState,
    play: suspend (String, Float, String) -> Boolean,
    stop: () -> Unit,
    onAction: (ExplanationReadingAction) -> Unit,
) {
    val parts = explanationSpeechParts(state)
    val currentPlay = rememberUpdatedState(play)
    val currentStop = rememberUpdatedState(stop)
    val currentAction = rememberUpdatedState(onAction)
    LaunchedEffect(parts, state.speechRate) {
        if (parts == null) {
            currentStop.value()
            currentAction.value(ExplanationReadingAction.AudioPlaybackFailed)
        } else if (parts.isEmpty()) currentStop.value()
        else {
            for (part in parts) {
                if (!currentPlay.value(part.text, state.speechRate, part.languageTag)) {
                    currentAction.value(ExplanationReadingAction.AudioPlaybackFailed)
                    return@LaunchedEffect
                }
            }
            currentAction.value(ExplanationReadingAction.ExplanationFinished)
        }
    }
}
