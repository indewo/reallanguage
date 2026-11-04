package com.reallanguage.app.feature.formation.domain

import com.reallanguage.app.feature.safety.domain.ConfirmedSource

sealed interface SemanticSplitResult {
    data class Ready(val units: List<SemanticUnit>) : SemanticSplitResult
    data object Failed : SemanticSplitResult
}

fun interface SemanticSplitRepository {
    suspend fun split(source: ConfirmedSource): SemanticSplitResult
}
