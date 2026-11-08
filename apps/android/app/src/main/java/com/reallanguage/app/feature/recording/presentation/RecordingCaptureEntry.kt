package com.reallanguage.app.feature.recording.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.CancellationException

@Composable
fun RecordingCaptureEntry(
    viewModel: ActiveRecordingViewModel,
    onBack: () -> Unit,
    transcribe: suspend (recordingId: String) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val currentBack by rememberUpdatedState(onBack)
    val currentTranscribe by rememberUpdatedState(transcribe)
    var saveAndReturn by rememberSaveable { mutableStateOf(false) }
    var savedId by rememberSaveable { mutableStateOf<String?>(null) }
    var failure by rememberSaveable { mutableStateOf<String?>(null) }
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    val processing = savedId != null && failure == null
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            if (effect is ActiveRecordingEffect.OpenCompletion) {
                if (saveAndReturn) currentBack() else savedId = effect.recordingId
            }
        }
    }
    LaunchedEffect(saveAndReturn, state.recording, state.error) {
        if (saveAndReturn && state.recording) viewModel.onAction(ActiveRecordingAction.StopRequested)
        else if (saveAndReturn && state.error != null) currentBack()
    }
    LaunchedEffect(savedId, attempt) {
        val id = savedId ?: return@LaunchedEffect
        if (failure != null) return@LaunchedEffect
        try {
            currentTranscribe(id)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (problem: Exception) {
            failure = problem.localizedMessage?.takeIf { it.isNotBlank() } ?: "转译失败，请重试"
        }
    }
    val goBack: () -> Unit = {
        when {
            processing || state.stopping && failure == null -> Unit
            savedId != null || state.error != null && !state.recording -> currentBack()
            else -> saveAndReturn = true
        }
    }
    BackHandler(onBack = goBack)
    RecordingCapturePage(
        state = if (failure == null) state else state.copy(stopping = false, recording = false),
        onAction = viewModel::onAction, onBack = goBack, transcribing = processing,
        transcriptionFailure = failure,
        onRetryTranscription = { failure = null; attempt++ },
    )
}
