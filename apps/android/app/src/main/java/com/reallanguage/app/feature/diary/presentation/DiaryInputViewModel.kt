package com.reallanguage.app.feature.diary.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.diary.domain.DiaryDictationRepository
import com.reallanguage.app.feature.diary.domain.DiaryDictationResult
import com.reallanguage.app.feature.diary.domain.UnavailableDiaryDictationRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DiaryInputViewModel(
    private val validator: DiaryInputValidator,
    initialText: String = "",
    private val dictation: DiaryDictationRepository = UnavailableDiaryDictationRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(initialState(initialText))
    val uiState: StateFlow<DiaryInputUiState> = mutableUiState.asStateFlow()

    private val effectChannel = Channel<DiaryInputEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var dictationTimer: Job? = null
    private var recognitionJob: Job? = null
    private var recognitionGeneration = 0L
    private var insertionStart = 0
    private var insertionEnd = 0

    fun onAction(action: DiaryInputAction) {
        when (action) {
            is DiaryInputAction.TextChanged -> updateText(action)
            DiaryInputAction.BackRequested -> requestBack()
            DiaryInputAction.SubmitRequested -> submit()
            DiaryInputAction.ContinueEditingRequested -> mutableUiState.update {
                it.copy(showDiscardConfirmation = false)
            }
            DiaryInputAction.DiscardConfirmed -> discard()
            DiaryInputAction.DictationHoldStarted -> startDictation()
            DiaryInputAction.DictationReleased -> finishDictation()
            DiaryInputAction.DictationInterrupted -> interruptDictation()
            DiaryInputAction.DictationPermissionDenied -> mutableUiState.update {
                it.copy(dictationState = DiaryDictationState.FAILED, dictationElapsedSeconds = 0)
            }
        }
    }

    private fun updateText(action: DiaryInputAction.TextChanged) {
        val text = action.value
        val validity = validator.validate(text)
        mutableUiState.update {
            it.copy(
                text = text,
                submitEnabled = validity == DiaryInputValidity.VALID,
                error = validity.toError(),
                showDiscardConfirmation = false,
                selectionStart = action.selectionStart.coerceIn(0, text.length),
                selectionEnd = action.selectionEnd.coerceIn(0, text.length),
                dictationState = if (it.dictationState == DiaryDictationState.FAILED) {
                    DiaryDictationState.IDLE
                } else it.dictationState,
            )
        }
    }

    private fun startDictation() {
        val current = mutableUiState.value
        if (current.submitting || current.dictationState == DiaryDictationState.RECORDING ||
            current.dictationState == DiaryDictationState.RECOGNIZING || !dictation.start()) {
            if (!current.submitting) mutableUiState.update {
                it.copy(dictationState = DiaryDictationState.FAILED, dictationElapsedSeconds = 0)
            }
            return
        }
        recognitionGeneration += 1
        insertionStart = current.selectionStart.coerceIn(0, current.text.length)
        insertionEnd = current.selectionEnd.coerceIn(insertionStart, current.text.length)
        mutableUiState.update {
            it.copy(dictationState = DiaryDictationState.RECORDING, dictationElapsedSeconds = 0)
        }
        dictationTimer?.cancel()
        dictationTimer = viewModelScope.launch {
            while (true) {
                delay(1_000)
                mutableUiState.update { state ->
                    if (state.dictationState == DiaryDictationState.RECORDING) {
                        state.copy(dictationElapsedSeconds = state.dictationElapsedSeconds + 1)
                    } else state
                }
            }
        }
    }

    private fun finishDictation() {
        if (mutableUiState.value.dictationState != DiaryDictationState.RECORDING) return
        dictationTimer?.cancel()
        dictationTimer = null
        val generation = recognitionGeneration
        mutableUiState.update { it.copy(dictationState = DiaryDictationState.RECOGNIZING) }
        recognitionJob = viewModelScope.launch {
            val result = try {
                dictation.finish(UUID.randomUUID().toString())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DiaryDictationResult.Failed
            }
            if (generation != recognitionGeneration) return@launch
            when (result) {
                is DiaryDictationResult.Recognized -> insertRecognition(result.transcript)
                DiaryDictationResult.Failed -> mutableUiState.update {
                    it.copy(dictationState = DiaryDictationState.FAILED, dictationElapsedSeconds = 0)
                }
            }
        }
    }

    private fun insertRecognition(transcript: String) {
        val recognized = transcript.trim()
        if (recognized.isBlank()) {
            mutableUiState.update { it.copy(dictationState = DiaryDictationState.FAILED, dictationElapsedSeconds = 0) }
            return
        }
        val current = mutableUiState.value
        val start = insertionStart.coerceIn(0, current.text.length)
        val end = insertionEnd.coerceIn(start, current.text.length)
        val combined = current.text.replaceRange(start, end, recognized)
        val cursor = start + recognized.length
        val validity = validator.validate(combined)
        mutableUiState.value = current.copy(
            text = combined,
            selectionStart = cursor,
            selectionEnd = cursor,
            submitEnabled = validity == DiaryInputValidity.VALID,
            error = validity.toError(),
            dictationState = DiaryDictationState.IDLE,
            dictationElapsedSeconds = 0,
        )
    }

    private fun interruptDictation() {
        recognitionGeneration += 1
        dictationTimer?.cancel()
        recognitionJob?.cancel()
        dictationTimer = null
        recognitionJob = null
        dictation.cancel()
        mutableUiState.update {
            it.copy(dictationState = DiaryDictationState.IDLE, dictationElapsedSeconds = 0)
        }
    }

    private fun requestBack() {
        interruptDictation()
        val current = mutableUiState.value
        if (current.text.isBlank()) {
            viewModelScope.launch { effectChannel.send(DiaryInputEffect.OpenHome) }
        } else {
            mutableUiState.update { it.copy(showDiscardConfirmation = true) }
        }
    }

    private fun submit() {
        val current = mutableUiState.value
        if (!current.submitEnabled || current.submitting) return
        mutableUiState.update { it.copy(submitting = true, submitEnabled = false) }
        viewModelScope.launch { effectChannel.send(DiaryInputEffect.OpenSafetyCheck(current.text)) }
    }

    private fun discard() {
        if (!mutableUiState.value.showDiscardConfirmation) return
        mutableUiState.value = DiaryInputUiState()
        viewModelScope.launch { effectChannel.send(DiaryInputEffect.OpenHome) }
    }

    private fun initialState(text: String): DiaryInputUiState {
        val validity = validator.validate(text)
        return DiaryInputUiState(
            text = text,
            submitEnabled = validity == DiaryInputValidity.VALID,
            error = validity.toError(),
        )
    }

    override fun onCleared() {
        interruptDictation()
        super.onCleared()
    }

    private fun DiaryInputValidity.toError(): DiaryInputError? = when (this) {
        DiaryInputValidity.TOO_MANY_SENTENCES -> DiaryInputError.TOO_MANY_SENTENCES
        DiaryInputValidity.TOO_LONG -> DiaryInputError.TOO_LONG
        DiaryInputValidity.EMPTY, DiaryInputValidity.VALID -> null
    }
}
