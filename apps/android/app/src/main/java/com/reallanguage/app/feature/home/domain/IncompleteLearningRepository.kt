package com.reallanguage.app.feature.home.domain

enum class OrdinarySourceType { DIARY, QUICK_RECORDING, AI_SCENE }
enum class ResumeLearningKind { ORDINARY, UNIFIED_REVIEW, FORMATION }

data class IncompleteLearningEntry(
    val learningId: String,
    val sourceType: OrdinarySourceType,
    val sourceLabelZh: String,
    val chapterNumber: Int?,
    val savedStepId: String,
    val savedStepLabelZh: String,
    val kind: ResumeLearningKind,
    val checkpointStepId: String? = null,
)

sealed interface IncompleteLearningLoadResult {
    data class Ready(val entries: List<IncompleteLearningEntry>) : IncompleteLearningLoadResult
    data object Failed : IncompleteLearningLoadResult
}

fun interface IncompleteLearningRepository {
    suspend fun loadOrdinaryLearning(): IncompleteLearningLoadResult
}
