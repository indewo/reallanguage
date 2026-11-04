package com.reallanguage.app.feature.review.data

import android.database.sqlite.SQLiteDatabase

internal enum class ReadOnlyReviewAvailability { NOT_DUE, AVAILABLE, DEFERRED_BY_DAILY_LIMIT }

/**
 * Select-only projection of loadTodayQueue's eligibility and whole-chapter daily limit.
 * It intentionally neither materializes a queue nor settles an empty cycle. Shared
 * knowledge eligibility stays authoritative in reviewCapabilityState, not a copy here.
 */
internal fun SQLiteDatabase.readOnlyReviewReminderAvailability(accountId: String, localDate: String): ReadOnlyReviewAvailability {
    val cycleId = rawQuery(
        "select review_cycle_id from review_cycles where account_id=? and status='ACTIVE' order by started_local_date,review_cycle_id limit 1",
        arrayOf(accountId),
    ).use { if (it.moveToFirst()) it.getString(0) else null }
    if (cycleId == null) {
        val days = rawQuery("select count(*) from normal_learning_days where account_id=?", arrayOf(accountId))
            .use { it.moveToFirst(); it.getInt(0) }
        if (days < 3) return ReadOnlyReviewAvailability.NOT_DUE
    }

    val completedChapters = if (cycleId == null) emptySet() else rawQuery(
        "select original_chapter_id from review_chapters where account_id=? and review_cycle_id=? and status='COMPLETED'",
        arrayOf(accountId, cycleId),
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    val chapterFocusSentences = linkedMapOf<String, MutableSet<String>>()
    rawQuery(
        "select o.chapter_id,o.sentence_id,o.capability_type,o.target_id " +
            "from learning_obligations o join chapters c on c.account_id=o.account_id and c.chapter_id=o.chapter_id " +
            "join formation_flows f on f.account_id=c.account_id and f.flow_id=c.flow_id " +
            "where o.account_id=? and c.status='COMPLETED' order by o.rowid,o.obligation_id",
        arrayOf(accountId),
    ).use { cursor ->
        while (cursor.moveToNext()) {
            val chapter = cursor.getString(0)
            val sentence = cursor.getString(1)
            val state = reviewCapabilityState(accountId, sentence, cursor.getString(2), cursor.getString(3))
            if (!state.eligible || chapter in completedChapters) continue
            val sentenceExists = rawQuery(
                "select 1 from fixed_sentences where account_id=? and chapter_id=? and unit_id=?",
                arrayOf(accountId, chapter, sentence),
            ).use { it.moveToFirst() }
            check(sentenceExists) { "Review source evidence is unavailable" }
            chapterFocusSentences.getOrPut(chapter) { linkedSetOf() }.add(sentence)
        }
    }
    if (chapterFocusSentences.isEmpty()) return ReadOnlyReviewAvailability.NOT_DUE

    val completedToday = rawQuery(
        "select coalesce(sum(completed_focus_count),0) from review_sessions where account_id=? and local_date=?",
        arrayOf(accountId, localDate),
    ).use { it.moveToFirst(); it.getInt(0) }
    check(completedToday >= 0)
    val available = (3 - completedToday).coerceAtLeast(0)
    // The foreground picks complete chapters, never a partial chapter to fill a slot.
    if (chapterFocusSentences.values.any { it.size in 1..available }) return ReadOnlyReviewAvailability.AVAILABLE
    check(completedToday > 0) { "Review chapter exceeds the confirmed focus limit" }
    return ReadOnlyReviewAvailability.DEFERRED_BY_DAILY_LIMIT
}
