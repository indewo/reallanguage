package com.reallanguage.app.core.vocabulary

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS
import android.database.sqlite.SQLiteDatabase.OPEN_READONLY
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AndroidEmbeddedVocabularyCatalog(context: Context) : VocabularyCatalog {
    private val applicationContext = context.applicationContext
    private val installMutex = Mutex()
    private val expectation by lazy(::readExpectation)
    @Volatile private var opened: SQLiteDatabase? = null
    private val longestDisplayByPool = ConcurrentHashMap<VocabularyPool, String>()

    override suspend fun find(
        surfaceForm: String,
        pool: VocabularyPool?,
    ): List<VocabularyCatalogSense> = withContext(Dispatchers.IO) {
        val normalized = normalizeVocabularyForm(surfaceForm)
        if (normalized.isBlank()) return@withContext emptyList()
        val poolClause = if (pool == null) "" else "and p.pool=?"
        val arguments = if (pool == null) arrayOf(normalized) else arrayOf(normalized, pool.name)
        database().rawQuery(
            """
            select s.sense_id,w.word_id,w.display,w.normalized_word,w.initial,w.item_type,
              s.part_of_speech,s.meaning_zh,w.phonetic,
              group_concat(p.pool, char(31)),
              min(case f.match_kind when 'EXACT' then 0 else 1 end)
            from catalog_forms f
            join catalog_words w on w.word_id=f.word_id
            join catalog_senses s on s.word_id=w.word_id
            join catalog_pool_senses p on p.sense_id=s.sense_id
            where f.normalized_form=? $poolClause
            group by s.sense_id,w.word_id,w.display,w.normalized_word,w.initial,w.item_type,
              s.part_of_speech,s.meaning_zh,w.phonetic
            order by 11,w.normalized_word,s.part_of_speech,s.meaning_zh
            """.trimIndent(),
            arguments,
        ).use(::readSenses)
    }

    override suspend fun browse(
        pool: VocabularyPool,
        initial: Char,
        limit: Int,
        offset: Int,
    ): List<VocabularyCatalogSense> = withContext(Dispatchers.IO) {
        require(limit in 1..500)
        require(offset >= 0)
        val letter = initial.uppercaseChar().toString()
        require(letter.single() in 'A'..'Z' || letter == "#")
        database().rawQuery(
            """
            select s.sense_id,w.word_id,w.display,w.normalized_word,w.initial,w.item_type,
              s.part_of_speech,s.meaning_zh,w.phonetic,
              (select group_concat(all_pools.pool, char(31)) from catalog_pool_senses all_pools
                where all_pools.sense_id=s.sense_id),0
            from catalog_pool_senses selected
            join catalog_senses s on s.sense_id=selected.sense_id
            join catalog_words w on w.word_id=s.word_id
            where selected.pool=? and selected.initial=?
            order by selected.normalized_word,s.part_of_speech,s.meaning_zh
            limit ? offset ?
            """.trimIndent(),
            arrayOf(pool.name, letter, limit.toString(), offset.toString()),
        ).use(::readSenses)
    }

    override suspend fun browsePage(
        pool: VocabularyPool,
        query: String,
        limit: Int,
        offset: Int,
    ): List<VocabularyCatalogSense> = withContext(Dispatchers.IO) {
        require(limit in 1..500)
        require(offset >= 0)
        val normalizedQuery = normalizeVocabularyForm(query)
        val queryClause = if (normalizedQuery.isBlank()) "" else "and normalized_word like ? escape '\\'"
        val queryArguments = if (normalizedQuery.isBlank()) emptyList() else listOf(
            normalizedQuery.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%",
        )
        val arguments = buildList {
            add(pool.name)
            addAll(queryArguments)
            add(limit.toString())
            add(offset.toString())
            add(pool.name)
        }.toTypedArray()
        database().rawQuery(
            """
            with page_words as (
              select normalized_word
              from catalog_pool_senses
              where pool=? $queryClause
              group by normalized_word
              order by normalized_word
              limit ? offset ?
            )
            select s.sense_id,w.word_id,w.display,w.normalized_word,w.initial,w.item_type,
              s.part_of_speech,s.meaning_zh,w.phonetic,
              (select group_concat(all_pools.pool, char(31)) from catalog_pool_senses all_pools
                where all_pools.sense_id=s.sense_id),0
            from catalog_pool_senses selected
            join page_words page on page.normalized_word=selected.normalized_word
            join catalog_senses s on s.sense_id=selected.sense_id
            join catalog_words w on w.word_id=s.word_id
            where selected.pool=?
            order by selected.normalized_word,s.part_of_speech,s.meaning_zh
            """.trimIndent(),
            arguments,
        ).use(::readSenses)
    }

    override suspend fun browsePageFromInitial(
        pool: VocabularyPool,
        initial: Char,
        limit: Int,
        offset: Int,
    ): List<VocabularyCatalogSense> = withContext(Dispatchers.IO) {
        require(limit in 1..500)
        require(offset >= 0)
        val letter = initial.uppercaseChar()
        require(letter in 'A'..'Z')
        val arguments = arrayOf(
            pool.name,
            letter.toString(),
            limit.toString(),
            offset.toString(),
            pool.name,
        )
        database().rawQuery(
            """
            with page_words as (
              select normalized_word
              from catalog_pool_senses
              where pool=? and initial>=?
              group by normalized_word
              order by normalized_word
              limit ? offset ?
            )
            select s.sense_id,w.word_id,w.display,w.normalized_word,w.initial,w.item_type,
              s.part_of_speech,s.meaning_zh,w.phonetic,
              (select group_concat(all_pools.pool, char(31)) from catalog_pool_senses all_pools
                where all_pools.sense_id=s.sense_id),0
            from catalog_pool_senses selected
            join page_words page on page.normalized_word=selected.normalized_word
            join catalog_senses s on s.sense_id=selected.sense_id
            join catalog_words w on w.word_id=s.word_id
            where selected.pool=?
            order by selected.normalized_word,s.part_of_speech,s.meaning_zh
            """.trimIndent(),
            arguments,
        ).use(::readSenses)
    }

    override suspend fun longestDisplay(pool: VocabularyPool): String = withContext(Dispatchers.IO) {
        longestDisplayByPool[pool] ?: database().rawQuery(
            """
            select w.display
            from catalog_pool_senses selected
            join catalog_senses s on s.sense_id=selected.sense_id
            join catalog_words w on w.word_id=s.word_id
            where selected.pool=?
            group by w.word_id,w.display
            order by length(w.display) desc,w.display
            limit 1
            """.trimIndent(),
            arrayOf(pool.name),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else ""
        }.also { longestDisplayByPool.putIfAbsent(pool, it) }
    }

    override suspend fun metadata(): VocabularyCatalogMetadata = withContext(Dispatchers.IO) {
        val values = database().rawQuery(
            "select key,value from catalog_metadata",
            emptyArray(),
        ).use { cursor -> buildMap {
            while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
        } }
        VocabularyCatalogMetadata(
            schemaVersion = values.getValue("schema_version").toInt(),
            dataVersion = values.getValue("data_version"),
            wordCount = values.getValue("word_count").toInt(),
            senseCount = values.getValue("sense_count").toInt(),
        )
    }

    private suspend fun database(): SQLiteDatabase {
        opened?.takeIf(SQLiteDatabase::isOpen)?.let { return it }
        return installMutex.withLock {
            opened?.takeIf(SQLiteDatabase::isOpen)?.let { return@withLock it }
            val installed = installedFile()
            val database = SQLiteDatabase.openDatabase(
                installed.absolutePath,
                null,
                OPEN_READONLY or NO_LOCALIZED_COLLATORS,
            )
            check(database.matches(expectation))
            opened = database
            database
        }
    }

    private fun installedFile(): File {
        val directory = File(applicationContext.noBackupFilesDir, "vocabulary-catalog").apply {
            check(exists() || mkdirs())
        }
        val destination = File(directory, DATABASE_FILE)
        if (destination.isCompatibleCatalog()) return destination
        val temporary = File(directory, "$DATABASE_FILE.tmp")
        if (temporary.exists()) check(temporary.delete())
        applicationContext.assets.open(ASSET_PATH).use { input ->
            FileOutputStream(temporary).use { output ->
                input.copyTo(output, COPY_BUFFER_BYTES)
                output.fd.sync()
            }
        }
        check(temporary.isCompatibleCatalog())
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        return destination
    }

    private fun File.isCompatibleCatalog(): Boolean {
        if (!isFile || length() <= 0L) return false
        return runCatching {
            SQLiteDatabase.openDatabase(absolutePath, null, OPEN_READONLY or NO_LOCALIZED_COLLATORS).use { db ->
                db.matches(expectation) &&
                    db.rawQuery("pragma quick_check", emptyArray()).use { it.moveToFirst() && it.getString(0) == "ok" }
            }
        }.getOrDefault(false)
    }

    private fun readExpectation(): CatalogExpectation {
        val manifest = applicationContext.assets.open(MANIFEST_ASSET_PATH).bufferedReader(Charsets.UTF_8).use {
            JSONObject(it.readText())
        }
        val pools = manifest.getJSONArray("pools").let { array ->
            buildSet {
                for (index in 0 until array.length()) add(VocabularyPool.valueOf(array.getString(index)))
            }
        }
        check(pools == VocabularyPool.values().toSet())
        return CatalogExpectation(
            schemaVersion = manifest.getInt("schemaVersion").toString(),
            dataVersion = manifest.getString("dataVersion"),
            wordCount = manifest.getInt("wordCount").toString(),
            senseCount = manifest.getInt("senseCount").toString(),
            pools = pools,
        )
    }

    private fun SQLiteDatabase.matches(expected: CatalogExpectation): Boolean =
        metadataValue("schema_version") == expected.schemaVersion &&
            metadataValue("data_version") == expected.dataVersion &&
            metadataValue("word_count") == expected.wordCount &&
            metadataValue("sense_count") == expected.senseCount &&
            metadataValue("pool_ids")
                ?.split(',')
                ?.mapTo(linkedSetOf()) { VocabularyPool.valueOf(it) } == expected.pools

    private fun SQLiteDatabase.metadataValue(key: String): String? = rawQuery(
        "select value from catalog_metadata where key=?",
        arrayOf(key),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val value = cursor.getString(0)
        if (cursor.moveToNext()) null else value
    }

    private fun readSenses(cursor: Cursor): List<VocabularyCatalogSense> = buildList {
        while (cursor.moveToNext()) {
            add(
                VocabularyCatalogSense(
                    senseId = cursor.getString(0),
                    wordId = cursor.getString(1),
                    display = cursor.getString(2),
                    normalizedWord = cursor.getString(3),
                    initial = cursor.getString(4),
                    itemType = cursor.getString(5),
                    partOfSpeech = cursor.getString(6),
                    meaningZh = cursor.getString(7),
                    phonetic = if (cursor.isNull(8)) null else cursor.getString(8),
                    pools = cursor.getString(9).split('\u001f').mapTo(linkedSetOf()) { VocabularyPool.valueOf(it) },
                    matchKind = if (cursor.getInt(10) == 0) VocabularyMatchKind.EXACT else VocabularyMatchKind.INFLECTION,
                ),
            )
        }
    }

    private companion object {
        const val ASSET_PATH = "vocabulary/reallanguage_vocabulary_v1.db"
        const val MANIFEST_ASSET_PATH = "vocabulary/reallanguage_vocabulary_v1.manifest.json"
        const val DATABASE_FILE = "reallanguage-vocabulary-v1.db"
        const val COPY_BUFFER_BYTES = 128 * 1024
    }

    private data class CatalogExpectation(
        val schemaVersion: String,
        val dataVersion: String,
        val wordCount: String,
        val senseCount: String,
        val pools: Set<VocabularyPool>,
    )
}
