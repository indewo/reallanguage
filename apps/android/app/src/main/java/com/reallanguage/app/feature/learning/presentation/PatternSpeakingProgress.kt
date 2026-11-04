package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.PatternPracticeExample

/** Versioned local working state; no credentials, raw audio or independent mastery result. */
internal fun encodePatternProgress(state: PatternSpeakingUiState): Map<String, String> = buildMap {
    put("version", "2")
    put("patternCount", state.lockedPatterns.size.toString())
    state.lockedPatterns.forEachIndexed { index, pattern ->
        put("pattern.$index.id", pattern.patternId)
        put("pattern.$index.source", pattern.sourceSentenceId)
        put("pattern.$index.structure", pattern.structure)
        put("pattern.$index.explanation", pattern.explanationZh)
    }
    val processed = state.lockedPatterns.map { it.patternId }.filter { it in state.processedPatternIds }
    require(processed.toSet() == state.processedPatternIds && processed.size == state.processedPatternIds.size)
    put("processedPatternCount", processed.size.toString())
    processed.forEachIndexed { index, id -> put("processedPattern.$index.id", id) }
    put("patternIndex", state.patternIndex.toString()); put("stage", state.stage.name)
    put("exampleCount", state.examples.size.toString())
    state.examples.forEachIndexed { index, example ->
        put("example.$index.id", example.exampleId); put("example.$index.scene", example.sceneZh)
        put("example.$index.prompt", example.aiPromptEn); put("example.$index.en", example.targetResponseEn)
        put("example.$index.zh", example.targetResponseZh)
        put("example.$index.read", (example.exampleId in state.completedReadingExampleIds).toString())
    }
    put("responseIndex", state.responseIndex.toString()); put("transcript", state.transcript)
    put("feedback", state.feedbackZh.orEmpty()); put("repeat", state.repeatRecommended.toString())
    put("responseCompleted", state.currentResponseCompleted.toString()); put("muted", state.audioMuted.toString())
    put("completed", state.completed.toString())
}

internal fun restorePatternProgress(current: PatternSpeakingUiState, saved: Map<String, String>): PatternSpeakingUiState {
    val version = saved.getValue("version")
    require(version == "1" || version == "2")
    require(saved.getValue("patternCount").toInt() == current.lockedPatterns.size)
    val targetsById = current.lockedPatterns.associateBy { it.patternId }
    require(targetsById.size == current.lockedPatterns.size && targetsById.keys.all(String::isNotBlank))
    val expectedTargetKeys = current.lockedPatterns.indices.flatMap { index ->
        listOf("pattern.$index.id", "pattern.$index.source", "pattern.$index.structure", "pattern.$index.explanation")
    }.toSet()
    require(saved.keys.filter { it.startsWith("pattern.") }.toSet() == expectedTargetKeys)
    val savedIds = current.lockedPatterns.indices.map { saved.getValue("pattern.$it.id") }
    require(savedIds.size == savedIds.toSet().size && savedIds.toSet() == targetsById.keys)
    savedIds.forEachIndexed { index, id ->
        val pattern = targetsById.getValue(id)
        require(saved["pattern.$index.source"] == pattern.sourceSentenceId &&
            saved["pattern.$index.structure"] == pattern.structure && saved["pattern.$index.explanation"] == pattern.explanationZh)
    }
    val savedIndex = saved.getValue("patternIndex").toInt()
    require(savedIndex in savedIds.indices)
    val currentId = savedIds[savedIndex]
    val patternIndex = current.lockedPatterns.indexOfFirst { it.patternId == currentId }
    val completed = saved.getValue("completed").toBooleanStrict()
    val processed = if (version == "1") {
        // v1 advanced only after a complete target. Preserve that original completed prefix,
        // never reinterpret it using today's source order or throw away the current paid set.
        require("processedPatternCount" !in saved && saved.keys.none { it.startsWith("processedPattern.") })
        require(!completed || savedIndex == savedIds.lastIndex)
        if (completed) savedIds.toSet() else savedIds.take(savedIndex).toSet()
    } else {
        val processedCount = saved.getValue("processedPatternCount").toInt()
        require(processedCount in 0..savedIds.size)
        val expectedProcessedKeys = (0 until processedCount).map { "processedPattern.$it.id" }.toSet()
        require(saved.keys.filter { it.startsWith("processedPattern.") }.toSet() == expectedProcessedKeys)
        val ids = (0 until processedCount).map { saved.getValue("processedPattern.$it.id") }
        require(ids.size == ids.toSet().size && ids.all { it in targetsById })
        ids.toSet()
    }
    require(if (completed) processed == targetsById.keys else currentId !in processed)
    val stage = PatternSpeakingStage.valueOf(saved.getValue("stage"))
    val count = saved.getValue("exampleCount").toInt().also { require(it in 0..5) }
    val examples = List(count) { index ->
        PatternPracticeExample(saved.getValue("example.$index.id"), saved.getValue("example.$index.scene"),
            saved.getValue("example.$index.prompt"), saved.getValue("example.$index.en"), saved.getValue("example.$index.zh"))
    }
    require(examples.map { it.exampleId }.distinct().size == count)
    require(examples.all { listOf(it.exampleId, it.sceneZh, it.aiPromptEn, it.targetResponseEn, it.targetResponseZh).all(String::isNotBlank) })
    val read = examples.filterIndexed { index, _ -> saved.getValue("example.$index.read").toBooleanStrict() }.map { it.exampleId }.toSet()
    require(stage !in setOf(PatternSpeakingStage.READING, PatternSpeakingStage.RESPONDING) || count > 0)
    require(stage != PatternSpeakingStage.RESPONDING || read.size == count)
    val responseIndex = saved.getValue("responseIndex").toInt().also { require(it in 0..maxOf(0, count - 1)) }
    return current.copy(patternIndex = patternIndex, stage = stage, examples = examples,
        completedReadingExampleIds = read, responseIndex = responseIndex,
        transcript = saved.getValue("transcript"), feedbackZh = saved.getValue("feedback").ifEmpty { null },
        repeatRecommended = saved.getValue("repeat").toBooleanStrict(),
        currentResponseCompleted = saved.getValue("responseCompleted").toBooleanStrict(),
        audioMuted = saved.getValue("muted").toBooleanStrict(), completed = completed, processedPatternIds = processed,
        recording = false, readingExampleId = null, submitting = false, progressFailed = false,
        failureMessageVisible = stage == PatternSpeakingStage.LOADING,
    )
}
