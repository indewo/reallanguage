package com.reallanguage.app.feature.learning.data

import android.content.Context
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.ChapterSelectionLoadResult
import com.reallanguage.app.feature.learning.domain.ChapterSelectionRepository
import com.reallanguage.app.feature.learning.domain.ChapterSelectionBoundary
import com.reallanguage.app.feature.learning.domain.RemainingChapterSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidChapterSelectionRepository(
    context: Context,
    private val runtime: SessionRuntime,
) : ChapterSelectionRepository {
    private val applicationContext = context.applicationContext
    private val owner = runtime.accountId
    private val epoch = runtime.accountEpoch
    private fun ownerIsActive() = owner != null && runtime.accountId == owner &&
        runtime.accountEpoch == epoch && runtime.accessToken != null

    override suspend fun loadRemaining(boundary: ChapterSelectionBoundary): ChapterSelectionLoadResult = withContext(Dispatchers.IO) {
        val accountId = owner ?: return@withContext ChapterSelectionLoadResult.Failed
        if (!ownerIsActive()) return@withContext ChapterSelectionLoadResult.Failed
        try {
          FormationDatabase(applicationContext).use { helper ->
            val db = helper.readableDatabase
            check(ownerIsActive())
            val flowId = when (boundary) {
                is ChapterSelectionBoundary.ArchivedChapter -> db.rawQuery(
                    "select c.flow_id from chapters c inner join chapter_archives a " +
                        "on a.account_id=c.account_id and a.chapter_id=c.chapter_id " +
                        "where c.account_id=? and c.chapter_id=? and c.status='COMPLETED'",
                    arrayOf(accountId, boundary.chapterId),
                ).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }
                is ChapterSelectionBoundary.FixedSource -> db.rawQuery(
                    "select flow_id from chapters where account_id=? and flow_id=? and status='NOT_STARTED' limit 1",
                    arrayOf(accountId, boundary.sourceId),
                ).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }
            }
            val chapters = db.rawQuery(
                "select c.chapter_id,c.chapter_order," +
                    "coalesce((select f.text_zh from fixed_sentences f where f.account_id=c.account_id " +
                    "and f.chapter_id=c.chapter_id order by case f.role when 'FOCUS' then 0 else 1 end,f.sentence_order limit 1),'') " +
                    "from chapters c where c.account_id=? and c.flow_id=? and c.status='NOT_STARTED' " +
                    "order by c.chapter_order",
                arrayOf(accountId, flowId),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val identity = cursor.getString(2)
                        check(identity.isNotBlank())
                        add(RemainingChapterSummary(cursor.getString(0), cursor.getInt(1) + 1, identity))
                    }
                }
            }
            check(ownerIsActive())
            ChapterSelectionLoadResult.Ready(flowId, chapters)
          }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ChapterSelectionLoadResult.Failed }
    }
}
