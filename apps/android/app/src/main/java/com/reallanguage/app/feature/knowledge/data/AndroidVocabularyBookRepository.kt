package com.reallanguage.app.feature.knowledge.data

import android.content.Context
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.VocabularyBookRepository
import com.reallanguage.app.feature.knowledge.domain.VocabularyLoadResult
import com.reallanguage.app.feature.knowledge.domain.VocabularyExample
import com.reallanguage.app.feature.knowledge.domain.VocabularySenseRecord
import com.reallanguage.app.feature.formation.domain.isPlausibleVocabularyDisplay
import com.reallanguage.app.feature.settings.domain.BasicVocabulary
import com.reallanguage.app.core.vocabulary.VocabularyCatalog
import com.reallanguage.app.core.vocabulary.AndroidVocabularyPoolPreference
import com.reallanguage.app.core.vocabulary.VocabularyPool
import com.reallanguage.app.core.vocabulary.bestMeaningMatch
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface CurrentVocabularyScopeSource {
    suspend fun load(): Set<String>?
}

class LocalFirstVocabularyScopeSource(
    private val runtime: SessionRuntime,
    private val preference: AndroidVocabularyPoolPreference,
    private val fallback: CurrentVocabularyScopeSource,
) : CurrentVocabularyScopeSource {
    override suspend fun load(): Set<String>? {
        val accountId = runtime.accountId ?: return null
        preference.read(accountId)?.let { return setOf(it.name) }
        val remote = fallback.load() ?: return null
        val selected = remote.singleOrNull() ?: return null
        if (!preference.write(accountId, selected)) return null
        return setOf(selected)
    }
}

