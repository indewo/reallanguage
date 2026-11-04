package com.reallanguage.app.feature.home.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener

/** A locally recorded, explicitly confirmed server deletion. Contains no bearer or learning text. */
data class ConfirmedAccountDeletion(val accountId: String, val commandId: String)
sealed interface AccountDeletionCleanupLoad {
    data class Ready(val entries: List<ConfirmedAccountDeletion>) : AccountDeletionCleanupLoad
    data object Unavailable : AccountDeletionCleanupLoad
}
interface AccountDeletionCleanupJournal {
    suspend fun read(): AccountDeletionCleanupLoad
    suspend fun record(deletion: ConfirmedAccountDeletion): Boolean
    suspend fun complete(deletion: ConfirmedAccountDeletion): Boolean
}
object UnavailableAccountDeletionCleanupJournal : AccountDeletionCleanupJournal {
    override suspend fun read() = AccountDeletionCleanupLoad.Unavailable
    override suspend fun record(deletion: ConfirmedAccountDeletion) = false
    override suspend fun complete(deletion: ConfirmedAccountDeletion) = false
}
class AndroidAccountDeletionCleanupJournal(
    context: Context,
    private val directory: File = File(context.noBackupFilesDir, "account-deletion-cleanup"),
) : AccountDeletionCleanupJournal {
    override suspend fun read(): AccountDeletionCleanupLoad = withContext(Dispatchers.IO) {
        try { locked { dir ->
            recoverPreparedFiles(dir)
            AccountDeletionCleanupLoad.Ready(receipts(dir).map { readReceipt(it) })
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { AccountDeletionCleanupLoad.Unavailable }
    }
    override suspend fun record(deletion: ConfirmedAccountDeletion): Boolean = withContext(Dispatchers.IO) {
        try { locked { dir ->
            require(deletion.isValid())
            recoverPreparedFiles(dir)
            val target = child(dir, "${hash(deletion.accountId)}.pending")
            if (target.exists()) return@locked readReceipt(target) == deletion
            val prepared = child(dir, "${target.name}.new")
            val bytes = JSONObject().put("accountId", deletion.accountId).put("commandId", deletion.commandId).toString().toByteArray(Charsets.UTF_8)
            FileOutputStream(prepared).use { it.write(bytes); it.fd.sync() }
            check(readReceipt(prepared) == deletion)
            Files.move(prepared.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            forceDirectory(dir)
            readReceipt(target) == deletion
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
    override suspend fun complete(deletion: ConfirmedAccountDeletion): Boolean = withContext(Dispatchers.IO) {
        try { locked { dir ->
            require(deletion.isValid())
            recoverPreparedFiles(dir)
            val target = child(dir, "${hash(deletion.accountId)}.pending")
            if (!target.exists()) return@locked true
            if (readReceipt(target) != deletion) return@locked false
            Files.delete(target.toPath())
            forceDirectory(dir)
            !target.exists()
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    private fun <T> locked(action: (File) -> T): T = synchronized(journalLock) {
        require(!Files.isSymbolicLink(directory.toPath()))
        check(directory.isDirectory || directory.mkdirs())
        val dir = directory.canonicalFile
        val lock = child(dir, LOCK_FILE)
        FileChannel.open(lock.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val files = requireNotNull(dir.listFiles())
                require(files.all { file -> file.name == LOCK_FILE || RECEIPT_NAME.matches(file.name) || PREPARED_NAME.matches(file.name) })
                files.forEach { child(dir, it.name); require(it.isFile) }
                action(dir)
            }
        }
    }
    private fun receipts(dir: File) = requireNotNull(dir.listFiles()).filter { RECEIPT_NAME.matches(it.name) }.sortedBy { it.name }
    private fun recoverPreparedFiles(dir: File) {
        requireNotNull(dir.listFiles()).filter { PREPARED_NAME.matches(it.name) }.forEach { prepared ->
            val value = readReceipt(prepared)
            val target = child(dir, prepared.name.removeSuffix(".new"))
            if (target.exists()) {
                require(readReceipt(target) == value)
                Files.delete(prepared.toPath())
            } else {
                FileChannel.open(prepared.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
                Files.move(prepared.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            forceDirectory(dir)
        }
    }
    private fun readReceipt(file: File): ConfirmedAccountDeletion {
        require(file.isFile && file.length() in 1..8192 && !Files.isSymbolicLink(file.toPath()))
        val bytes = file.readBytes()
        require(bytes.size <= 8192)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        val parser = JSONTokener(text)
        val json = parser.nextValue() as? JSONObject ?: error("Deletion receipt is invalid")
        require(parser.nextClean() == '\u0000' && json.keys().asSequence().toSet() == setOf("accountId", "commandId"))
        val account = json.get("accountId"); val command = json.get("commandId")
        require(account is String && command is String)
        return ConfirmedAccountDeletion(account, command).also {
            require(it.isValid() && file.name.removeSuffix(".new") == "${hash(it.accountId)}.pending")
        }
    }
    private fun child(dir: File, name: String): File = File(dir, name).also {
        require(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == dir)
    }
    private fun forceDirectory(dir: File) { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private companion object {
        val journalLock = Any()
        const val LOCK_FILE = ".deletion.lock"
        val RECEIPT_NAME = Regex("[a-f0-9]{64}\\.pending")
        val PREPARED_NAME = Regex("[a-f0-9]{64}\\.pending\\.new")
    }
}

internal fun ConfirmedAccountDeletion.isValid(): Boolean = accountId.isNotBlank() && accountId.length <= 256 &&
    commandId.isNotBlank() && commandId.length <= 128 && accountId.none { it.isISOControl() } && commandId.none { it.isISOControl() }
