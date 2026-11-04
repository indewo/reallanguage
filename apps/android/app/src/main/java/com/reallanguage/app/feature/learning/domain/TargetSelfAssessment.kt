package com.reallanguage.app.feature.learning.domain

enum class SelfAssessmentAbility { MEANING_WRITING, PATTERN_WRITING, PATTERN_SPEAKING }

data class SelfAssessmentTarget(
    val targetId: String,
    val sentenceId: String,
    val display: String,
    val explanationZh: String,
    val abilities: Set<SelfAssessmentAbility>,
    val assessedAbilities: Set<SelfAssessmentAbility> = emptySet(),
)

/** Chapter-local intent, not an independent assessment or a formal knowledge result. */
interface TargetSelfAssessmentRepository {
    /** Failures throw. An empty list means there really are no selected targets. */
    suspend fun load(): List<SelfAssessmentTarget>
    suspend fun mark(targetId: String, ability: SelfAssessmentAbility): Boolean
}
