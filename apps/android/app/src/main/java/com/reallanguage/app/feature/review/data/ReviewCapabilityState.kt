package com.reallanguage.app.feature.review.data

import android.database.sqlite.SQLiteDatabase
import java.util.Locale

internal data class ReviewCapabilityState(val status: String, val occurrenceCount: Int, val eligible: Boolean) {
    val priority: Int get() = when (status) {
        "PENDING" -> 0
        "LEARNING" -> 1
        "SELF_ASSESSED" -> 2
        "VERIFIED" -> 3
        else -> error("Unknown saved capability status")
    }
}

/** Reads actual formal state; a missing knowledge item is not a fabricated learning item. */
internal fun SQLiteDatabase.reviewCapabilityState(
    account: String, sentence: String, capability: String, targetId: String,
): ReviewCapabilityState {
    if (capability in setOf("FULL_WRITING", "FULL_SPEAKING", "SCENE_RESPONSE")) {
        val status = rawQuery("select status from sentence_capabilities where account_id=? and sentence_id=? and capability_type=?",
            arrayOf(account, sentence, capability)).use { check(it.moveToFirst()); it.getString(0) }
        check(status in setOf("PENDING", "LEARNING", "VERIFIED"))
        return ReviewCapabilityState(status, 1, status != "VERIFIED")
    }
    val target = rawQuery("select display,explanation_zh,type from target_selections where account_id=? and target_id=? and unit_id=?",
        arrayOf(account, targetId, sentence)).use { check(it.moveToFirst()); Triple(it.getString(0), it.getString(1), it.getString(2)) }
    fun normalized(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    val result = when (capability) {
        "MEANING_WRITING" -> {
            check(target.third in setOf("WORD", "FIXED_EXPRESSION"))
            rawQuery("select v.status,(select count(*) from vocabulary_occurrences o where o.account_id=v.account_id and o.item_id=v.item_id) from vocabulary_items v where v.account_id=? and v.normalized_form=? and v.semantic_sense_key=?",
                arrayOf(account, normalized(target.first), normalized(target.second))).use {
                check(it.moveToFirst()); ReviewCapabilityState(it.getString(0), it.getInt(1), it.getString(0) != "VERIFIED")
            }
        }
        "PATTERN_WRITING", "PATTERN_SPEAKING" -> {
            check(target.third == "PATTERN")
            val prefix = if (capability == "PATTERN_WRITING") "writing" else "speaking"
            rawQuery("select p.${prefix}_status,p.${prefix}_streak,(select count(*) from pattern_occurrences o where o.account_id=p.account_id and o.item_id=p.item_id) from pattern_items p where p.account_id=? and p.semantic_pattern_key=?",
                arrayOf(account, normalized(target.first) + "|" + normalized(target.second))).use {
                check(it.moveToFirst()); ReviewCapabilityState(it.getString(0), it.getInt(2), it.getString(0) != "VERIFIED" || it.getInt(1) < 3)
            }
        }
        else -> error("Unknown review capability")
    }
    check(result.status in setOf("PENDING", "LEARNING", "SELF_ASSESSED", "VERIFIED"))
    return result
}
