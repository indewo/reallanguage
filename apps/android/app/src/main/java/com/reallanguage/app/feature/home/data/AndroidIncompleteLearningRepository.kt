package com.reallanguage.app.feature.home.data

import android.content.Context
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.home.domain.IncompleteLearningEntry
import com.reallanguage.app.feature.home.domain.IncompleteLearningLoadResult
import com.reallanguage.app.feature.home.domain.IncompleteLearningRepository
import com.reallanguage.app.feature.home.domain.OrdinarySourceType
import com.reallanguage.app.feature.home.domain.ResumeLearningKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class AndroidIncompleteLearningRepository(
    context: Context,
    private val runtime: SessionRuntime,
) : IncompleteLearningRepository {
    private val database = FormationDatabase(context.applicationContext)

    override suspend fun loadOrdinaryLearning(): IncompleteLearningLoadResult = withContext(Dispatchers.IO) {
        val identity = synchronized(runtime) {
            runtime.accountId?.let { it to runtime.accountEpoch }
        } ?: return@withContext IncompleteLearningLoadResult.Failed
        val accountId = identity.first
        val readContext = currentCoroutineContext()
        try {
            readContext.ensureActive()
            val entries = database.readableDatabase.rawQuery(
                """select c.chapter_id,f.source_type,f.source_title,c.chapter_order,
                          coalesce(cp.module_id,'LR-01'),coalesce(cp.step_id,'entry')
                   from chapters c
                   left join learning_checkpoints cp on c.account_id=cp.account_id and c.chapter_id=cp.chapter_id
                   join formation_flows f on f.account_id=c.account_id and f.flow_id=c.flow_id
                   left join chapter_archives a on a.account_id=c.account_id and a.chapter_id=c.chapter_id
                   where c.account_id=? and a.chapter_id is null and c.status!='COMPLETED'
                     and (cp.chapter_id is not null or not exists (
                       select 1 from chapters earlier
                       left join chapter_archives archived on archived.account_id=earlier.account_id and archived.chapter_id=earlier.chapter_id
                       where earlier.account_id=c.account_id and earlier.flow_id=c.flow_id
                         and earlier.chapter_order<c.chapter_order and earlier.status!='COMPLETED'
                         and archived.chapter_id is null
                     ))
                   order by c.rowid desc""",
                arrayOf(accountId),
            ).use { cursor -> buildList {
                while (cursor.moveToNext()) {
                    readContext.ensureActive()
                    add(IncompleteLearningEntry(
                        learningId = cursor.getString(0),
                        sourceType = when (cursor.getString(1)) {
                            "QUICK_RECORDING" -> OrdinarySourceType.QUICK_RECORDING
                            "AI_SCENE" -> OrdinarySourceType.AI_SCENE
                            else -> OrdinarySourceType.DIARY
                        },
                        sourceLabelZh = cursor.getString(2),
                        chapterNumber = cursor.getInt(3) + 1,
                        savedStepId = cursor.getString(4),
                        savedStepLabelZh = cursor.getString(4).labelZh(),
                        kind = ResumeLearningKind.ORDINARY,
                        checkpointStepId = cursor.getString(5),
                    ))
                }
            } }
            readContext.ensureActive()
            synchronized(runtime) {
                if (runtime.accountId == accountId && runtime.accountEpoch == identity.second) {
                    IncompleteLearningLoadResult.Ready(entries)
                } else IncompleteLearningLoadResult.Failed
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { IncompleteLearningLoadResult.Failed }
    }

    suspend fun hasIncompleteLearning(): Boolean =
        (loadOrdinaryLearning() as? IncompleteLearningLoadResult.Ready)?.entries?.isNotEmpty() == true
}

private fun String.labelZh(): String = when (this) {
    "LR-01" -> "章节开始"
    "LR-02" -> "基础讲解与三轮朗读"
    "LR-03" -> "读＋说"
    "LR-04" -> "手写抄写"
    "LR-05" -> "音频听写"
    "LR-06" -> "整句会说"
    "LR-07" -> "句式会说"
    "LR-08" -> "完整书写"
    "LR-09" -> "场景回应"
    else -> "继续学习"
}