class HttpCurrentVocabularyScopeSource(
    private val transport: HttpTransport,
    private val runtime: SessionRuntime,
) : CurrentVocabularyScopeSource {
    override suspend fun load(): Set<String>? {
        val token = runtime.accessToken ?: return null
        return try {
            val response = transport.execute(
                HttpRequest(
                    method = "GET",
                    path = "/api/v1/settings/current",
                    body = "",
                    headers = mapOf("Authorization" to "Bearer $token"),
                ),
            )
            if (response.statusCode != 200) return null
            val json = JSONObject(response.body)
            val basic = json.get("basicVocabulary")
            require(basic is String)
            val basicScope = BasicVocabulary.valueOf(basic)
            // The retired purpose-vocabulary array is intentionally ignored. Filtering is now
            // one indexed lookup against the single selected peer vocabulary pool.
            setOf(basicScope.name)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
}

class AndroidVocabularyBookRepository(
    context: Context,
    private val runtime: SessionRuntime,
    private val scopeSource: CurrentVocabularyScopeSource,
    private val vocabularyCatalog: VocabularyCatalog? = null,
) : VocabularyBookRepository {
    constructor(
        context: Context,
        runtime: SessionRuntime,
        scopeSource: CurrentVocabularyScopeSource,
    ) : this(context, runtime, scopeSource, null)

    private val applicationContext = context.applicationContext
    private val ownerAccountId = runtime.accountId
    private val ownerAccountEpoch = runtime.accountEpoch
    @Volatile private var cachedSavedRecords: VocabularyLoadResult.Ready? = null
    private fun ownerIsActive() = ownerAccountId != null && runtime.accountId == ownerAccountId &&
        runtime.accountEpoch == ownerAccountEpoch && runtime.accessToken != null

    override suspend fun loadAllRecords(): VocabularyLoadResult = withContext(Dispatchers.IO) {
        cachedSavedRecords?.takeIf { ownerIsActive() }?.let { return@withContext it }
        val accountId = ownerAccountId ?: return@withContext VocabularyLoadResult.Failed
        if (!ownerIsActive()) return@withContext VocabularyLoadResult.Failed
        try {
            val currentScopes = scopeSource.load()?.takeIf(Set<String>::isNotEmpty)
                ?: return@withContext VocabularyLoadResult.Failed
            if (!ownerIsActive()) return@withContext VocabularyLoadResult.Failed
            FormationDatabase(applicationContext).use { helper ->
            val db = helper.readableDatabase
            val records = db.rawQuery(
                """
                select v.item_id,v.display,v.meaning_zh,v.status,v.verified_success_count,v.rowid,
                  coalesce((select group_concat(w.wordlist, '') from vocabulary_wordlists w where w.account_id=v.account_id and w.item_id=v.item_id),''),
                  (select count(*) from vocabulary_occurrences o where o.account_id=v.account_id and o.item_id=v.item_id and o.is_focus=1),
                  (select count(*) from vocabulary_occurrences o where o.account_id=v.account_id and o.item_id=v.item_id and o.is_focus=0),
                  v.phonetic,v.pronunciation_audio_id,v.representative_scene
                from vocabulary_items v
                where v.account_id=?
                order by v.rowid
                """.trimIndent(),
                arrayOf(accountId),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val itemId = cursor.getString(0)
                        val display = cursor.getString(1)
                        if (!display.isPlausibleVocabularyDisplay()) continue
                        val examples = db.rawQuery(
                            "select text_en,text_zh,audio_id from vocabulary_examples where account_id=? and item_id=? order by example_order",
                            arrayOf(accountId, itemId),
                        ).use { exampleCursor -> buildList {
                            while (exampleCursor.moveToNext()) add(VocabularyExample(exampleCursor.getString(0), exampleCursor.getString(1), exampleCursor.getString(2)))
                        } }
                        add(
                            VocabularySenseRecord(
                                senseId = itemId,
                                englishForm = display,
                                meaningZh = cursor.getString(2),
                                status = when (cursor.getString(3)) {
                                    "PENDING" -> KnowledgeStatus.NOT_STARTED
                                    "SELF_ASSESSED" -> KnowledgeStatus.TENTATIVE
                                    "VERIFIED" -> KnowledgeStatus.MASTERED
                                    "LEARNING" -> KnowledgeStatus.LEARNING
                                    else -> error("Unknown saved vocabulary status")
                                },
                                effectiveLearnedCount = cursor.getInt(4),
                                learningScopes = cursor.getString(6).split('').filter(String::isNotBlank).toSet(),
                                enteredOrder = cursor.getLong(5),
                                phonetic = cursor.optionalMetadata(9),
                                pronunciationText = cursor.optionalMetadata(10),
                                representativeScene = cursor.optionalMetadata(11),
                                examples = examples,
                                focusOccurrenceCount = cursor.getInt(7),
                                contextOccurrenceCount = cursor.getInt(8),
                            ),
                        )
                    }
                }
            }
            val catalogBound = if (vocabularyCatalog == null) records else records.map { record ->
                val match = vocabularyCatalog.find(record.englishForm).bestMeaningMatch(record.meaningZh)
                if (match == null) record else record.copy(
                    catalogSenseId = match.senseId,
                    learningScopes = match.pools.mapTo(linkedSetOf()) { it.name },
                    phonetic = record.phonetic ?: match.phonetic,
                )
            }
            if (ownerIsActive()) {
                VocabularyLoadResult.Ready(
                    currentScopes = currentScopes,
                    records = catalogBound,
                    longestHeadword = catalogBound.maxByOrNull { it.englishForm.length }?.englishForm.orEmpty(),
                ).also { cachedSavedRecords = it }
            } else VocabularyLoadResult.Failed
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { VocabularyLoadResult.Failed }
    }

    override suspend fun loadPage(query: String, limit: Int, offset: Int): VocabularyLoadResult =
        loadCatalogPage(query, null, limit, offset)

    override suspend fun loadFromInitial(initial: Char, limit: Int, offset: Int): VocabularyLoadResult =
        loadCatalogPage("", initial.uppercaseChar(), limit, offset)

    private suspend fun loadCatalogPage(
        query: String,
        initial: Char?,
        limit: Int,
        offset: Int,
    ): VocabularyLoadResult =
        withContext(Dispatchers.IO) {
            val catalog = vocabularyCatalog ?: return@withContext loadAllRecords()
            val saved = loadAllRecords() as? VocabularyLoadResult.Ready
                ?: return@withContext VocabularyLoadResult.Failed
            if (!ownerIsActive()) return@withContext VocabularyLoadResult.Failed
            val selectedPool = saved.currentScopes.singleOrNull()?.let { raw ->
                runCatching { VocabularyPool.valueOf(raw) }.getOrNull()
            } ?: return@withContext VocabularyLoadResult.Failed
            try {
                val page = if (initial == null) catalog.browsePage(selectedPool, query, limit, offset)
                    else catalog.browsePageFromInitial(selectedPool, initial, limit, offset)
                val savedByCatalogSense = saved.records.mapNotNull { record ->
                    record.catalogSenseId?.let { it to record }
                }.toMap()
                val records = page.mapIndexed { index, catalogSense ->
                    val learned = savedByCatalogSense[catalogSense.senseId]
                    if (learned == null) {
                        VocabularySenseRecord(
                            senseId = catalogSense.senseId,
                            englishForm = catalogSense.display,
                            meaningZh = catalogSense.meaningZh,
                            status = KnowledgeStatus.NOT_STARTED,
                            effectiveLearnedCount = 0,
                            learningScopes = catalogSense.pools.mapTo(linkedSetOf()) { it.name },
                            enteredOrder = offset.toLong() * 1_000L + index,
                            phonetic = catalogSense.phonetic,
                            pronunciationText = catalogSense.display,
                            catalogSenseId = catalogSense.senseId,
                            partOfSpeech = catalogSense.partOfSpeech,
                        )
                    } else {
                        learned.copy(
                            senseId = catalogSense.senseId,
                            englishForm = catalogSense.display,
                            meaningZh = catalogSense.meaningZh,
                            learningScopes = catalogSense.pools.mapTo(linkedSetOf()) { it.name },
                            phonetic = learned.phonetic ?: catalogSense.phonetic,
                            pronunciationText = learned.pronunciationText ?: catalogSense.display,
                            catalogSenseId = catalogSense.senseId,
                            partOfSpeech = catalogSense.partOfSpeech,
                        )
                    }
                }
                if (!ownerIsActive()) VocabularyLoadResult.Failed else VocabularyLoadResult.Ready(
                    currentScopes = saved.currentScopes,
                    records = records,
                    hasMore = page.map { it.normalizedWord }.distinct().size >= limit,
                    longestHeadword = catalog.longestDisplay(selectedPool),
                )
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { VocabularyLoadResult.Failed }
        }
}

private fun android.database.Cursor.optionalMetadata(index: Int): String? =
    if (isNull(index)) null else getString(index)?.trim()?.takeIf {
        it.isNotEmpty() && !it.equals("null", ignoreCase = true)
    }
