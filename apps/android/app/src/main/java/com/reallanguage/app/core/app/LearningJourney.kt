package com.reallanguage.app.core.app

import com.reallanguage.app.feature.learning.domain.FixedLearningChapter

internal fun nextLearningStep(
    chapter: FixedLearningChapter,
    focusSentenceId: String,
    completedModule: String,
): HomeDiaryRoute.ResumeLearning? {
    val focuses = chapter.sentences.filter { it.isFocus }.map { it.sentenceId }
    val index = focuses.indexOf(focusSentenceId)
    if (index < 0) return null
    val following = focuses.getOrNull(index + 1)
    val (module, focus) = when (completedModule) {
        "LR-02" -> if (following != null) "LR-02" to following else "LR-03" to focuses.first()
        "LR-03" -> "LR-04" to focusSentenceId
        "LR-04" -> if (following != null) "LR-03" to following else "LR-05" to focuses.first()
        "LR-05" -> if (following != null) "LR-05" to following else "LR-06" to focuses.first()
        "LR-06" -> if (following != null) "LR-06" to following else "LR-07" to focuses.first()
        "LR-07" -> if (following != null) "LR-07" to following else "LR-08" to focuses.first()
        else -> return null
    }
    return HomeDiaryRoute.ResumeLearning(chapter.chapterId, module, focus)
}
