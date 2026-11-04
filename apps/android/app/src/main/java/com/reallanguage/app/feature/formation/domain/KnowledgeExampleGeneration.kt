package com.reallanguage.app.feature.formation.domain

data class KnowledgeSourceSentence(val textEn: String, val textZh: String)
data class KnowledgeExampleDraft(
    val textEn: String,
    val textZh: String,
    val pronunciationText: String,
)
enum class KnowledgeTargetType { VOCABULARY, PATTERN }
data class KnowledgeTargetDetails(
    val targetId: String,
    val phonetic: String?,
    val pronunciationText: String,
    val representativeScene: String,
    val examples: List<KnowledgeExampleDraft>,
    val naturalRevalidation: Boolean? = null,
)
data class KnowledgeExampleGenerationRequest(
    val targetId: String,
    val targetType: KnowledgeTargetType,
    val display: String,
    val meaningZh: String,
    val sourceScene: List<KnowledgeSourceSentence>,
    val commandId: String,
)
sealed interface KnowledgeExampleGenerationResult {
    data class Generated(val details: KnowledgeTargetDetails) : KnowledgeExampleGenerationResult
    data object Failed : KnowledgeExampleGenerationResult
}
fun interface KnowledgeExampleGenerationRepository {
    suspend fun generate(request: KnowledgeExampleGenerationRequest): KnowledgeExampleGenerationResult
}
