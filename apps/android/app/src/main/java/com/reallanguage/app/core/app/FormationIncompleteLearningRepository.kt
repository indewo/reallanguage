package com.reallanguage.app.core.app

import com.reallanguage.app.feature.formation.domain.FormationRecoveryRepository
import com.reallanguage.app.feature.formation.domain.FormationRecoveryList
import com.reallanguage.app.feature.formation.domain.FormationRecoveryPhase
import com.reallanguage.app.feature.home.domain.*
import com.reallanguage.app.feature.safety.domain.SourceType
import kotlinx.coroutines.CancellationException

/** The existing unfinished-content entry, not a new draft library or a formal chapter. */
class FormationIncompleteLearningRepository(
    private val ordinary: IncompleteLearningRepository,
    private val formation: FormationRecoveryRepository,
    private val fixedStatus: suspend (com.reallanguage.app.feature.formation.domain.FormationRecoveryItem) -> FixedFormationStatus = { FixedFormationStatus.NotFixed },
) : IncompleteLearningRepository {
    override suspend fun loadOrdinaryLearning(): IncompleteLearningLoadResult {
        val chapters = ordinary.loadOrdinaryLearning() as? IncompleteLearningLoadResult.Ready
            ?: return IncompleteLearningLoadResult.Failed
        val sources = formation.list() as? FormationRecoveryList.Listed
            // Formation drafts are an additional source. An unreadable legacy draft
            // must never hide independently valid chapter/checkpoint progress.
            ?: return IncompleteLearningLoadResult.Ready(chapters.entries)
        val pending = mutableListOf<com.reallanguage.app.feature.formation.domain.FormationRecoveryItem>()
        for (source in sources.items) {
            val status = try { fixedStatus(source) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { continue }
            when (status) {
                FixedFormationStatus.NotFixed -> pending += source
                FixedFormationStatus.Fixed -> Unit
                // Keep the encrypted record untouched. It may become readable after
                // the active flow saves a compatible snapshot, but it cannot make the
                // rest of the unfinished-learning page blank.
                FixedFormationStatus.Unavailable -> Unit
            }
        }
        return IncompleteLearningLoadResult.Ready(chapters.entries + pending.map { source ->
            IncompleteLearningEntry(
                learningId = source.sourceEntryId,
                sourceType = when (source.sourceType) {
                    SourceType.DIARY -> OrdinarySourceType.DIARY
                    SourceType.RECORDING -> OrdinarySourceType.QUICK_RECORDING
                    SourceType.AI_SCENE -> OrdinarySourceType.AI_SCENE
                },
                sourceLabelZh = source.title,
                chapterNumber = null,
                savedStepId = "FORMATION:${source.phase.name}",
                savedStepLabelZh = source.phase.labelZh(),
                kind = ResumeLearningKind.FORMATION,
            )
        })
    }
}

private fun FormationRecoveryPhase.labelZh() = when (this) {
    FormationRecoveryPhase.BILINGUAL_SELECTION -> "选择学习短句"
    FormationRecoveryPhase.SEMANTIC_SPLIT -> "整理中文内容"
    FormationRecoveryPhase.FOCUS_SELECTION -> "选择重点句"
    FormationRecoveryPhase.CHAPTER_FORMATION -> "形成章节"
    FormationRecoveryPhase.ENGLISH_GENERATION -> "生成英文表达"
    FormationRecoveryPhase.ENGLISH_CONFIRMATION -> "确认英文表达"
    FormationRecoveryPhase.LEARNING_TARGET_LOADING -> "生成学习目标"
    FormationRecoveryPhase.LEARNING_TARGET_SELECTION -> "选择学习目标"
    FormationRecoveryPhase.FINAL_FIX -> "确认并固定"
}
