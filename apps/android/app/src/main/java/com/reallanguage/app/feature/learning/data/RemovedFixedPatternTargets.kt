package com.reallanguage.app.feature.learning.data

import android.database.sqlite.SQLiteDatabase

/** A missing knowledge row is not deletion evidence. Only this owner's exact receipt is. */
internal const val ACTIVE_FIXED_TARGET_PREDICATE = """not (t.type='PATTERN' and exists (
    select 1 from pattern_removal_commands r
    where r.account_id=t.account_id and length(r.pattern_id)>0
      and t.known_knowledge_ref='PATTERN:' || r.pattern_id
))"""

internal data class RemovedFixedPatternTarget(val sentenceId: String, val targetId: String)

/** Read inside the caller's transaction; fixed source/target snapshots remain untouched. */
internal fun SQLiteDatabase.removedFixedPatternTargets(accountId: String, chapterId: String): Set<RemovedFixedPatternTarget> = rawQuery(
    "select t.unit_id,t.target_id from target_selections t where t.account_id=? and t.chapter_id=? and not ($ACTIVE_FIXED_TARGET_PREDICATE)",
    arrayOf(accountId, chapterId),
).use { cursor -> buildSet {
    while (cursor.moveToNext()) add(RemovedFixedPatternTarget(cursor.getString(0), cursor.getString(1)))
} }
