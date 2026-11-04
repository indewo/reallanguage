package com.reallanguage.app.feature.home.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.reallanguage.app.feature.home.domain.ReminderLocalIdentity
import com.reallanguage.app.feature.home.domain.ReminderReviewAvailability
import com.reallanguage.app.feature.home.domain.ReminderTaskFacts
import com.reallanguage.app.feature.home.domain.ReminderTaskFactsReader
import com.reallanguage.app.feature.home.domain.ReminderTaskFactsResult
import com.reallanguage.app.feature.review.data.ReadOnlyReviewAvailability
import com.reallanguage.app.feature.review.data.readOnlyReviewReminderAvailability
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidReminderTaskFactsReader(
    context: Context,
    private val identity: ReminderLocalIdentity,
    private val localDate: () -> String = { LocalDate.now().toString() },
) : ReminderTaskFactsReader {
    private val databaseFile = context.applicationContext.getDatabasePath("real-language-learning.db")

    override suspend fun read(): ReminderTaskFactsResult = withContext(Dispatchers.IO) {
        val account = identity.accountId ?: return@withContext ReminderTaskFactsResult.Failed
        try {
            if (!identity.isCurrent()) return@withContext ReminderTaskFactsResult.Failed
            val date = LocalDate.parse(localDate()).toString()
            val facts = if (!databaseFile.exists()) {
                // A fresh installation has no local learning data. Do not create a database here.
                ReminderTaskFacts(ReminderReviewAvailability.NOT_DUE, false)
            } else SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                check(database.isReadOnly)
                val before = database.dataVersion()
                val review = when (database.readOnlyReviewReminderAvailability(account, date)) {
                    ReadOnlyReviewAvailability.NOT_DUE -> ReminderReviewAvailability.NOT_DUE
                    ReadOnlyReviewAvailability.AVAILABLE -> ReminderReviewAvailability.AVAILABLE
                    ReadOnlyReviewAvailability.DEFERRED_BY_DAILY_LIMIT -> ReminderReviewAvailability.DEFERRED_BY_DAILY_LIMIT
                }
                val incomplete = database.hasOrdinaryIncompleteLearning(account)
                // No write transaction is needed. A concurrent commit invalidates the combined
                // projection instead of returning facts assembled from different learning states.
                check(before == database.dataVersion()) { "Learning state changed during reminder read" }
                ReminderTaskFacts(review, incomplete)
            }
            if (identity.isCurrent()) ReminderTaskFactsResult.Ready(facts) else ReminderTaskFactsResult.Failed
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ReminderTaskFactsResult.Failed }
    }
}

private fun SQLiteDatabase.dataVersion(): Long = rawQuery("pragma data_version", null).use {
    check(it.moveToFirst()); it.getLong(0)
}

/** Same source/archive/checkpoint filter as AndroidIncompleteLearningRepository, without reading text. */
private fun SQLiteDatabase.hasOrdinaryIncompleteLearning(accountId: String): Boolean = rawQuery(
    """select 1 from chapters c
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
         )) limit 1""",
    arrayOf(accountId),
).use { it.moveToFirst() }
