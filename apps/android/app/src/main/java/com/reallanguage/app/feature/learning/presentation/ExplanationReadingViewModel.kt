package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.learning.domain.ExplanationContentResult
import com.reallanguage.app.feature.learning.domain.ExplanationReadingProvider
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.SelectableExpression
import com.reallanguage.app.feature.learning.domain.savedExpressionExplanation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ExplanationReadingViewModel(
    chapter: FixedLearningChapter,
    currentFocusSentenceId: String,
    baseExplanationZh: String,
    expressions: List<SelectableExpression>,
    private val provider: ExplanationReadingProvider,
    private val progressStore: com.reallanguage.app.feature.learning.domain.LearningProgressStore? = null,
    private val voicePreferencesProvider: com.reallanguage.app.feature.learning.domain.LearningVoicePreferencesProvider? = null,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        ExplanationReadingUiState(
            chapter = chapter,
            currentFocusSentenceId = currentFocusSentenceId,
            baseExplanationZh = baseExplanationZh,
            expressions = expressions,
        ),
    )
    val uiState: StateFlow<ExplanationReadingUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<ExplanationReadingEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var pendingSave: ExplanationReadingUiState? = null
    private var expressionJob: kotlinx.coroutines.Job? = null
    private var expressionGeneration = 0L

    init { if (progressStore != null || voicePreferencesProvider != null) restoreProgress() }

    fun onAction(action: ExplanationReadingAction) {
        if (action == ExplanationReadingAction.ExplanationFinished) {
            mutableUiState.update { it.copy(audioPlaying = false, explaining = false, pronunciationText = null) }
            return
        }
        if (action == ExplanationReadingAction.AudioPlaybackFailed) {
            mutableUiState.update { it.copy(audioPlaying = false, explaining = false, audioFailed = true) }
            return
        }
        if (action == ExplanationReadingAction.RetryProgressRequested) {
            retryProgress()
            return
        }
        val switchingExpression = action is ExplanationReadingAction.ExpressionRequested && expressionJob?.isActive == true
        if ((mutableUiState.value.loading && !switchingExpression) || mutableUiState.value.progressFailed) return
        when (action) {
            ExplanationReadingAction.AudioPlaybackFailed -> Unit
            ExplanationReadingAction.RetryProgressRequested -> Unit
            ExplanationReadingAction.BackRequested -> send(
                ExplanationReadingEffect.ReturnToChapterEntry,
            )
            ExplanationReadingAction.AudioToggled -> change {
                if (!it.voiceEnabled) it
                else if (it.audioPlaying) it.copy(audioPlaying = false, explaining = false, autoExplanationSuppressed = true)
                else it.copy(audioPlaying = true, explaining = false, audioFailed = false,
                    pronunciationText = it.chapter.sentences.first { sentence -> sentence.sentenceId == it.currentFocusSentenceId }.textEn)
            }
            ExplanationReadingAction.ExplanationFinished -> change {
                it.copy(explaining = false, audioPlaying = false, pronunciationText = null)
            }
            ExplanationReadingAction.ReadFinished -> readFinished()
            ExplanationReadingAction.SkipExplanationRequested -> change {
                it.copy(explaining = false, audioPlaying = false, pendingHintRound = null, loadingFailed = false)
            }
            ExplanationReadingAction.SkipSentenceRequested -> send(
                ExplanationReadingEffect.SentenceSkipped,
            )
            ExplanationReadingAction.ContinueReadingRequested -> {
                if (mutableUiState.value.threeReadsCompleted) {
                    change { it.copy(audioPlaying = it.voiceEnabled, explaining = false, audioFailed = false,
                        pronunciationText = it.chapter.sentences.first { sentence -> sentence.sentenceId == it.currentFocusSentenceId }.textEn) }
                }
            }
            ExplanationReadingAction.UnderstoodRequested -> {
                understandAndContinue()
            }
            is ExplanationReadingAction.ExpressionRequested -> explainExpression(action.expressionId)
        }
    }

    private fun understandAndContinue() {
        val current = mutableUiState.value
        if (current.loading || current.progressFailed) return
        val next = current.copy(
            completedReadCount = 3,
            pendingHintRound = null,
            audioPlaying = false,
            explaining = false,
            pronunciationText = null,
        )
        if (progressStore == null) {
            mutableUiState.value = next
            send(ExplanationReadingEffect.OpenReadAndSpeak)
        } else {
            mutableUiState.update { it.copy(loading = true) }
            viewModelScope.launch {
                if (commit(next)) send(ExplanationReadingEffect.OpenReadAndSpeak)
            }
        }
    }

    private fun readFinished() {
        val current = mutableUiState.value
        if (current.loading || current.explaining || current.threeReadsCompleted) return
        // A failed next-round hint is still pending; retrying it is not another read.
        val completedCount = if (current.pendingHintRound != null) {
            current.completedReadCount
        } else current.completedReadCount + 1
        if (completedCount >= 3) {
            change {
                it.copy(completedReadCount = completedCount, audioPlaying = false)
            }
            return
        }
        val round = completedCount + 1
        val next = current.copy(completedReadCount = completedCount, pendingHintRound = round, loading = true, loadingFailed = false)
        mutableUiState.update { it.copy(loading = true) }
        viewModelScope.launch {
            if (commit(next)) loadPendingHint()
        }
    }

    private suspend fun loadPendingHint() {
        val current = mutableUiState.value
        val round = current.pendingHintRound ?: return
        when (
            val result = provider.incrementalHint(
                current.currentFocusSentenceId,
                round,
                current.baseExplanationZh,
            )
        ) {
            is ExplanationContentResult.Ready -> commit(
                current.copy(
                    currentIncrementalHint = result.explanationZh,
                    pendingHintRound = null,
                    loading = false,
                    explaining = !current.autoExplanationSuppressed && current.voiceEnabled,
                    audioPlaying = !current.autoExplanationSuppressed && current.voiceEnabled,
                    pronunciationText = null,
                    audioFailed = false,
                ),
            )
            else -> mutableUiState.update {
                it.copy(loading = false, loadingFailed = true, explaining = false)
            }
        }
    }

    private fun explainExpression(expressionId: String) {
        val current = mutableUiState.value
        if ((current.loading && expressionJob?.isActive != true) || current.expressions.none { it.expressionId == expressionId }) return
        val generation = ++expressionGeneration
        expressionJob?.cancel()
        val expression = current.expressions.first { it.expressionId == expressionId }
        mutableUiState.update { it.copy(loading = true, loadingFailed = false, explaining = false,
            activeExpressionId = expressionId, selectedExpression = null,
            pronunciationText = expression.display, audioPlaying = it.voiceEnabled, audioFailed = false) }
        expressionJob = viewModelScope.launch {
            val result = try {
                val delivered = provider.explainExpression(current.currentFocusSentenceId, expressionId)
                if (delivered is ExplanationContentResult.ExpressionReady &&
                    !provider.validateSavedExpressionOccurrence(current.currentFocusSentenceId, expression, delivered.explanation)) ExplanationContentResult.Failed
                else delivered
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ExplanationContentResult.Failed }
            if (generation != expressionGeneration) return@launch
            when (
                result
            ) {
                is ExplanationContentResult.ExpressionReady -> commit(
                    mutableUiState.value.copy(selectedExpression = result.explanation, loading = false),
                )
                else -> mutableUiState.update {
                    it.copy(loading = false, loadingFailed = true)
                }
            }
        }
    }

    private fun change(transform: (ExplanationReadingUiState) -> ExplanationReadingUiState) {
        val next = transform(mutableUiState.value)
        if (progressStore == null) mutableUiState.value = next
        else {
            mutableUiState.update { it.copy(loading = true) }
            viewModelScope.launch { commit(next) }
        }
    }

    private suspend fun commit(next: ExplanationReadingUiState): Boolean {
        val saved = try {
            check(next.selectedExpression?.let {
                val occurrence = next.expressions.single { expression -> expression.expressionId == it.expressionId }
                provider.validateSavedExpressionOccurrence(next.currentFocusSentenceId, occurrence, it)
            } != false)
            progressStore?.write(encode(next)) != false
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        if (saved) {
            pendingSave = null
            mutableUiState.value = next.copy(progressFailed = false)
        } else {
            pendingSave = next
            mutableUiState.update { it.copy(loading = false, progressFailed = true) }
        }
        return saved
    }

    private fun restoreProgress() {
        mutableUiState.update { it.copy(loading = true, progressFailed = false) }
        viewModelScope.launch {
            try {
                voicePreferencesProvider?.let { provider ->
                    val preferences = checkNotNull(provider.load())
                    val automatic = preferences.voiceEnabled && preferences.autoPlayEnabled
                    mutableUiState.update { it.copy(voiceEnabled = preferences.voiceEnabled, speechRate = preferences.speechRate,
                        autoExplanationSuppressed = !automatic, audioPlaying = automatic, explaining = automatic) }
                }
                val values = progressStore?.read()
                val restored = if (values == null) mutableUiState.value.copy(loading = false)
                    else decode(values, mutableUiState.value)
                require(restored.selectedExpression?.let {
                    val occurrence = restored.expressions.single { expression -> expression.expressionId == it.expressionId }
                    provider.validateSavedExpressionOccurrence(restored.currentFocusSentenceId, occurrence, it)
                } != false)
                mutableUiState.value = restored
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableUiState.update { it.copy(loading = false, progressFailed = true) } }
        }
    }

    private fun retryProgress() {
        if (mutableUiState.value.loading || !mutableUiState.value.progressFailed) return
        val next = pendingSave
        if (next == null) restoreProgress()
        else {
            mutableUiState.update { it.copy(loading = true) }
            viewModelScope.launch {
                if (commit(next) && next.loading && next.pendingHintRound != null) loadPendingHint()
            }
        }
    }

    private fun encode(state: ExplanationReadingUiState) = buildMap {
        put("version", "1")
        put("reads", state.completedReadCount.toString())
        put("explaining", state.explaining.toString())
        put("autoExplanationSuppressed", state.autoExplanationSuppressed.toString())
        state.pendingHintRound?.let { put("pendingHintRound", it.toString()) }
        state.currentIncrementalHint?.let { put("hint", it) }
        state.activeExpressionId?.let { put("activeExpressionId", it) }
        state.selectedExpression?.let {
            put("expressionId", it.expressionId); put("expressionZh", it.explanationZh)
            it.phonetic?.let { phonetic -> put("phonetic", phonetic) }
            it.naturalExample?.let { example ->
                put("exampleEn", example.textEn); put("exampleZh", example.textZh)
            }
            it.contextualMeaningZh?.let { meaning ->
                put("expressionContextualMeaningZh", meaning)
                put("expressionUsageZh", requireNotNull(it.usageZh))
                put("expressionHintZh", it.expressionHintZh.orEmpty())
            }
        }
    }

    private fun decode(values: Map<String, String>, base: ExplanationReadingUiState): ExplanationReadingUiState {
        require(values["version"] == "1")
        val reads = requireNotNull(values["reads"]?.toIntOrNull()).also { require(it in 0..3) }
        val pending = values["pendingHintRound"]?.toInt().also { require(it == null || (it in 2..3 && it == reads + 1)) }
        requireNotNull(values["explaining"]?.toBooleanStrictOrNull())
        val expression = savedExpressionExplanation(values)?.also { saved ->
            require(base.expressions.any { it.expressionId == saved.expressionId })
        }
        return base.copy(completedReadCount = reads, pendingHintRound = pending,
            currentIncrementalHint = values["hint"], selectedExpression = expression,
            activeExpressionId = values["activeExpressionId"]?.also { id -> require(base.expressions.any { it.expressionId == id }) } ?: expression?.expressionId,
            explaining = false, loading = false,
            autoExplanationSuppressed = base.autoExplanationSuppressed || values["autoExplanationSuppressed"]?.toBooleanStrict() == true,
            loadingFailed = pending != null, audioPlaying = false, progressFailed = false)
    }

    private fun send(effect: ExplanationReadingEffect) {
        viewModelScope.launch { effectChannel.send(effect) }
    }
}
