package com.reallanguage.app.feature.formation.domain

data class EnglishCandidate(
    val candidateId: String,
    val textEn: String,
    val reusedKnowledgeRefs: List<String> = emptyList(),
)

data class EnglishSentenceDraft(
    val unitId: String,
    val textZh: String,
    val isFocus: Boolean,
    val candidates: List<EnglishCandidate>,
)

data class EnglishChapterDraft(
    val chapterId: String,
    val sentences: List<EnglishSentenceDraft>,
)
