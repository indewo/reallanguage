package com.reallanguage.app.feature.formation.data

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.safety.domain.*
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FormationRecoveryDecodeAccountGateTest {
    @Test fun accountRevokedAfterAuthenticatedReadCannotReceiveDecodedPrivateSnapshot() = runBlocking {
        val hash = "a".repeat(64)
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "recovery-decode-gate-test-${UUID.randomUUID()}")
        val keys = object : FormationPayloadKeys {
            private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            override fun existing(accountSubjectHash: String, keyVersion: Int) = key
            override fun createIfAbsent(accountSubjectHash: String, keyVersion: Int) = key
            override fun deleteAccount(accountSubjectHash: String) = Unit
        }
        try {
            val snapshot = FormationRecoverySnapshot("decode-owner", 0, FormationRecoveryPhase.SEMANTIC_SPLIT,
                ConfirmedSource("隔离已确认内容。", SourceType.DIARY, SourceContentShape.ARTICLE), "隔离标题", FormationSourceOrigin.Diary)
            val cipher = FormationPayloadCipher(keys)
            val writer = EncryptedFormationRecoveryRepository(hash, FormationRecoveryFileStore(root, cipher, { true }))
            assertEquals(FormationRecoverySave.Saved(0), writer.save(snapshot, null))
            var checks = 0
            // The store authorizes before lock, within lock, and after the authenticated file read.
            // Authorization is revoked before the higher-level decoded DTO may be delivered.
            val reader = EncryptedFormationRecoveryRepository(hash, FormationRecoveryFileStore(root, cipher, { ++checks <= 3 }))
            assertEquals(FormationRecoveryLoad.Unavailable, reader.load(snapshot.sourceEntryId))
            assertTrue(checks > 3)
        } finally { check(!root.exists() || root.deleteRecursively()) }
    }
}
