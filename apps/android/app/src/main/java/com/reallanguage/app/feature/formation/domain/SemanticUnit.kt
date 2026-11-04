package com.reallanguage.app.feature.formation.domain

data class SemanticUnit(
    val unitId: String,
    val order: Int,
    val textZh: String,
    val recommendationReasonZh: String? = null,
)
