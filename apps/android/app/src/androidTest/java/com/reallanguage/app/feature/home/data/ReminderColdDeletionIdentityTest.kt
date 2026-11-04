package com.reallanguage.app.feature.home.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.DeletedAccountLocalAccess
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.core.session.androidSessionCredentialStore
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** No Activity/startup/revoker is run; a unique account models the fresh receiver process boundary. */
class ReminderColdDeletionIdentityTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val owner = "cold-reminder-$id"
    private val other = "other-cold-reminder-$id"
    private val files = File(base.cacheDir, "cold-reminder-$id")
    private val preferenceFiles = mutableSetOf<String>()
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = File(files, "no-backup")
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = "cold-reminder-$id-$name"; preferenceFiles += scoped
            return base.getSharedPreferences(scoped, mode)
        }
    }
    private val credentials get() = androidSessionCredentialStore(context)
    private val journal get() = AndroidAccountDeletionCleanupJournal(context)
    private val stored = StoredSession(owner, "isolated-cold-refresh", true)

    @After fun cleanOnlyFixture() {
        preferenceFiles.forEach { base.deleteSharedPreferences(it) }
        check(files.name == "cold-reminder-$id"); files.deleteRecursively()
    }

    @Test fun durableSameAccountReceiptRejectsColdReceiverBeforeAnyInMemoryRevoke() = runBlocking(Dispatchers.IO) {
        credentials.save(stored)
        assertTrue(journal.record(ConfirmedAccountDeletion(owner, "receipt")))
        assertTrue(DeletedAccountLocalAccess.allowsAccount(owner))
        assertNull(androidReminderLocalIdentity(context))
        assertEquals(stored, credentials.load())
        assertEquals(1, (journal.read() as AccountDeletionCleanupLoad.Ready).entries.size)
    }

    @Test fun corruptReceiptDoesNotAuthorizeReceiverOrEraseEvidence() = runBlocking(Dispatchers.IO) {
        credentials.save(stored)
        val directory = File(context.noBackupFilesDir, "account-deletion-cleanup").apply { mkdirs() }
        val hash = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val corrupt = File(directory, "$hash.pending").apply { writeText("{broken-receipt") }
        assertNull(androidReminderLocalIdentity(context))
        assertTrue(corrupt.exists())
        assertEquals(stored, credentials.load())
    }

    @Test fun anotherAccountsReceiptDoesNotSilenceCurrentAccount() = runBlocking(Dispatchers.IO) {
        credentials.save(stored)
        assertTrue(journal.record(ConfirmedAccountDeletion(other, "other-receipt")))
        val identity = requireNotNull(androidReminderLocalIdentity(context))
        assertEquals(owner, identity.accountId)
        assertTrue(identity.isCurrent())
        assertEquals(stored, credentials.load())
    }

    @Test fun alreadyCapturedReceiverIdentityRechecksNewDurableReceiptWithoutStartup() = runBlocking(Dispatchers.IO) {
        credentials.save(stored)
        val identity = requireNotNull(androidReminderLocalIdentity(context))
        assertTrue(identity.isCurrent())
        assertTrue(journal.record(ConfirmedAccountDeletion(owner, "late-receipt")))
        assertTrue(DeletedAccountLocalAccess.allowsAccount(owner))
        assertFalse(identity.isCurrent())
        assertEquals(stored, credentials.load())
    }
}
