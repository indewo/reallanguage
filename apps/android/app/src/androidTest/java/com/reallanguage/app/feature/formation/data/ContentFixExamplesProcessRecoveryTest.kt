package com.reallanguage.app.feature.formation.data

import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.learning.data.accountLearningAttemptsDirectory
import com.reallanguage.app.testing.withExplicitSource
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in write -> external force-stop -> read -> external force-stop -> verify.
 * Real repository/source/SQLite/Keystore only; not the formation-entry UI or real HTTP/AI. */
class ContentFixExamplesProcessRecoveryTest {
    @Test fun acceptedFirstTargetSurvivesProcessDeathAndFixedReplayNeedsNoProvider() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val requestedPhase = args.getString("contentFixExamplesPhase")
        assumeTrue(requestedPhase in setOf("write", "read", "verify"))
        val phase = requireNotNull(requestedPhase)
        val runId = requireNotNull(args.getString("runId")).also { require(UUID.fromString(it).toString() == it) }
        withContext(Dispatchers.IO) {
            val f = Fixture(phase, runId)
            var verified = false
            try {
                if (phase == "write") { f.assertKeys(false); f.seedForeignRows() }
                else f.assertKeys(true)
                val previousWrite = if (phase == "write") null else f.context.evidence("write")
                if (previousWrite != null) {
                    assertNotEquals("A real external process stop is required", previousWrite.getInt("pid"), Process.myPid())
                    assertEquals(2, previousWrite.getInt("calls"))
                    assertEquals(previousWrite.getString("protectedSqlSha"), f.sqlHash(true))
                }
                if (phase == "read") {
                    assertEquals(previousWrite!!.getString("allSqlSha"), f.sqlHash(false))
                    assertEquals(previousWrite.getString("cacheSha"), f.cacheSha())
                    f.assertOneEncryptedPayload()
                }
                val previousRead = if (phase == "verify") f.context.evidence("read") else null
                if (previousRead != null) {
                    assertNotEquals(previousRead.getInt("pid"), Process.myPid())
                    assertNotEquals(previousWrite!!.getInt("pid"), previousRead.getInt("pid"))
                    assertEquals(1, previousRead.getInt("calls"))
                    assertEquals(previousRead.getString("allSqlSha"), f.sqlHash(false))
                    assertEquals(previousRead.getString("cacheSha"), f.cacheSha())
                }
                val protectedBefore = f.sqlHash(true)
                val allBefore = f.sqlHash(false)
                val repository = f.repository()
                assertEquals(0, f.calls.size)
                // Only this explicit operation retries. No constructor/load auto-submits.
                val result = repository.fix(f.request)
                if (phase == "write") {
                    assertEquals(ContentFixResult.Failed, result)
                    assertEquals(listOf("t1", "t2"), f.calls.map { it.targetId })
                    assertEquals(allBefore, f.sqlHash(false))
                    f.assertOneEncryptedPayload()
                } else {
                    assertEquals(ContentFixResult.Fixed(f.persistentId("chapter", "c1")), result)
                    assertEquals(if (phase == "read") listOf("t2") else emptyList<String>(), f.calls.map { it.targetId })
                    f.assertFixedRowsAndSource()
                    assertTrue("A completed fix retires only its accepted-example cache", f.cacheFiles().none { it.extension == "payload" })
                }
                assertEquals(protectedBefore, f.sqlHash(true))
                f.assertKeys(true)
                val allAfter = f.sqlHash(false)
                val cacheAfter = f.cacheSha()
                if (phase == "verify") {
                    assertEquals(allBefore, allAfter)
                    assertEquals(previousRead!!.getString("cacheSha"), cacheAfter)
                } else {
                    val last = f.calls.last()
                    f.context.writeEvidence(phase, JSONObject().put("pid", Process.myPid()).put("calls", f.calls.size)
                        .put("allSqlSha", allAfter).put("protectedSqlSha", protectedBefore).put("cacheSha", cacheAfter)
                        .put("requestBSha", requestSha(last)).put("commandBSha", sha(last.commandId)))
                }
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putInt("pid", Process.myPid()); putInt("calls", f.calls.size)
                    putString("sqlSha", allAfter); putString("cacheSha", cacheAfter)
                })
                verified = phase == "verify"
            } finally {
                // Never dispose/clear write or read to masquerade as process death.
                // A failed phase preserves this UUID fixture; only successful verify cleans it.
                if (verified) f.deleteVerifiedFixture()
            }
        }
    }

    private class Fixture(private val phase: String, private val runId: String) {
        val context = ProcessContext(InstrumentationRegistry.getInstrumentation().targetContext, runId, phase == "write")
        val account = "content-fix-examples-$runId"
        private val accountHash = fixedSourceAccountHash(account)
        private val suffix = runId.replace("-", "")
        private val formationNamespace = "reallanguage.formation.payload.test.$suffix"
        private val sourceNamespace = "reallanguage.fixed-source.payload.test.$suffix"
        private val recoveryKeys = AndroidFormationPayloadKeys(formationNamespace, canUseAccount = { it == accountHash })
        private val sourceKeys = AndroidFixedSourcePayloadKeys(sourceNamespace, canUseAccount = { it == accountHash })
        private val sourceCipher = AesGcmFixedSourcePayloadCodec(sourceKeys)
        private val runtime = SessionRuntime().apply { update("ISOLATED_PROCESS_TOKEN", account) }
        val calls = mutableListOf<KnowledgeExampleGenerationRequest>()
        private val sentence = EnglishSentenceDraft("u1", "我经营一家咖啡店。", true,
            listOf(EnglishCandidate("e1", "I run a cafe.")))
        private val targets = listOf(
            LearningTarget("t1", "u1", "run", "经营", LearningTargetType.WORD, learningScopes = setOf("CET_4")),
            LearningTarget("t2", "u1", "cafe", "咖啡店", LearningTargetType.WORD, learningScopes = setOf("CET_4")),
        )
        val request = ContentFixRequest("flow-$runId", "fix-$runId", listOf(EnglishChapterDraft("c1", listOf(sentence))),
            mapOf("u1" to "e1"), targets, setOf("t1", "t2")).withExplicitSource(
                "我经营一家咖啡店。", listOf(FixedSourceUnit("u1", 0, "我经营一家咖啡店。")))

        fun repository() = AndroidContentFixRepository(context, runtime, sourceCipher = sourceCipher,
            exampleRecoveryKeys = recoveryKeys, knowledgeExamples = { plan ->
                calls += plan
                assertTrue("A fixed replay must never reach a provider", phase != "verify")
                val target = targets.single { it.targetId == plan.targetId }
                val expected = KnowledgeExampleGenerationRequest(target.targetId, KnowledgeTargetType.VOCABULARY,
                    target.display, target.explanationZh, listOf(KnowledgeSourceSentence("I run a cafe.", sentence.textZh)),
                    "${request.commandId}:${target.targetId}")
                assertEquals(expected, plan)
                if (phase == "read") {
                    assertEquals("Only the previously missing target may be requested", "t2", plan.targetId)
                    assertEquals(1, calls.size)
                    val previous = context.evidence("write")
                    assertEquals(previous.getString("requestBSha"), requestSha(plan))
                    assertEquals(previous.getString("commandBSha"), sha(plan.commandId))
                } else assertEquals(listOf("t1", "t2").take(calls.size), calls.map { it.targetId })
                if (phase == "write" && plan.targetId == "t2") KnowledgeExampleGenerationResult.Failed
                else KnowledgeExampleGenerationResult.Generated(details(plan.targetId))
            })

        fun persistentId(kind: String, id: String) = UUID.nameUUIDFromBytes(
            "${request.formationFlowId}:$kind:$id".toByteArray(Charsets.UTF_8)).toString()

        private fun details(id: String): KnowledgeTargetDetails {
            val target = targets.single { it.targetId == id }
            val english = if (id == "t1") listOf("I run a cafe after work.", "They run a small cafe.", "We run a cafe together.")
                else listOf("We meet at the cafe.", "The cafe opens early.", "I work near a cafe.")
            return KnowledgeTargetDetails(id, if (id == "t1") "/rʌn/" else "/ˈkæfeɪ/", target.display,
                "隔离经营咖啡店场景", english.mapIndexed { index, text ->
                    KnowledgeExampleDraft(text, "隔离完整中文例句${id}第${index + 1}条。", text)
                })
        }

        fun seedForeignRows() = FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            val foreign = "foreign-$account"
            db.execSQL("insert into formation_flows values (?,?,'FIXED','DIARY','隔离其他账号')", arrayOf(foreign, request.formationFlowId))
            db.execSQL("insert into chapters values (?,?,?,0,'COMPLETED')", arrayOf(foreign, request.formationFlowId, persistentId("chapter", "c1")))
            db.execSQL("insert into learning_module_progress values (?,?,?,'LR-03','{}')",
                arrayOf(foreign, persistentId("chapter", "c1"), persistentId("sentence", "u1")))
        }

        fun assertKeys(expected: Boolean) {
            listOf(recoveryKeys, sourceKeys).forEach { keys ->
                val key = keys.existing(accountHash, 1)
                if (expected) { assertNotNull(key); assertNull("Test Keystore keys remain non-exportable", key!!.encoded) }
                else assertNull("A fresh runId must not reuse a prior test key", key)
            }
        }

        fun assertFixedRowsAndSource() = FormationDatabase(context).use { helper ->
            val db = helper.readableDatabase
            val first = persistentId("chapter", "c1")
            assertEquals(request.sourceProjection, db.readFixedSource(account, request.formationFlowId, sourceCipher))
            assertTrue(db.matchesFixedSelection(account, request, first))
            fun count(table: String) = db.rawQuery("select count(*) from $table where account_id=?", arrayOf(account)).use {
                assertTrue(it.moveToFirst()); it.getInt(0)
            }
            assertEquals(1, count("fix_commands")); assertEquals(1, count("fixed_source_payloads"))
            assertEquals(1, count("chapters")); assertEquals(1, count("fixed_sentences"))
            assertEquals(2, count("target_selections")); assertEquals(2, count("target_vocabulary_details"))
            assertEquals(0, count("vocabulary_items")); assertEquals(0, count("pattern_items"))
            db.rawQuery("select first_chapter_id from fix_commands where account_id=? and command_id=?", arrayOf(account, request.commandId)).use {
                assertTrue(it.moveToFirst()); assertEquals(first, it.getString(0)); assertFalse(it.moveToNext())
            }
            targets.forEach { target ->
                val expected = details(target.targetId)
                db.rawQuery("select chapter_id,phonetic,pronunciation_text,representative_scene,examples_json from target_vocabulary_details where account_id=? and target_id=?",
                    arrayOf(account, persistentId("target", target.targetId))).use { cursor ->
                    assertTrue(cursor.moveToFirst()); assertEquals(first, cursor.getString(0))
                    assertEquals(expected.phonetic, cursor.getString(1)); assertEquals(expected.pronunciationText, cursor.getString(2))
                    assertEquals(expected.representativeScene, cursor.getString(3))
                    val examples = JSONArray(cursor.getString(4)); assertEquals(3, examples.length())
                    expected.examples.forEachIndexed { index, example ->
                        val stored = examples.getJSONObject(index)
                        assertEquals(example.textEn, stored.getString("textEn")); assertEquals(example.textZh, stored.getString("textZh"))
                        assertEquals(example.pronunciationText, stored.getString("pronunciationText"))
                    }
                    assertFalse(cursor.moveToNext())
                }
            }
        }

        private val recoveryDirectory get() = File(accountLearningAttemptsDirectory(context.filesDir, account), "fix-examples-recovery")
        fun cacheFiles(): List<File> {
            check(recoveryDirectory.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
            check(recoveryDirectory.isDirectory)
            return recoveryDirectory.walkTopDown().filter { it.isFile }.onEach {
                check(it.canonicalPath.startsWith(recoveryDirectory.canonicalPath + File.separator))
            }.toList()
        }
        fun cacheSha() = sha(cacheFiles().map { "${it.relativeTo(recoveryDirectory).path}:${sha(it.readBytes())}" }.sorted().joinToString("\n"))
        fun assertOneEncryptedPayload() {
            val file = cacheFiles().single { it.extension == "payload" }
            val bytes = file.readBytes()
            assertFalse(String(bytes, Charsets.UTF_8).contains("I run a cafe"))
            assertFalse(String(bytes, Charsets.UTF_8).contains("隔离完整中文例句"))
        }

        fun sqlHash(protectedOnly: Boolean) = FormationDatabase(context).use { helper ->
            val db = helper.readableDatabase
            val tables = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            val rows = tables.flatMap { table ->
                check(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
                db.rawQuery("select * from \"$table\"", null).use { c -> buildList {
                    add("TABLE:$table:${c.columnNames.joinToString(",")}")
                    while (c.moveToNext()) {
                        fun value(name: String): String? = c.getColumnIndex(name).takeIf { it >= 0 }?.let(c::getString)
                        val allowed = value("account_id") == account && when (table) {
                            "formation_flows", "fixed_source_payloads" -> value("flow_id") == request.formationFlowId
                            "chapters" -> value("flow_id") == request.formationFlowId && value("chapter_id") == persistentId("chapter", "c1")
                            "fixed_sentences" -> value("chapter_id") == persistentId("chapter", "c1") && value("unit_id") == persistentId("sentence", "u1")
                            "target_selections", "target_vocabulary_details" -> value("chapter_id") == persistentId("chapter", "c1") && value("target_id") in targets.map { persistentId("target", it.targetId) }
                            "fix_commands" -> value("command_id") == request.commandId
                            else -> false
                        }
                        if (!protectedOnly || !allowed) add(table + (0 until c.columnCount).joinToString("") { column ->
                            val text = when (c.getType(column)) { Cursor.FIELD_TYPE_NULL -> null
                                Cursor.FIELD_TYPE_BLOB -> sha(c.getBlob(column)); else -> c.getString(column) }
                            text?.let { "${it.length}:$it" } ?: "-1:"
                        })
                    }
                } }
            }.sorted()
            sha(rows.joinToString("\n"))
        }

        fun deleteVerifiedFixture() {
            val aliases = listOf(formationNamespace, sourceNamespace).map { "$it.$accountHash.v1" }
            context.deleteVerifiedFixture(aliases, accountHash)
        }
    }

    private class ProcessContext(base: Context, private val runId: String, create: Boolean) : ContextWrapper(base) {
        private val root = base.cacheDir.canonicalFile
        private val directory = File(root, "content-fix-examples-process-$runId")
        private val database = File(directory, "formation.db")
        private val files = File(directory, "files")
        init {
            check(directory.canonicalFile == directory.absoluteFile && directory.parentFile == root)
            if (create) { check(directory.mkdir()); check(files.mkdir()) }
            else check(directory.isDirectory && database.isFile && files.isDirectory)
            listOf(database, files).forEach { check(it.canonicalFile == it.absoluteFile) }
        }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = files
        override fun getNoBackupFilesDir() = files
        override fun getCacheDir() = directory
        override fun getDatabasePath(name: String): File { check(name == "real-language-learning.db"); return database }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        override fun deleteDatabase(name: String) = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        private fun evidenceFile(phase: String): File {
            check(phase in setOf("write", "read"))
            return File(directory, "$phase-evidence.json").also { check(it.canonicalFile == it.absoluteFile) }
        }
        fun evidence(phase: String) = JSONObject(evidenceFile(phase).also { check(it.length() in 1..16_384) }.readText()).also {
            check(it.getInt("version") == 1 && it.getString("runSha") == sha(runId))
        }
        fun writeEvidence(phase: String, data: JSONObject) {
            val file = evidenceFile(phase); check(file.createNewFile())
            val bytes = data.put("version", 1).put("runSha", sha(runId)).toString().toByteArray(Charsets.UTF_8)
            FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
        }
        fun deleteVerifiedFixture(aliases: List<String>, accountHash: String) {
            check(accountHash == fixedSourceAccountHash("content-fix-examples-$runId"))
            val suffix = runId.replace("-", "")
            check(aliases == listOf("reallanguage.formation.payload.test.$suffix.$accountHash.v1",
                "reallanguage.fixed-source.payload.test.$suffix.$accountHash.v1"))
            check(directory.name == "content-fix-examples-process-$runId" && directory.canonicalFile.parentFile == root)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); aliases.forEach { deleteEntry(it); check(!containsAlias(it)) } }
            check(deleteDatabase("real-language-learning.db")); check(directory.deleteRecursively())
        }
    }

    private companion object {
        fun requestSha(value: KnowledgeExampleGenerationRequest) = sha((listOf(value.targetId, value.targetType.name,
            value.display, value.meaningZh, value.commandId, value.sourceScene.size.toString()) +
            value.sourceScene.flatMap { listOf(it.textEn, it.textZh) }).joinToString("") { "${it.length}:$it" })
        fun sha(text: String) = sha(text.toByteArray(Charsets.UTF_8))
        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
