package com.reallanguage.app.feature.home.data

import android.content.Context
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.data.accountLearningAttemptsDirectory
import com.reallanguage.app.feature.recording.data.accountRecordingsDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.reallanguage.app.feature.formation.data.FormationPayloadKeys
import com.reallanguage.app.feature.formation.data.AndroidFixedSourcePayloadKeys
import com.reallanguage.app.feature.formation.data.fixedSourceAccountHash
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class AndroidAccountDataCleaner(context: Context,
    // Deletion has its own exact-account capability; it may verify removal after normal access is revoked.
    private val fixedSourceKeys: FormationPayloadKeys = AndroidFixedSourcePayloadKeys(canUseAccount = { true }),
    private val learningFeedbackKeys: FormationPayloadKeys = com.reallanguage.app.feature.learning.data.AndroidReadSpeakPayloadKeys(canUseAccount = { true }),
    private val fullSpeakingKeys: FormationPayloadKeys = com.reallanguage.app.feature.learning.data.AndroidFullSpeakingPayloadKeys(canUseAccount = { true }),
    private val patternSpeakingKeys: FormationPayloadKeys = com.reallanguage.app.feature.learning.data.AndroidPatternSpeakingPayloadKeys(canUseAccount = { true }),
) : LocalAccountDataCleaner {
    private val applicationContext = context.applicationContext
    override suspend fun clear(accountId: String): Boolean = withContext(Dispatchers.IO) {
        if (accountId.isBlank()) return@withContext false
        try {
            FormationDatabase(applicationContext).use { helper ->
              val database = helper.writableDatabase
              database.beginTransaction()
              try {
                val tables = database.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%'", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                tables.forEach { table ->
                    val quoted = table.replace("\"", "\"\"")
                    val hasAccount = database.rawQuery("pragma table_info(\"$quoted\")", null).use { cursor ->
                        var found = false
                        while (cursor.moveToNext()) if (cursor.getString(1) == "account_id") found = true
                        found
                    }
                    if (hasAccount) {
                        database.delete("\"$quoted\"", "account_id = ?", arrayOf(accountId))
                        database.rawQuery("select count(*) from \"$quoted\" where account_id=?", arrayOf(accountId)).use {
                            check(it.moveToFirst() && it.getLong(0) == 0L)
                        }
                    }
                }
                database.setTransactionSuccessful()
              } finally {
                database.endTransaction()
              }
            }
            val recordings = accountRecordingsDirectory(applicationContext.filesDir, accountId)
            val attempts = accountLearningAttemptsDirectory(applicationContext.filesDir, accountId)
            check(deleteOwnedDirectory(recordings) && deleteOwnedDirectory(attempts))
            val accountHash = fixedSourceAccountHash(accountId)
            fixedSourceKeys.deleteAccount(accountHash)
            check(fixedSourceKeys.existing(accountHash, 1) == null)
            learningFeedbackKeys.deleteAccount(accountHash)
            check(learningFeedbackKeys.existing(accountHash, 1) == null)
            fullSpeakingKeys.deleteAccount(accountHash)
            check(fullSpeakingKeys.existing(accountHash, 1) == null)
            patternSpeakingKeys.deleteAccount(accountHash)
            check(patternSpeakingKeys.existing(accountHash, 1) == null)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    /** Do not follow a link into another account or outside the private per-account directory. */
    private fun deleteOwnedDirectory(directory: File): Boolean {
        val path = directory.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
        return !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
    }
}
