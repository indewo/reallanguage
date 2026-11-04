package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.SceneEvaluationCandidate
import com.reallanguage.app.feature.learning.domain.SceneEvaluationDecision

internal data class RestoredSceneProgress(val state: SceneResponseUiState, val candidate: SceneEvaluationCandidate?)

internal fun encodeSceneProgress(state: SceneResponseUiState, candidate: SceneEvaluationCandidate?, recordingId: String? = null): Map<String, String> = buildMap {
    put("version", "1"); put("taskCount", state.tasks.size.toString())
    state.tasks.forEachIndexed { index, task ->
        put("task.$index.id", task.taskId); put("task.$index.sentence", task.sentenceId)
        put("task.$index.target", task.targetSentenceEn); put("task.$index.background", task.backgroundEn.orEmpty())
        put("task.$index.lines", task.contextLines.size.toString())
        task.contextLines.forEachIndexed { lineIndex, line ->
            put("task.$index.line.$lineIndex.speaker", line.speaker); put("task.$index.line.$lineIndex.text", line.textEn)
        }
    }
    put("taskIndex", state.taskIndex.toString()); put("roundIndex", state.roundIndex.toString())
    put("transcript", state.transcript); put("feedback", state.feedbackZh.orEmpty())
    put("trainingCompleted", state.trainingRoundCompleted.toString()); put("muted", state.audioMuted.toString())
    put("finalDecision", state.finalDecision?.name.orEmpty()); put("completed", state.completed.toString())
    recordingId?.takeIf(String::isNotBlank)?.let { put("recordingId", it) }
    candidate?.let {
        put("candidateDecision", it.decision.name); put("candidateTurn", it.correctTurn.toString())
        put("candidateComplete", it.targetSentenceComplete.toString()); put("candidatePronunciation", it.pronunciationIntelligible.toString())
        put("candidateFeedback", it.feedbackZh)
    }
}

internal fun restoreSceneProgress(current: SceneResponseUiState, saved: Map<String, String>): RestoredSceneProgress {
    require(saved["version"] == "1" && saved.getValue("taskCount").toInt() == current.tasks.size)
    require(current.tasks.isNotEmpty())
    current.tasks.forEachIndexed { index, task ->
        require(saved["task.$index.id"] == task.taskId && saved["task.$index.sentence"] == task.sentenceId && saved["task.$index.target"] == task.targetSentenceEn)
        require(saved["task.$index.background"] == task.backgroundEn.orEmpty() && saved.getValue("task.$index.lines").toInt() == task.contextLines.size)
        task.contextLines.forEachIndexed { lineIndex, line ->
            require(saved["task.$index.line.$lineIndex.speaker"] == line.speaker && saved["task.$index.line.$lineIndex.text"] == line.textEn)
        }
    }
    val index = saved.getValue("taskIndex").toInt().also { require(it in current.tasks.indices) }
    val round = saved.getValue("roundIndex").toInt().also { require(it in 0..2) }
    val candidate = saved["candidateDecision"]?.let {
        require(round == 2)
        SceneEvaluationCandidate(SceneEvaluationDecision.valueOf(it), saved.getValue("candidateTurn").toBooleanStrict(),
            saved.getValue("candidateComplete").toBooleanStrict(), saved.getValue("candidatePronunciation").toBooleanStrict(), saved.getValue("candidateFeedback"))
    }
    val decision = saved.getValue("finalDecision").takeIf(String::isNotEmpty)?.let(SceneEvaluationDecision::valueOf)
    require(decision == null || candidate != null)
    if (decision == SceneEvaluationDecision.PASS) {
        require(candidate?.decision == SceneEvaluationDecision.PASS && candidate.correctTurn && candidate.targetSentenceComplete && candidate.pronunciationIntelligible)
    }
    val completed = saved.getValue("completed").toBooleanStrict()
    require(!completed || (index == current.tasks.lastIndex && decision in setOf(SceneEvaluationDecision.PASS, SceneEvaluationDecision.FAIL)))
    val trainingDone = saved.getValue("trainingCompleted").toBooleanStrict()
    require(!trainingDone || round < 2)
    val transcript = saved.getValue("transcript")
    return RestoredSceneProgress(current.copy(taskIndex = index, roundIndex = round, transcript = transcript,
        feedbackZh = saved.getValue("feedback").ifEmpty { null }, trainingRoundCompleted = trainingDone,
        audioMuted = saved.getValue("muted").toBooleanStrict(), finalDecision = decision, completed = completed,
        loading = false, submitting = false, transcribing = false, recording = false, transcriptReady = false,
        needsNewRecording = transcript.isNotBlank() && !trainingDone && candidate == null,
        saveFailed = decision == SceneEvaluationDecision.PASS && !completed, progressFailed = false,
    ), candidate)
}
