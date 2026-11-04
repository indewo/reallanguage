package com.reallanguage.app.feature.formation.data

import android.content.Context
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.knowledge.data.CurrentVocabularyScopeSource
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AndroidEnglishKnowledgeSnapshotSource(
    context: Context,
    private val runtime: SessionRuntime,
    private val scopeSource: CurrentVocabularyScopeSource,
    private val selection: KnownKnowledgeSelection = KnownKnowledgeSelection(),
) : EnglishKnowledgeSnapshotSource {
    private val applicationContext = context.applicationContext
    private val owner = runtime.accountId
    private val ownerEpoch = runtime.accountEpoch
    override suspend fun freeze(baseCommandId: String, requestDigest: String): KnownKnowledgeSnapshotResult = withContext(Dispatchers.IO) {
        val account = owner ?: return@withContext KnownKnowledgeSnapshotResult.Failed
        val revision = runtime.identityRevision.value
        fun active() = runtime.accountId == account && runtime.accessToken != null && runtime.accountEpoch == ownerEpoch && runtime.identityRevision.value == revision
        if (!active() || baseCommandId.isBlank() || baseCommandId.length > 128 || requestDigest.isBlank() || requestDigest.length > 128) return@withContext KnownKnowledgeSnapshotResult.Failed
        try {
            FormationDatabase(applicationContext).use { helper ->
                val db = helper.writableDatabase
                db.readSnapshot(account, baseCommandId, requestDigest)?.let {
                    check(active()); return@withContext KnownKnowledgeSnapshotResult.Ready(it)
                }
                val scopes = scopeSource.load()?.takeIf { it.isNotEmpty() } ?: return@withContext KnownKnowledgeSnapshotResult.Failed
                currentCoroutineContext().ensureActive(); check(active())
                db.beginTransaction()
                try {
                    val selected = db.readSnapshot(account, baseCommandId, requestDigest) ?: selection.select(db.readKnownEntries(account), scopes).also { candidates ->
                        val entries = JSONArray(candidates.map { JSONObject().put("ref", it.ref).put("contentHash", knowledgeContentHash(it)) })
                        db.insertOrThrow("english_generation_knowledge_snapshots", null, ContentValues().apply {
                            put("account_id", account); put("base_command_id", baseCommandId); put("request_digest", requestDigest); put("entries_json", entries.toString())
                        })
                    }
                    currentCoroutineContext().ensureActive(); check(active())
                    db.setTransactionSuccessful()
                    KnownKnowledgeSnapshotResult.Ready(selected)
                } finally { db.endTransaction() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { KnownKnowledgeSnapshotResult.Failed }
    }

    private fun SQLiteDatabase.readSnapshot(account: String, base: String, digest: String): List<KnownKnowledgeCandidate>? = rawQuery(
        "select request_digest,entries_json from english_generation_knowledge_snapshots where account_id=? and base_command_id=?", arrayOf(account, base),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        check(cursor.getString(0) == digest)
        val array = JSONArray(cursor.getString(1))
        check(array.length() <= 16)
        val candidates = List(array.length()) { index ->
            val entry = array.getJSONObject(index)
            check(entry.keys().asSequence().toSet() == setOf("ref", "contentHash"))
            val ref = entry.get("ref") as? String ?: error("Invalid frozen knowledge reference")
            val hash = entry.get("contentHash") as? String ?: error("Invalid frozen knowledge fingerprint")
            val candidate = readKnownCandidate(account, ref) ?: error("Frozen knowledge no longer available")
            check(hash == knowledgeContentHash(candidate))
            candidate
        }
        check(candidates.map { it.ref }.distinct().size == candidates.size)
        check(candidates.sumOf { it.display.codePointCount(0, it.display.length) + it.meaningZh.codePointCount(0, it.meaningZh.length) } <= 4000)
        candidates
    }
}

class AndroidEnglishKnowledgeSnapshotCleaner(context: Context) {
    private val applicationContext = context.applicationContext
    suspend fun clear(accountId: String): Boolean = withContext(Dispatchers.IO) {
        if (accountId.isBlank()) return@withContext false
        if (!applicationContext.getDatabasePath("real-language-learning.db").exists()) return@withContext true
        try { FormationDatabase(applicationContext).use { it.writableDatabase.delete("english_generation_knowledge_snapshots", "account_id=?", arrayOf(accountId)) }; true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
}

internal fun SQLiteDatabase.readKnownCandidate(account: String, ref: String): KnownKnowledgeCandidate? {
    check(isValidKnowledgeRef(ref))
    val type = KnownKnowledgeType.valueOf(ref.substringBefore(':'))
    val table = if (type == KnownKnowledgeType.PATTERN) "pattern_items" else "vocabulary_items"
    val display = if (type == KnownKnowledgeType.PATTERN) "structure_en" else "display"
    return rawQuery("select $display,meaning_zh from $table where account_id=? and item_id=?", arrayOf(account, ref.substringAfter(':'))).use {
        if (!it.moveToFirst()) null else KnownKnowledgeCandidate(ref, type, it.getString(0), it.getString(1)).also { item -> check(item.isValid()) }
    }
}

private fun SQLiteDatabase.readKnownEntries(account: String): List<KnownKnowledgeEntry> = buildList {
    fun priority(status: String) = when(status) { "PENDING" -> 0; "LEARNING" -> 1; "SELF_ASSESSED" -> 2; "VERIFIED" -> 3; else -> error("Invalid stored knowledge status") }
    rawQuery("select v.item_id,v.display,v.meaning_zh,v.status,v.rowid,(select count(*) from vocabulary_occurrences o where o.account_id=v.account_id and o.item_id=v.item_id) from vocabulary_items v where v.account_id=? order by v.rowid", arrayOf(account)).use { c -> while(c.moveToNext()) {
        val id = c.getString(0)
        val scopes = rawQuery("select wordlist from vocabulary_wordlists where account_id=? and item_id=?", arrayOf(account, id)).use { w -> buildSet { while(w.moveToNext()) add(w.getString(0)) } }
        add(KnownKnowledgeEntry(KnownKnowledgeCandidate("VOCABULARY:$id", KnownKnowledgeType.VOCABULARY, c.getString(1), c.getString(2)), priority(c.getString(3)), c.getLong(5), c.getLong(4), scopes))
    } }
    rawQuery("select p.item_id,p.structure_en,p.meaning_zh,p.writing_status,p.speaking_status,p.rowid,(select count(*) from pattern_occurrences o where o.account_id=p.account_id and o.item_id=p.item_id) from pattern_items p where p.account_id=? order by p.rowid", arrayOf(account)).use { c -> while(c.moveToNext()) {
        add(KnownKnowledgeEntry(KnownKnowledgeCandidate("PATTERN:${c.getString(0)}", KnownKnowledgeType.PATTERN, c.getString(1), c.getString(2)), minOf(priority(c.getString(3)), priority(c.getString(4))), c.getLong(6), c.getLong(5)))
    } }
}

internal fun knowledgeDigest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
private fun knowledgeContentHash(candidate: KnownKnowledgeCandidate) = knowledgeDigest(listOf(candidate.ref, candidate.type.name, candidate.display, candidate.meaningZh).joinToString("") { "${it.length}:$it" })
