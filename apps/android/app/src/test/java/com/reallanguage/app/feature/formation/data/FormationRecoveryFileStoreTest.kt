package com.reallanguage.app.feature.formation.data

import java.io.File
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FormationRecoveryFileStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val owner = FormationPayloadOwner("a".repeat(64), "source:isolated-1")
    private val first = "确认中文与有效候选：first isolated payload".toByteArray()
    private val second = "第二修订：second isolated payload".toByteArray()
    private val keys = JceFormationTestKeys()
    private val cipher = FormationPayloadCipher(keys)

    @Test fun `real encrypted file survives new repository and does not contain plaintext`() = runBlocking {
        val root = folder.newFolder()
        val store = FormationRecoveryFileStore(root, cipher, { true })
        assertEquals(FormationPayloadSave.Saved(0), store.compareAndSet(owner, null, 1, first))
        val reloaded = FormationRecoveryFileStore(root, FormationPayloadCipher(keys), { true }).load(owner)
        assertTrue(reloaded is FormationPayloadLoad.Loaded)
        reloaded as FormationPayloadLoad.Loaded
        assertArrayEquals(first, reloaded.plaintext)
        assertEquals(FormationPayloadIdentity(owner, 0, 1), reloaded.identity)
        assertFalse(root.walkTopDown().filter(File::isFile).any { it.readBytes().toString(Charsets.UTF_8).contains(first.toString(Charsets.UTF_8)) })
    }

    @Test fun `CAS update rejects stale changed data and equivalent lost response can replay`() = runBlocking {
        val store = FormationRecoveryFileStore(folder.newFolder(), cipher, { true })
        assertEquals(FormationPayloadSave.Saved(0), store.compareAndSet(owner, null, 1, first))
        assertEquals(FormationPayloadSave.Saved(0), store.compareAndSet(owner, null, 1, first))
        assertEquals(FormationPayloadSave.Conflict, store.compareAndSet(owner, null, 1, second))
        assertEquals(FormationPayloadSave.Saved(1), store.compareAndSet(owner, 0, 1, second))
        assertEquals(FormationPayloadSave.Saved(1), store.compareAndSet(owner, 0, 1, second))
        assertEquals(FormationPayloadSave.Conflict, store.compareAndSet(owner, 0, 1, first))
        assertArrayEquals(second, (store.load(owner) as FormationPayloadLoad.Loaded).plaintext)
    }

    @Test fun `two repositories racing one revision accept one different value only`() = runBlocking {
        val root = folder.newFolder()
        val store = FormationRecoveryFileStore(root, cipher, { true })
        assertEquals(FormationPayloadSave.Saved(0), store.compareAndSet(owner, null, 1, first))
        val start = CountDownLatch(1)
        val results = listOf(second, "different concurrent edit".toByteArray()).map { text ->
            async(Dispatchers.IO) {
                start.await()
                FormationRecoveryFileStore(root, cipher, { true }).compareAndSet(owner, 0, 1, text)
            }
        }.also { start.countDown() }.awaitAll()
        assertEquals(1, results.count { it == FormationPayloadSave.Saved(1) })
        assertEquals(1, results.count { it == FormationPayloadSave.Conflict })
    }

    @Test fun `failed atomic replacement keeps previous confirmed bytes and is retryable`() = runBlocking {
        val root = folder.newFolder()
        val normal = FormationRecoveryFileStore(root, cipher, { true })
        assertEquals(FormationPayloadSave.Saved(0), normal.compareAndSet(owner, null, 1, first))
        val failing = FormationRecoveryFileStore(root, cipher, { true }, FormationAtomicFileCommitter { _, _, _ ->
            throw java.io.IOException("isolated commit failure")
        })
        assertEquals(FormationPayloadSave.Failed, failing.compareAndSet(owner, 0, 1, second))
        assertArrayEquals(first, (normal.load(owner) as FormationPayloadLoad.Loaded).plaintext)
        assertEquals(FormationPayloadSave.Saved(1), normal.compareAndSet(owner, 0, 1, second))
    }

    @Test fun `permission loss cannot read write or expose an old accounts payload`() = runBlocking {
        val root = folder.newFolder()
        var allowed = true
        val store = FormationRecoveryFileStore(root, cipher, { allowed })
        assertEquals(FormationPayloadSave.Saved(0), store.compareAndSet(owner, null, 1, first))
        allowed = false
        assertEquals(FormationPayloadLoad.Unavailable, store.load(owner))
        assertEquals(FormationPayloadSave.Failed, store.compareAndSet(owner, 0, 1, second))
        val other = FormationRecoveryFileStore(root, cipher, { it == "b".repeat(64) })
        assertEquals(FormationPayloadLoad.Unavailable, other.load(owner))
        assertEquals(FormationPayloadLoad.Missing, other.load(owner.copy(accountSubjectHash = "b".repeat(64))))
    }

    @Test fun `a committer that writes wrong bytes cannot report saved without authenticated readback`() = runBlocking {
        val store = FormationRecoveryFileStore(folder.newFolder(), cipher, { true }, FormationAtomicFileCommitter { target, _, _ ->
            target.writeBytes(byteArrayOf(1, 2, 3))
        })
        assertEquals(FormationPayloadSave.Failed, store.compareAndSet(owner, null, 1, first))
        assertEquals(FormationPayloadLoad.Unavailable, store.load(owner))
    }

    @Test fun `malformed owner is rejected without creating files or keys`() = runBlocking {
        val root = folder.newFolder()
        val store = FormationRecoveryFileStore(root, cipher, { true })
        val invalid = owner.copy(accountSubjectHash = "../escape")
        assertEquals(FormationPayloadSave.Failed, store.compareAndSet(invalid, null, 1, first))
        assertEquals(FormationPayloadLoad.Unavailable, store.load(invalid))
        assertTrue(root.listFiles().orEmpty().isEmpty())
        assertEquals(0, keys.creations)
    }

    @Test fun `listing authenticates multiple sessions but exposes identities only from the current account`() = runBlocking {
        val root = folder.newFolder()
        val writer = FormationRecoveryFileStore(root, cipher, { true })
        val next = owner.copy(sourceEntryId = "source:isolated-2")
        val foreign = owner.copy(accountSubjectHash = "b".repeat(64))
        listOf(owner, next, foreign).forEach {
            assertEquals(FormationPayloadSave.Saved(0), writer.compareAndSet(it, null, 1, first))
        }
        val reader = FormationRecoveryFileStore(root, cipher, { it == owner.accountSubjectHash })
        val listed = reader.list(owner.accountSubjectHash)
        assertTrue(listed is FormationPayloadListing.Listed)
        assertEquals(setOf(FormationPayloadIdentity(owner, 0, 1), FormationPayloadIdentity(next, 0, 1)),
            (listed as FormationPayloadListing.Listed).identities.toSet())
        assertEquals(FormationPayloadListing.Unavailable, reader.list(foreign.accountSubjectHash))
    }

    @Test fun `corrupt stored record makes listing unavailable rather than an empty or partial success`() = runBlocking {
        val root = folder.newFolder()
        val store = FormationRecoveryFileStore(root, cipher, { true })
        assertEquals(FormationPayloadSave.Saved(0), store.compareAndSet(owner, null, 1, first))
        val payload = root.walkTopDown().single { it.isFile && it.name.endsWith(".payload") }
        payload.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(FormationPayloadListing.Unavailable, store.list(owner.accountSubjectHash))
        assertEquals(FormationPayloadLoad.Unavailable, store.load(owner))
        assertEquals(FormationPayloadSave.Failed, store.compareAndSet(owner, null, 1, second))
    }

    @Test fun `unreadable record is preserved before a fresh accepted snapshot is saved`() = runBlocking {
        val root = folder.newFolder()
        val store = FormationRecoveryFileStore(root, cipher, { true })
        assertEquals(FormationPayloadSave.Saved(0), store.compareAndSet(owner, null, 1, first))
        val payload = root.walkTopDown().single { it.isFile && it.name.endsWith(".payload") }
        val damaged = byteArrayOf(1, 2, 3, 4, 5)
        payload.writeBytes(damaged)

        assertEquals(FormationPayloadPreservation.Preserved, store.preserveUnavailable(owner) { true })
        assertEquals(FormationPayloadLoad.Missing, store.load(owner))
        val preserved = root.walkTopDown().single { it.isFile && it.name.contains(".unreadable") }
        assertArrayEquals(damaged, preserved.readBytes())

        assertEquals(FormationPayloadSave.Saved(0), store.compareAndSet(owner, null, 1, second))
        assertArrayEquals(second, (store.load(owner) as FormationPayloadLoad.Loaded).plaintext)
        assertArrayEquals("The preserved evidence must not be overwritten", damaged, preserved.readBytes())
    }

    @Test fun `preservation recheck leaves a readable concurrent record untouched`() = runBlocking {
        val root = folder.newFolder()
        val store = FormationRecoveryFileStore(root, cipher, { true })
        assertEquals(FormationPayloadSave.Saved(0), store.compareAndSet(owner, null, 1, first))
        assertEquals(FormationPayloadPreservation.BecameReadable, store.preserveUnavailable(owner) { false })
        assertArrayEquals(first, (store.load(owner) as FormationPayloadLoad.Loaded).plaintext)
        assertFalse(root.walkTopDown().any { it.isFile && it.name.contains(".unreadable") })
    }
}
