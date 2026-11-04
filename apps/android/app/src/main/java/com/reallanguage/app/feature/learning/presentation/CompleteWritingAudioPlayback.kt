package com.reallanguage.app.feature.learning.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Composable
fun CompleteWritingAudioPlayback(
    state: CompleteWritingUiState,
    play: suspend (String, Float) -> Boolean,
    stop: () -> Unit,
    onAction: (CompleteWritingAction) -> Unit,
) {
    val currentPlay = rememberUpdatedState(play)
    val currentStop = rememberUpdatedState(stop)
    val currentAction = rememberUpdatedState(onAction)
    val sentenceId = state.audioSentenceId ?: state.currentFocusSentenceId
    val sentence = state.focusSentences.firstOrNull { it.sentenceId == sentenceId }
    DisposableEffect(Unit) { onDispose { currentStop.value() } }
    LaunchedEffect(state.chapter.chapterId, state.phase, state.sentenceIndex, state.audioPlaying,
        sentence, state.audioRequestId) {
        if (!state.audioPlaying) currentStop.value()
        else {
            val successful = try {
                sentence != null && sentence.textEn.isNotBlank() && currentPlay.value(sentence.textEn, .82f)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            currentCoroutineContext().ensureActive()
            currentAction.value(CompleteWritingAction.AudioPlaybackFinished(state.audioRequestId, successful))
        }
    }
}
