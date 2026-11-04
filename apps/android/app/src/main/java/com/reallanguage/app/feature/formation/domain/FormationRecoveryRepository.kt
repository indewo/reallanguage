package com.reallanguage.app.feature.formation.domain

import com.reallanguage.app.feature.safety.domain.SourceType

data class FormationRecoveryItem(
    val sourceEntryId: String,
    val revision: Long,
    val phase: FormationRecoveryPhase,
    val sourceType: SourceType,
    val title: String,
)

sealed interface FormationRecoveryLoad {
    data object Missing : FormationRecoveryLoad
    data object Unavailable : FormationRecoveryLoad
    data class Loaded(val revision: Long, val snapshot: FormationRecoverySnapshot) : FormationRecoveryLoad
}

sealed interface FormationRecoverySave {
    data class Saved(val revision: Long) : FormationRecoverySave
    data object Conflict : FormationRecoverySave
    data object Unavailable : FormationRecoverySave
}

sealed interface FormationRecoveryList {
    data class Listed(val items: List<FormationRecoveryItem>) : FormationRecoveryList
    data object Unavailable : FormationRecoveryList
}

/** Bound to one authenticated account at construction; UI cannot request another account's content. */
interface FormationRecoveryRepository {
    suspend fun load(sourceEntryId: String): FormationRecoveryLoad
    suspend fun save(snapshot: FormationRecoverySnapshot, expectedRevision: Long?): FormationRecoverySave
    suspend fun list(): FormationRecoveryList
}
