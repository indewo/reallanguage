package com.reallanguage.app.feature.review.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** Call inside the owning account's transaction, only after no pending work remains. */
internal fun SQLiteDatabase.completeReviewCycle(accountId: String, cycleId: String, localDate: String) {
    val changed = update("review_cycles", ContentValues().apply { put("status", "COMPLETED") },
        "account_id=? and review_cycle_id=? and status='ACTIVE'", arrayOf(accountId, cycleId))
    if (changed == 0) return // A later result replay must not erase new-cycle days.
    delete("normal_learning_days", "account_id=? and local_date<=?", arrayOf(accountId, localDate))
    delete("review_chapter_items", "account_id=? and review_chapter_id in (select review_chapter_id from review_chapters where account_id=? and review_cycle_id=?)",
        arrayOf(accountId, accountId, cycleId))
    delete("review_queue_items", "account_id=? and review_cycle_id=?", arrayOf(accountId, cycleId))
}
