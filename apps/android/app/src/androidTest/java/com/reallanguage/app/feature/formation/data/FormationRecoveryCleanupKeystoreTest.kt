package com.reallanguage.app.feature.formation.data

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class FormationRecoveryCleanupKeystoreTest {
    @Test fun approvedAccountClearRemovesOnlyIsolatedPurposeKeyAndAccountCiphertexts() = runBlocking {
        withContext(Dispatchers.IO) {
            val testId = UUID.randomUUID().toString().replace("-", "")
            val namespace = "reallanguage.formation.payload.test.$testId"
            val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "formation-cleanup-test-$testId")
            val keys = AndroidFormationPayloadKeys(namespace)
            val first = FormationPayloadOwner("a".repeat(64), "isolated-first")
            val second = first.copy(accountSubjectHash = "b".repeat(64))
            try {
                val store = FormationRecoveryFileStore(root, FormationPayloadCipher(keys), { true }, canClearAccount = { it == first.accountSubjectHash })
                listOf(first, second).forEach { assertEquals(FormationPayloadSave.Saved(0), store.compareAndSet(it, null, 1, "隔离内容".toByteArray())) }
                assertNotNull(keys.existing(first.accountSubjectHash, 1))
                assertEquals(FormationAccountClear.Cleared, store.clearAccount(first.accountSubjectHash))
                assertNull(keys.existing(first.accountSubjectHash, 1))
                assertNotNull(keys.existing(second.accountSubjectHash, 1))
                assertEquals(FormationPayloadLoad.Missing, store.load(first))
                assertTrue(store.load(second) is FormationPayloadLoad.Loaded)
                assertEquals(setOf(".formation.lock"), File(root, first.accountSubjectHash).listFiles().orEmpty().map { it.name }.toSet())
            } finally {
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                store.aliases().toList().filter { it.startsWith("$namespace.") }.forEach(store::deleteEntry)
                check(!root.exists() || root.deleteRecursively())
            }
        }
    }
}
