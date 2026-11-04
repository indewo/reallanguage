package com.reallanguage.app.feature.formation.data

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

sealed interface FormationPayloadLoad {
    data object Missing : FormationPayloadLoad
    data object Unavailable : FormationPayloadLoad
    class Loaded(val identity: FormationPayloadIdentity, val plaintext: ByteArray) : FormationPayloadLoad
}
sealed interface FormationPayloadSave {
    data class Saved(val revision: Long) : FormationPayloadSave
    data object Conflict : FormationPayloadSave
    data object Failed : FormationPayloadSave
}
sealed interface FormationPayloadListing {
    data class Listed(val identities: List<FormationPayloadIdentity>) : FormationPayloadListing
    data object Unavailable : FormationPayloadListing
}
enum class FormationPayloadPreservation { Preserved, Missing, BecameReadable, Failed }
enum class FormationPayloadRemoval { Removed, Conflict, Failed }
enum class FormationAccountClear { Cleared, Failed }

fun interface FormationAtomicFileCommitter {
    fun replace(target: File, envelope: ByteArray, canCommit: () -> Boolean)
}

/** No raw source text is used as a filename, and no network permission is implied by local data. */
class FormationRecoveryFileStore(
    private val directory: File,
    private val cipher: FormationPayloadCipher,
    private val canAccess: (String) -> Boolean,
    private val committer: FormationAtomicFileCommitter? = null,
    private val canClearAccount: ((String) -> Boolean)? = null,
) {
    /** A final lightweight gate after a caller decodes private data, without another file read. */
    internal fun hasCurrentAccess(owner: FormationPayloadOwner): Boolean = try {
        owner.isValid() && canAccess(owner.accountSubjectHash)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }

    suspend fun discard(owner: FormationPayloadOwner, expectedRevision: Long): FormationPayloadRemoval = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        val allowed = { job?.isActive != false && canAccess(owner.accountSubjectHash) }
        try {
            require(owner.isValid() && expectedRevision >= 0 && allowed())
            withAccountLock(owner.accountSubjectHash) { accountDirectory ->
                require(allowed())
                val target = payloadFile(accountDirectory, owner)
                val tombstone = tombstoneFile(target)
                val removedRevision = readTombstone(tombstone)
                if (removedRevision != null && removedRevision != expectedRevision)
                    return@withAccountLock FormationPayloadRemoval.Conflict
                when (val stored = readPayload(owner, target)) {
                    FormationPayloadLoad.Unavailable -> return@withAccountLock FormationPayloadRemoval.Failed
                    FormationPayloadLoad.Missing -> if (removedRevision == expectedRevision) {
                        require(allowed())
                        return@withAccountLock FormationPayloadRemoval.Removed
                    } else return@withAccountLock FormationPayloadRemoval.Conflict
                    is FormationPayloadLoad.Loaded -> {
                        stored.plaintext.fill(0)
                        if (stored.identity.revision != expectedRevision) return@withAccountLock FormationPayloadRemoval.Conflict
                    }
                }
                require(allowed())
                if (removedRevision == null) {
                    val marker = ByteBuffer.allocate(TOMBSTONE_BYTES).putInt(TOMBSTONE_MAGIC).putInt(1).putLong(expectedRevision).array()
                    (committer ?: AtomicFormationFileCommitter).replace(tombstone, marker, allowed)
                }
                require(readTombstone(tombstone) == expectedRevision && allowed())
                Files.deleteIfExists(target.toPath())
                forceDirectory(accountDirectory)
                require(!target.exists() && readTombstone(tombstone) == expectedRevision && allowed())
                FormationPayloadRemoval.Removed
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { FormationPayloadRemoval.Failed }
    }

    /** This separate, exact-account capability is never inferred from ordinary read/write access. */
    suspend fun clearAccount(accountSubjectHash: String): FormationAccountClear = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        val allowed = { job?.isActive != false && canClearAccount?.invoke(accountSubjectHash) == true }
        try {
            require(validFormationAccountHash(accountSubjectHash) && allowed())
            withAccountLock(accountSubjectHash) { accountDirectory ->
                require(allowed())
                val files = requireNotNull(accountDirectory.listFiles()).filterNot { it.name == LOCK_FILE }
                // Do not recursively erase unexpected directories or files, even inside this scoped directory.
                require(files.all { file -> file.isFile && safeChild(accountDirectory, file.name) == file.canonicalFile &&
                    (PAYLOAD_NAME.matches(file.name) || TOMBSTONE_NAME.matches(file.name) || PRESERVED_NAME.matches(file.name) ||
                        (file.name.startsWith(".formation-") && file.name.endsWith(".pending"))) })
                files.forEach { file -> require(allowed()); Files.delete(file.toPath()) }
                forceDirectory(accountDirectory)
                require(accountDirectory.listFiles()?.all { it.name == LOCK_FILE } == true && allowed())
                cipher.deleteAccountKey(accountSubjectHash)
                require(accountDirectory.listFiles()?.all { it.name == LOCK_FILE } == true && allowed())
                FormationAccountClear.Cleared
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { FormationAccountClear.Failed }
    }

    suspend fun load(owner: FormationPayloadOwner): FormationPayloadLoad = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        val allowed = { job?.isActive != false && canAccess(owner.accountSubjectHash) }
        try {
            require(owner.isValid() && allowed())
            withAccountLock(owner.accountSubjectHash) { accountDirectory ->
                require(allowed())
                val record = read(owner, payloadFile(accountDirectory, owner))
                require(allowed())
                record
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { FormationPayloadLoad.Unavailable }
    }

    /**
     * Keeps an unreadable encrypted record as recovery evidence before a fresh accepted
     * snapshot is written. Its state is rechecked under the account lock, preventing a
     * stale failure from quarantining a concurrent valid write.
     */
    suspend fun preserveUnavailable(
        owner: FormationPayloadOwner,
        plaintextIsUnavailable: (ByteArray) -> Boolean,
    ): FormationPayloadPreservation = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        val allowed = { job?.isActive != false && canAccess(owner.accountSubjectHash) }
        try {
            require(owner.isValid() && allowed())
            withAccountLock(owner.accountSubjectHash) { accountDirectory ->
                require(allowed())
                val target = payloadFile(accountDirectory, owner)
                if (readTombstone(tombstoneFile(target)) != null || !target.exists())
                    return@withAccountLock FormationPayloadPreservation.Missing
                require(target.isFile && safeChild(accountDirectory, target.name) == target.canonicalFile)
                val stillUnavailable = when (val current = readPayload(owner, target)) {
                    FormationPayloadLoad.Missing -> return@withAccountLock FormationPayloadPreservation.Missing
                    FormationPayloadLoad.Unavailable -> true
                    is FormationPayloadLoad.Loaded -> try {
                        plaintextIsUnavailable(current.plaintext)
                    } finally {
                        current.plaintext.fill(0)
                    }
                }
                if (!stillUnavailable) return@withAccountLock FormationPayloadPreservation.BecameReadable
                require(allowed())
                val preserved = firstPreservedFile(accountDirectory, target)
                Files.move(target.toPath(), preserved.toPath(), StandardCopyOption.ATOMIC_MOVE)
                forceDirectory(accountDirectory)
                require(!target.exists() && preserved.isFile && allowed())
                FormationPayloadPreservation.Preserved
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { FormationPayloadPreservation.Failed }
    }

    suspend fun list(accountSubjectHash: String): FormationPayloadListing = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        val allowed = { job?.isActive != false && canAccess(accountSubjectHash) }
        try {
            require(validFormationAccountHash(accountSubjectHash) && allowed())
            withAccountLock(accountSubjectHash) { accountDirectory ->
                require(allowed())
                val files = requireNotNull(accountDirectory.listFiles())
                files.filter { it.name.endsWith(TOMBSTONE_SUFFIX) }.forEach { file ->
                    require(TOMBSTONE_NAME.matches(file.name) && safeChild(accountDirectory, file.name) == file.canonicalFile)
                    requireNotNull(readTombstone(file))
                }
                val identities = files.filter { it.name.endsWith(PAYLOAD_SUFFIX) }.mapNotNull { file ->
                    require(file.isFile && safeChild(accountDirectory, file.name) == file.canonicalFile)
                    if (readTombstone(tombstoneFile(file)) != null) return@mapNotNull null
                    val envelope = readBounded(file)
                    val identity = requireNotNull(cipher.metadata(envelope)).identity
                    require(identity.owner.accountSubjectHash == accountSubjectHash)
                    require(payloadFile(accountDirectory, identity.owner) == file.canonicalFile)
                    val plaintext = requireNotNull(cipher.decrypt(identity, envelope))
                    // No body or source title is returned by this technical index.
                    plaintext.fill(0)
                    require(allowed())
                    identity
                }
                require(identities.map { it.owner.sourceEntryId }.distinct().size == identities.size)
                require(allowed())
                FormationPayloadListing.Listed(identities.sortedBy { it.owner.sourceEntryId })
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { FormationPayloadListing.Unavailable }
    }

    suspend fun compareAndSet(owner: FormationPayloadOwner, expectedRevision: Long?, payloadVersion: Int,
        plaintext: ByteArray): FormationPayloadSave {
        val requested = plaintext.copyOf()
        return withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()[Job]
            val allowed = { job?.isActive != false && canAccess(owner.accountSubjectHash) }
            try {
                require(owner.isValid() && allowed())
                require(expectedRevision == null || expectedRevision in 0 until Long.MAX_VALUE)
                val nextRevision = expectedRevision?.plus(1) ?: 0
                val identity = FormationPayloadIdentity(owner, nextRevision, payloadVersion)
                require(identity.isValid())
                withAccountLock(owner.accountSubjectHash) { accountDirectory ->
                    require(allowed())
                    val target = payloadFile(accountDirectory, owner)
                    if (readTombstone(tombstoneFile(target)) != null) return@withAccountLock FormationPayloadSave.Conflict
                    val previous = read(owner, target)
                    require(allowed())
                    when (previous) {
                        FormationPayloadLoad.Unavailable -> return@withAccountLock FormationPayloadSave.Failed
                        FormationPayloadLoad.Missing -> if (expectedRevision != null) return@withAccountLock FormationPayloadSave.Conflict
                        is FormationPayloadLoad.Loaded -> {
                            // Exact one-step replay handles a lost save response; older changes cannot overwrite it.
                            if (previous.identity == identity && previous.plaintext.contentEquals(requested))
                                return@withAccountLock FormationPayloadSave.Saved(nextRevision)
                            if (previous.identity.revision != expectedRevision) return@withAccountLock FormationPayloadSave.Conflict
                        }
                    }
                    val encrypted = cipher.encrypt(identity, requested)
                    require(allowed())
                    (committer ?: AtomicFormationFileCommitter).replace(target, encrypted, allowed)
                    require(allowed())
                    val saved = read(owner, target)
                    require(saved is FormationPayloadLoad.Loaded && saved.identity == identity && saved.plaintext.contentEquals(requested))
                    require(allowed())
                    FormationPayloadSave.Saved(nextRevision)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { FormationPayloadSave.Failed }
            finally { requested.fill(0) }
        }
    }

    private fun read(owner: FormationPayloadOwner, target: File): FormationPayloadLoad {
        if (readTombstone(tombstoneFile(target)) != null) return FormationPayloadLoad.Missing
        return readPayload(owner, target)
    }

    private fun readPayload(owner: FormationPayloadOwner, target: File): FormationPayloadLoad {
        if (!target.exists()) return FormationPayloadLoad.Missing
        if (!target.isFile) return FormationPayloadLoad.Unavailable
        val envelope = readBounded(target)
        val identity = cipher.metadata(envelope)?.identity ?: return FormationPayloadLoad.Unavailable
        if (identity.owner != owner) return FormationPayloadLoad.Unavailable
        val plaintext = cipher.decrypt(identity, envelope) ?: return FormationPayloadLoad.Unavailable
        return FormationPayloadLoad.Loaded(identity, plaintext)
    }

    private fun tombstoneFile(target: File): File = safeChild(requireNotNull(target.parentFile), target.name.removeSuffix(PAYLOAD_SUFFIX) + TOMBSTONE_SUFFIX)

    private fun readTombstone(file: File): Long? {
        if (!file.exists()) return null
        require(file.isFile && file.length() == TOMBSTONE_BYTES.toLong())
        val bytes = file.readBytes()
        require(bytes.size == TOMBSTONE_BYTES)
        val marker = ByteBuffer.wrap(bytes)
        require(marker.int == TOMBSTONE_MAGIC && marker.int == 1)
        return marker.long.also { require(it >= 0) }
    }

    private fun readBounded(file: File): ByteArray {
        require(file.length() in 1..cipher.maxEnvelopeBytes)
        return file.inputStream().use { stream ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size().toLong() + count <= cipher.maxEnvelopeBytes)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }

    private fun payloadFile(accountDirectory: File, owner: FormationPayloadOwner): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(owner.sourceEntryId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return safeChild(accountDirectory, hash + PAYLOAD_SUFFIX)
    }

    private fun firstPreservedFile(accountDirectory: File, target: File): File {
        val stem = target.name.removeSuffix(PAYLOAD_SUFFIX)
        for (index in 0..999) {
            val suffix = if (index == 0) "" else ".$index"
            val candidate = safeChild(accountDirectory, "$stem.unreadable$suffix")
            if (!candidate.exists()) return candidate
        }
        error("Too many preserved recovery records")
    }

    private fun safeChild(parent: File, name: String): File {
        val child = File(parent, name)
        require(!Files.isSymbolicLink(child.toPath()))
        return child.canonicalFile.also { require(it.parentFile == parent.canonicalFile) }
    }

    private fun <T> withAccountLock(accountSubjectHash: String, action: (File) -> T): T {
        val root = directory.canonicalFile
        check(root.isDirectory || root.mkdirs())
        val accountDirectory = safeChild(root, accountSubjectHash)
        val lockIndex = (accountDirectory.path.hashCode() and Int.MAX_VALUE) % locks.size
        return synchronized(locks[lockIndex]) {
            check(accountDirectory.isDirectory || accountDirectory.mkdirs())
            val lockFile = safeChild(accountDirectory, LOCK_FILE)
            RandomAccessFile(lockFile, "rw").use { file ->
                file.channel.lock().use { action(accountDirectory) }
            }
        }
    }

    private companion object {
        const val PAYLOAD_SUFFIX = ".payload"
        const val TOMBSTONE_SUFFIX = ".discarded"
        const val LOCK_FILE = ".formation.lock"
        const val TOMBSTONE_BYTES = 16
        const val TOMBSTONE_MAGIC = 0x524c4644
        val PAYLOAD_NAME = Regex("[a-f0-9]{64}\\.payload")
        val TOMBSTONE_NAME = Regex("[a-f0-9]{64}\\.discarded")
        val PRESERVED_NAME = Regex("[a-f0-9]{64}\\.unreadable(?:\\.[1-9][0-9]{0,2})?")
        // Bounded JVM stripes prevent overlapping same-process file locks; OS lock covers another process.
        val locks = Array(64) { Any() }
    }
}

private object AtomicFormationFileCommitter : FormationAtomicFileCommitter {
    override fun replace(target: File, envelope: ByteArray, canCommit: () -> Boolean) {
        val directory = requireNotNull(target.parentFile)
        val temporary = File.createTempFile(".formation-", ".pending", directory)
        try {
            FileOutputStream(temporary).use { stream -> stream.write(envelope); stream.fd.sync() }
            check(canCommit())
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            // The rename and directory entry are durable before a save may be reported.
            forceDirectory(directory)
        } finally { if (temporary.exists()) check(temporary.delete()) }
    }
}

private fun forceDirectory(directory: File) {
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
}
