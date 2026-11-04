package com.reallanguage.app.core.app

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.util.AtomicFile
import com.reallanguage.app.feature.formation.data.AndroidFixedSourcePayloadKeys
import com.reallanguage.app.feature.formation.data.AndroidFormationPayloadKeys
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Metadata only. No source text, delivered explanation, audio or session credential belongs here. */
internal data class DiaryProcessArguments(val phase: String, val runId: String) {
    companion object {
        fun read(args: Bundle): DiaryProcessArguments? {
            val phase = args.getString("diaryProcessPhase")
            val run = args.getString("diaryProcessRunId")
            if (phase == null && run == null) return null
            require(phase in setOf("write", "read", "cleanup"))
            require(run != null && run.matches(UUID_PATTERN) && UUID.fromString(run).toString() == run)
            return DiaryProcessArguments(requireNotNull(phase), run)
        }
    }
}

internal data class DiaryProcessReady(
    val writePid: Int,
    val microphonePermission: Int,
    val identity: Map<String, String>,
    val tableDigests: Map<String, String>,
    val preferenceNames: Set<String>,
)

/** Test-only deterministic context. READ never has CREATE_IF_NECESSARY or a fallback path. */
internal class DiaryJourneyProcessFiles(private val base: Context, val runId: String, private val account: String) {
    val directory = File(base.cacheDir.canonicalFile, "diary-process-$runId")
    private val database = File(directory, "learning.db")
    private val ownership = File(directory, "ownership.json")
    val readyFile = File(directory, "write-ready.json")
    private val ownerHash = sha(account.toByteArray(Charsets.UTF_8))
    private val preferencePrefix = "diary-learning-entry-$runId-"
    @Volatile private var openMode = "CLOSED"

    init {
        require(runId.matches(UUID_PATTERN) && UUID.fromString(runId).toString() == runId)
        require(account == "diary-learning-entry-$runId")
        checkPaths()
    }

