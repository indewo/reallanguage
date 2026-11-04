package com.reallanguage.app.feature.formation.data

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FormationRecoveryCleanupTest {
    @get:Rule val folder = TemporaryFolder()
    private val owner = FormationPayloadOwner("a".repeat(64), "source-discard-1")
    private val next = owner.copy(sourceEntryId = "source-discard-2")
    private val foreign = owner.copy(accountSubjectHash = "b".repeat(64))
    private val payload = "隔离已确认内容".toByteArray()
    private val keys = JceFormationTestKeys()

    @Test fun `discard removes exact current source only and retry cannot resurrect a first save`() = runBlocking {
        val store = store()
        listOf(owner, next, foreign).forEach { assertEquals(FormationPayloadSave.Saved(0), store.compareAndSet(it, null, 1, payload)) }
        assertEquals(FormationPayloadRemoval.Removed, store.discard(owner, 0))
        assertEquals(FormationPayloadLoad.Missing, store.load(owner))
        assertEquals(FormationPayloadRemoval.Removed, store.discard(owner, 0))
        assertEquals(FormationPayloadSave.Conflict, store.compareAndSet(owner, null, 1, payload))
        assertEquals(FormationPayloadSave.Conflict, store.compareAndSet(owner, 0, 1, payload))
        assertTrue(store.load(next) is FormationPayloadLoad.Loaded)
        assertTrue(store.load(foreign) is FormationPayloadLoad.Loaded)
        assertEquals(listOf(FormationPayloadIdentity(next, 0, 1)), (store.list(owner.accountSubjectHash) as FormationPayloadListing.Listed).identities)
        assertNotNull(keys.existing(owner.accountSubjectHash, 1))
    }

    @Test fun `stale discard cannot erase a newer saved revision`() = runBlocking {
        val store = store()
        assertEquals(FormationPayloadSave.Saved(0), store.compareAndSet(owner, null, 1, payload))
        assertEquals(FormationPayloadSave.Saved(1), store.compareAndSet(owner, 0, 1, payload + 1.toByte()))
        assertEquals(FormationPayloadRemoval.Conflict, store.discard(owner, 0))
        assertEquals(1, (store.load(owner) as FormationPayloadLoad.Loaded).identity.revision)
        assertEquals(FormationPayloadRemoval.Removed, store.discard(owner, 1))
        assertEquals(FormationPayloadRemoval.Conflict, store.discard(owner, 0))
    }

    @Test fun `concurrent edit and discard have one winner without deleting a later revision`() = runBlocking {
        val root = folder.newFolder()
        val writer = store(root)
        assertEquals(FormationPayloadSave.Saved(0), writer.compareAndSet(owner, null, 1, payload))
        val tasks = listOf(async(Dispatchers.IO) { writer.discard(owner, 0) },
            async(Dispatchers.IO) { store(root).compareAndSet(owner, 0, 1, payload + 1.toByte()) }).awaitAll()
        val discarded = tasks[0] == FormationPayloadRemoval.Removed
        val edited = tasks[1] == FormationPayloadSave.Saved(1)
        assertTrue(discarded xor edited)
        if (discarded) assertEquals(FormationPayloadLoad.Missing, writer.load(owner))
        else assertEquals(1, (writer.load(owner) as FormationPayloadLoad.Loaded).identity.revision)
    }

    @Test fun `failed tombstone commit leaves current content unchanged and can retry`() = runBlocking {
        val root = folder.newFolder()
        val normal = store(root)
        assertEquals(FormationPayloadSave.Saved(0), normal.compareAndSet(owner, null, 1, payload))
        val failed = FormationRecoveryFileStore(root, FormationPayloadCipher(keys), { true }, FormationAtomicFileCommitter { _, _, _ -> error("isolated commit failure") })
        assertEquals(FormationPayloadRemoval.Failed, failed.discard(owner, 0))
        assertArrayEquals(payload, (normal.load(owner) as FormationPayloadLoad.Loaded).plaintext)
        assertEquals(FormationPayloadRemoval.Removed, normal.discard(owner, 0))
    }

    @Test fun `ordinary access never implies account cleanup permission and old account capability stays isolated`() = runBlocking {
        val root = folder.newFolder()
        val ordinary = store(root)
        listOf(owner, foreign).forEach { ordinary.compareAndSet(it, null, 1, payload) }
        assertEquals(FormationAccountClear.Failed, ordinary.clearAccount(owner.accountSubjectHash))
        assertTrue(ordinary.load(owner) is FormationPayloadLoad.Loaded)
        val approved = FormationRecoveryFileStore(root, FormationPayloadCipher(keys), { false }, canClearAccount = { it == owner.accountSubjectHash })
        assertEquals(FormationAccountClear.Failed, approved.clearAccount(foreign.accountSubjectHash))
        assertEquals(FormationAccountClear.Cleared, approved.clearAccount(owner.accountSubjectHash))
        assertEquals(FormationPayloadLoad.Missing, ordinary.load(owner))
        assertTrue(ordinary.load(foreign) is FormationPayloadLoad.Loaded)
        assertNull(keys.existing(owner.accountSubjectHash, 1))
        assertNotNull(keys.existing(foreign.accountSubjectHash, 1))
    }

    @Test fun `account clear verifies payload pending tombstones and key removal and is idempotent`() = runBlocking {
        val root = folder.newFolder()
        val store = FormationRecoveryFileStore(root, FormationPayloadCipher(keys), { true }, canClearAccount = { it == owner.accountSubjectHash })
        listOf(owner, next, foreign).forEach { store.compareAndSet(it, null, 1, payload) }
        assertEquals(FormationPayloadRemoval.Removed, store.discard(owner, 0))
        File(File(root, owner.accountSubjectHash), ".formation-isolated.pending").writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(FormationAccountClear.Cleared, store.clearAccount(owner.accountSubjectHash))
        assertEquals(setOf(".formation.lock"), File(root, owner.accountSubjectHash).listFiles().orEmpty().map { it.name }.toSet())
        assertNull(keys.existing(owner.accountSubjectHash, 1))
        assertTrue(store.load(foreign) is FormationPayloadLoad.Loaded)
        assertEquals(FormationAccountClear.Cleared, store.clearAccount(owner.accountSubjectHash))
    }

    @Test fun `unverified key deletion fails rather than report completed and can retry`() = runBlocking {
        val root = folder.newFolder()
        var fail = true
        var deletionCalls = 0
        val custody = object : FormationPayloadKeys by keys {
            override fun deleteAccount(accountSubjectHash: String) {
                deletionCalls++
                if (!fail) keys.deleteAccount(accountSubjectHash)
            }
        }
        val store = FormationRecoveryFileStore(root, FormationPayloadCipher(custody), { true }, canClearAccount = { it == owner.accountSubjectHash })
        store.compareAndSet(owner, null, 1, payload)
        assertEquals(FormationAccountClear.Failed, store.clearAccount(owner.accountSubjectHash))
        assertEquals(1, deletionCalls)
        assertNotNull(keys.existing(owner.accountSubjectHash, 1))
        fail = false
        assertEquals(FormationAccountClear.Cleared, store.clearAccount(owner.accountSubjectHash))
        assertEquals(2, deletionCalls)
        assertNull(keys.existing(owner.accountSubjectHash, 1))
    }

    @Test fun `invalid owner revoked cleanup and corrupt live envelope cannot be guessed into a discard`() = runBlocking {
        val root = folder.newFolder()
        val store = FormationRecoveryFileStore(root, FormationPayloadCipher(keys), { true }, canClearAccount = { false })
        assertEquals(FormationPayloadRemoval.Failed, store.discard(owner.copy(accountSubjectHash = "../escape"), 0))
        assertEquals(FormationAccountClear.Failed, store.clearAccount(owner.accountSubjectHash))
        store.compareAndSet(owner, null, 1, payload)
        root.walkTopDown().single { it.name.endsWith(".payload") }.writeBytes(byteArrayOf(3, 2, 1))
        assertEquals(FormationPayloadRemoval.Failed, store.discard(owner, 0))
        assertEquals(FormationPayloadLoad.Unavailable, store.load(owner))
    }

    private fun store(root: File = folder.newFolder()) = FormationRecoveryFileStore(root, FormationPayloadCipher(keys), { true })
}
