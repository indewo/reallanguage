package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.safety.domain.*
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class FixedSourcePayloadCodecTest {
    private val identity = FixedSourcePayloadIdentity("a".repeat(64), "flow", "source", 0)
    private val projection = FixedSourceProjection("flow", "source", 0,
        ConfirmedSource("喝茶。\n另一句完整来源。", SourceType.DIARY, SourceContentShape.ARTICLE), "原始来源标题",
        listOf(FixedSourceUnit("u1", 0, "喝茶。"), FixedSourceUnit("u2", 1, "另一句完整来源。")),
        listOf("u1"), mapOf("u1" to "e1"), emptySet(), FixedSourceOrigin.Diary)

    @Test fun realAuthenticatedCipherRoundTripsCompleteSourceAndRandomizesEqualProjection() {
        val codec = AesGcmFixedSourcePayloadCodec(FixedSourceTestKeys())
        val first = requireNotNull(codec.encrypt(identity, projection))
        assertEquals(projection, codec.decrypt(identity, first))
        val second = requireNotNull(codec.encrypt(identity, projection))
        assertFalse(first.contentEquals(second))
        assertFalse(String(first, Charsets.UTF_8).contains(projection.source.text))
    }

    @Test fun accountFlowSourceRevisionAndDamageAreIndependentlyRejected() {
        val codec = AesGcmFixedSourcePayloadCodec(FixedSourceTestKeys())
        val encrypted = requireNotNull(codec.encrypt(identity, projection))
        listOf(identity.copy(accountSubjectHash = "b".repeat(64)), identity.copy(formationFlowId = "other"),
            identity.copy(sourceEntryId = "other"), identity.copy(sourceRevision = 1)).forEach { assertNull(codec.decrypt(it, encrypted)) }
        assertNull(codec.decrypt(identity, encrypted.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }))
        assertNull(codec.encrypt(identity, projection.copy(formationFlowId = "other")))
    }

    @Test fun realFixedSourceKeystorePurposeIsNonExportableAndIndependentOfFormationCleanup() = runBlocking {
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString().replace("-", "")
            val sourceNamespace = "reallanguage.fixed-source.payload.test.$id"
            val formationNamespace = "reallanguage.formation.payload.test.$id"
            val fixedKeys = AndroidFixedSourcePayloadKeys(sourceNamespace)
            val formationKeys = AndroidFormationPayloadKeys(formationNamespace)
            try {
                val sourceKey = fixedKeys.createIfAbsent(identity.accountSubjectHash, 1)
                assertNull(sourceKey.encoded)
                formationKeys.createIfAbsent(identity.accountSubjectHash, 1)
                val codec = AesGcmFixedSourcePayloadCodec(fixedKeys)
                val encrypted = requireNotNull(codec.encrypt(identity, projection))
                assertNull(AesGcmFixedSourcePayloadCodec(formationKeys).decrypt(identity, encrypted))
                formationKeys.deleteAccount(identity.accountSubjectHash)
                assertEquals(projection, AesGcmFixedSourcePayloadCodec(AndroidFixedSourcePayloadKeys(sourceNamespace)).decrypt(identity, encrypted))
                fixedKeys.deleteAccount(identity.accountSubjectHash)
                assertNull(codec.decrypt(identity, encrypted))
            } finally {
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                store.aliases().toList().filter { it.startsWith("$sourceNamespace.") || it.startsWith("$formationNamespace.") }.forEach(store::deleteEntry)
            }
        }
    }
}
