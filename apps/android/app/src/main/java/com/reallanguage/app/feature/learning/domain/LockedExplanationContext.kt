package com.reallanguage.app.feature.learning.domain

import com.reallanguage.app.feature.formation.domain.LearningTargetType
import com.reallanguage.app.feature.formation.domain.SourceEvidence

/** An explanation only: no target identity, selected state, or learning capability. */
data class LockedExplanationVocabulary(
    val display: String,
    val senseZh: String,
    val unitType: LearningTargetType,
    val sourceEvidence: SourceEvidence,
)

data class LockedExplanationTarget(
    val targetId: String,
    val type: LearningTargetType,
    val display: String,
    val explanationZh: String,
    val structureParts: List<String>,
    val sourceEvidence: SourceEvidence?,
    val relatedVocabulary: List<LockedExplanationVocabulary>?,
)

/** The caller verifies this exact English against its displayed immutable chapter. */
data class LockedExplanationContext(
    val chapterId: String,
    val focusSentenceId: String,
    val textEn: String,
    val targets: List<LockedExplanationTarget>,
)
