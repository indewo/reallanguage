package com.reallanguage.app.core.app

import com.reallanguage.app.feature.learning.domain.ExplanationPreparationResult
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint

internal fun chapterEntryStartRoute(chapter: FixedLearningChapter, checkpoint: OrdinaryLearningCheckpoint?): HomeDiaryRoute? {
    if (checkpoint != null && checkpoint.chapterId != chapter.chapterId) return null
    if (checkpoint != null && checkpoint.moduleId != "LR-01") {
        if (checkpoint.moduleId !in setOf("LR-02", "LR-03", "LR-04", "LR-05", "LR-06", "LR-07", "LR-08", "LR-09")) return null
        learningResumeFocus(chapter, checkpoint.moduleId, checkpoint.stepId) ?: return null
        return HomeDiaryRoute.ResumeLearning(chapter.chapterId, checkpoint.moduleId, checkpoint.stepId)
    }
    return chapter.sentences.firstOrNull { it.isFocus }?.let { HomeDiaryRoute.ChapterExplanation(chapter, it.sentenceId) }
}

internal fun learningResumeRoute(chapter: FixedLearningChapter, moduleId: String, stepId: String?, explanation: ExplanationPreparationResult.Ready?, recoveredInput: String = ""): HomeDiaryRoute? {
    if (moduleId == "LR-01") return HomeDiaryRoute.ChapterLearning(chapter.chapterId)
    val focus = learningResumeFocus(chapter, moduleId, stepId) ?: return null
    if (moduleId == "LR-02") return HomeDiaryRoute.ChapterExplanation(chapter, focus)
    val delivered = explanation ?: return null
    val base = delivered.baseExplanationZh
    val expressions = delivered.expressions
    return when (moduleId) {
        "LR-03" -> HomeDiaryRoute.ReadSpeak(chapter, focus, base, expressions)
        "LR-04" -> HomeDiaryRoute.Handwriting(chapter, focus, base, expressions)
        "LR-05" -> if (stepId?.startsWith("correction:") == true) HomeDiaryRoute.ReadWriteCorrection(chapter, focus, base, expressions, recoveredInput) else HomeDiaryRoute.AudioDictation(chapter, focus, base, expressions)
        "LR-06" -> HomeDiaryRoute.FullSentenceSpeaking(chapter, focus, base, expressions)
        "LR-07" -> HomeDiaryRoute.PatternSpeaking(chapter, focus, base, expressions)
        "LR-08" -> HomeDiaryRoute.CompleteWriting(chapter, focus, base, expressions)
        "LR-09" -> HomeDiaryRoute.SceneResponse(chapter, focus, base, expressions)
        else -> null
    }
}

internal fun learningResumeFocus(chapter: FixedLearningChapter, moduleId: String, stepId: String?): String? {
    val focuses = chapter.sentences.filter { it.isFocus }.map { it.sentenceId }
    if (stepId == null) return focuses.firstOrNull()
    if (stepId in focuses) return stepId
    val prefixes = when (moduleId) {
        "LR-02" -> listOf("prepare:", "explanation:")
        "LR-05" -> listOf("correction:")
        else -> emptyList()
    }
    return prefixes.firstOrNull { stepId.startsWith(it) }?.let { prefix ->
        stepId.removePrefix(prefix).takeIf { it in focuses }
    }
}
