package com.reallanguage.app.feature.settings.domain

enum class BasicVocabulary {
    @Deprecated("Legacy transport value; not offered as a learning pool")
    PRIMARY_SCHOOL,
    JUNIOR_HIGH,
    SENIOR_HIGH,
    CET_4,
    CET_6,
    POSTGRADUATE,
    TOEFL,
    IELTS,
    GRE,
    @Deprecated("Legacy transport value; not offered as a learning pool")
    TEM_8,
}

val selectableVocabularyPools = listOf(
    BasicVocabulary.JUNIOR_HIGH,
    BasicVocabulary.SENIOR_HIGH,
    BasicVocabulary.CET_4,
    BasicVocabulary.CET_6,
    BasicVocabulary.POSTGRADUATE,
    BasicVocabulary.TOEFL,
    BasicVocabulary.IELTS,
    BasicVocabulary.GRE,
)

enum class PurposeVocabulary {
    IELTS_TOEFL,
    GRE,
    BUSINESS_ENGLISH,
}

data class InitialSettingsDraft(
    val formalityLevel: Int? = null,
    val slangEnabled: Boolean? = null,
    val basicVocabulary: BasicVocabulary? = null,
    val purposeVocabularies: Set<PurposeVocabulary> = emptySet(),
    val styleReference: String = "",
    val voiceEnabled: Boolean? = null,
    val autoPlayEnabled: Boolean? = null,
    val speechSpeedLevel: Int? = null,
)

sealed interface InitialSettingsCompletionResult {
    data object Completed : InitialSettingsCompletionResult
    data class Failed(val retryable: Boolean) : InitialSettingsCompletionResult
}

fun interface InitialSettingsRepository {
    suspend fun complete(settings: InitialSettingsDraft): InitialSettingsCompletionResult
}