    val databaseContext: Context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File {
            require(name == "real-language-learning.db")
            checkPaths()
            return database
        }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = open(name, factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase = open(name, factory)
        override fun deleteDatabase(name: String): Boolean {
            getDatabasePath(name)
            return SQLiteDatabase.deleteDatabase(database)
        }
        private fun open(name: String, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase {
            getDatabasePath(name)
            check(openMode == "WRITE" || openMode == "READ") { "An explicit process phase must authorize this private database" }
            if (openMode == "READ") check(database.isFile && database.length() > 0) { "Missing write database; never create a read fixture" }
            return SQLiteDatabase.openDatabase(database.path, factory,
                if (openMode == "WRITE") SQLiteDatabase.CREATE_IF_NECESSARY else SQLiteDatabase.OPEN_READWRITE)
        }
    }

    @Synchronized fun beginFreshWrite() {
        checkPaths()
        check(!directory.exists()) { "A new explicit UUID is required; previous evidence must not be overwritten" }
        check(directory.mkdirs())
        writeOwnership(emptySet())
        openMode = "WRITE"
    }

    /** Must succeed before any Graph, SQL helper, preferences or encrypted source reader is used. */
    @Synchronized fun openExistingRead(): DiaryProcessReady {
        checkPaths()
        val preferences = readOwnership()
        check(database.isFile && database.length() > 0 && readyFile.isFile) { "WRITE_READY and its database are required" }
        val json = readJson(readyFile)
        require(json.keys().asSequence().toSet() == setOf("version", "state", "runId", "accountHash", "writePid", "microphonePermission", "identity", "tableDigests", "preferenceNames"))
        require(json.integer("version") == 1 && json.text("state") == "WRITE_READY")
        require(json.text("runId") == runId && json.text("accountHash") == ownerHash)
        val ids = json.getJSONObject("identity").stringMap()
        require(ids.keys == setOf("flow", "source", "chapter", "context", "firstFocus", "secondFocus", "word", "pattern"))
        require(ids.values.all { it.matches(UUID_PATTERN) && UUID.fromString(it).toString() == it })
        require(ids.values.toSet().size == ids.size)
        val digests = json.getJSONObject("tableDigests").stringMap()
        require(digests.isNotEmpty() && digests.keys.all { it.matches(Regex("[a-z_]+")) } && digests.values.all { it.matches(Regex("[a-f0-9]{64}")) })
        val names = json.getJSONArray("preferenceNames").uniqueStrings()
        require(names.all(::validPreference) && preferences.containsAll(names))
        val pid = json.integer("writePid").also { require(it > 0) }
        val permission = json.integer("microphonePermission").also { require(it in setOf(-1, 0)) }
        openMode = "READ"
        return DiaryProcessReady(pid, permission, ids, digests, names)
    }

    @Synchronized fun recordPreference(name: String) {
        require(validPreference(name))
        check(openMode in setOf("WRITE", "READ"))
        val names = readOwnership()
        if (name !in names) writeOwnership(names + name)
    }

    @Synchronized fun writeReady(value: DiaryProcessReady) {
        check(openMode == "WRITE" && database.isFile && !readyFile.exists())
        require(value.writePid > 0 && value.microphonePermission in setOf(-1, 0))
        require(value.identity.keys == setOf("flow", "source", "chapter", "context", "firstFocus", "secondFocus", "word", "pattern"))
        require(value.identity.values.all { it.matches(UUID_PATTERN) } && value.identity.values.toSet().size == value.identity.size)
        require(value.tableDigests.isNotEmpty() && value.tableDigests.values.all { it.matches(Regex("[a-f0-9]{64}")) })
        require(readOwnership() == value.preferenceNames)
        atomicJson(readyFile, JSONObject().put("version", 1).put("state", "WRITE_READY").put("runId", runId)
            .put("accountHash", ownerHash).put("writePid", value.writePid).put("microphonePermission", value.microphonePermission)
            .put("identity", JSONObject(value.identity)).put("tableDigests", JSONObject(value.tableDigests))
            .put("preferenceNames", JSONArray(value.preferenceNames.sorted())))
        check(readJson(readyFile).text("state") == "WRITE_READY")
    }

    /** Explicit cleanup also handles an aborted write, but never accepts an unrelated directory. */
    @Synchronized fun cleanExactRun() {
        checkPaths()
        if (!directory.exists()) return
        val names = readOwnership()
        // Check each entry before descending. Even a test-owned parent must not turn an
        // unexpected nested link into permission to remove a different directory.
        directory.walkTopDown().forEach { entry ->
            check(entry.canonicalFile == entry.absoluteFile &&
                (entry == directory || entry.path.startsWith(directory.path + File.separator)))
        }
        openMode = "CLOSED"
        SQLiteDatabase.deleteDatabase(database)
        check(!database.exists())
        AndroidFormationPayloadKeys().deleteAccount(ownerHash)
        AndroidFixedSourcePayloadKeys().deleteAccount(ownerHash)
        names.forEach { name -> require(validPreference(name)); base.deleteSharedPreferences(name) }
        check(directory.deleteRecursively() && !directory.exists())
    }

    private fun checkPaths() {
        check(directory.parentFile == base.cacheDir.canonicalFile && directory.name == "diary-process-$runId")
        check(directory.canonicalFile == directory.absoluteFile)
        listOf(database, ownership, readyFile).forEach { check(it.canonicalFile == it.absoluteFile && it.parentFile == directory) }
    }

    private fun validPreference(name: String) = name.startsWith(preferencePrefix) && name.length in (preferencePrefix.length + 1)..256 &&
        name.matches(Regex("[A-Za-z0-9_.-]+"))

    private fun readOwnership(): Set<String> {
        checkPaths()
        check(directory.isDirectory && ownership.isFile)
        val json = readJson(ownership)
        require(json.keys().asSequence().toSet() == setOf("version", "runId", "accountHash", "preferenceNames"))
        require(json.integer("version") == 1 && json.text("runId") == runId && json.text("accountHash") == ownerHash)
        return json.getJSONArray("preferenceNames").uniqueStrings().also { require(it.all(::validPreference)) }
    }

    private fun writeOwnership(names: Set<String>) {
        require(names.all(::validPreference))
        atomicJson(ownership, JSONObject().put("version", 1).put("runId", runId).put("accountHash", ownerHash)
            .put("preferenceNames", JSONArray(names.sorted())))
    }

    private fun atomicJson(file: File, json: JSONObject) {
        checkPaths()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }

    private fun readJson(file: File): JSONObject {
        check(file.isFile && file.length() in 1L..65_536L)
        return AtomicFile(file).openRead().use {
            val parser = JSONTokener(it.readBytes().toString(Charsets.UTF_8))
            val value = parser.nextValue()
            require(value is JSONObject && parser.nextClean() == '\u0000')
            value
        }
    }
}

/** Length-framed hashes of logical rows, including NULL versus text and all table names. */
internal fun diaryJourneyTableDigests(rows: Map<String, List<List<String?>>>): Map<String, String> = rows.toSortedMap().mapValues { (table, values) ->
    val digest = MessageDigest.getInstance("SHA-256")
    fun number(value: Int) { digest.update(ByteBuffer.allocate(4).putInt(value).array()) }
    fun text(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); number(bytes.size); digest.update(bytes) }
    text(table); number(values.size)
    values.forEach { row -> number(row.size); row.forEach { value ->
        if (value == null) number(-1) else text(value)
    } }
    digest.digest().joinToString("") { "%02x".format(it) }
}

private val UUID_PATTERN = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun JSONObject.text(key: String): String = get(key).let { require(it is String && it.isNotEmpty()); it }
private fun JSONObject.integer(key: String): Int = get(key).let { require(it is Int); it }
private fun JSONObject.stringMap(): Map<String, String> = keys().asSequence().associateWith { text(it) }
private fun JSONArray.strictStrings(): List<String> = List(length()) { index -> get(index).let { require(it is String); it } }
private fun JSONArray.uniqueStrings(): Set<String> = strictStrings().let { values ->
    require(values.distinct().size == values.size)
    values.toSet()
}
