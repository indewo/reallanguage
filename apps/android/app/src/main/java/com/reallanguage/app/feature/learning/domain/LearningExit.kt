package com.reallanguage.app.feature.learning.domain

data class OrdinaryLearningCheckpoint(
    val chapterId: String,
    val moduleId: String,
    val stepId: String,
)

sealed interface CheckpointSaveResult {
    data object Saved : CheckpointSaveResult
    data object Failed : CheckpointSaveResult
}

fun interface LearningCheckpointRepository {
    suspend fun save(checkpoint: OrdinaryLearningCheckpoint): CheckpointSaveResult
}
