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

class EncryptedFormationRecoveryRepositoryTest {
    @Test fun savingAndReconstructingRepositoryReturnsExactDataAndAccountLocalDecryptedTitles() = withStore { store, directory, _ ->
        val repository = repository(store)
        val one = sample("source-1", "隔离的第一份内容")
        val two = sample("source-2", "隔离的第二份内容")
        assertEquals(FormationRecoverySave.Saved(0), repository.save(one, null))
        assertEquals(FormationRecoverySave.Saved(0), repository.save(two, null))
        assertEquals(FormationRecoveryLoad.Loaded(0, one), repository(store).load(one.sourceEntryId))
        val listed = repository(store).list() as FormationRecoveryList.Listed
        assertEquals(listOf(one.sourceTitle, two.sourceTitle), listed.items.map { it.title })
        assertTrue(listed.items.all { it.phase == FormationRecoveryPhase.SEMANTIC_SPLIT })
        directory.walkTopDown().filter { it.isFile }.forEach { file ->
            val raw = String(file.readBytes(), Charsets.UTF_8)
            assertFalse(raw.contains(one.sourceTitle)); assertFalse(raw.contains(one.source.text))
        }
    }

    @Test fun validEncryptionWithWrongPayloadOwnerCannotLoadOrBeListedAsAnotherSource() = withStore { store, _, _ ->
        val encoded = FormationRecoveryPayloadCodec().encode(sample("source-other"))
        assertEquals(FormationPayloadSave.Saved(0), store.compareAndSet(FormationPayloadOwner(ACCOUNT_A, "source-1"), null, 1, encoded))
        assertEquals(FormationRecoveryLoad.Unavailable, repository(store).load("source-1"))
        assertEquals(FormationRecoveryList.Unavailable, repository(store).list())
    }

    @Test fun damagedButAuthenticatedPayloadIsPreservedThenRebuiltFromCurrentValidatedSnapshot() = withStore { store, directory, _ ->
        assertEquals(FormationPayloadSave.Saved(0), store.compareAndSet(FormationPayloadOwner(ACCOUNT_A, "source-1"), null, 1, "{}".toByteArray()))
        assertEquals(FormationRecoveryLoad.Unavailable, repository(store).load("source-1"))
        assertEquals(FormationRecoveryList.Unavailable, repository(store).list())
        assertEquals(FormationRecoverySave.Saved(0), repository(store).save(sample("source-1"), 0))
        assertEquals(FormationRecoveryLoad.Loaded(0, sample("source-1")), repository(store).load("source-1"))
        assertEquals(1, directory.walkTopDown().count { it.isFile && it.name.contains(".unreadable") })
    }

    @Test fun currentAccountChangeBlocksReadListAndSaveWithoutChangingOtherAccountRecords() = withStore { store, _, setAccount ->
        assertEquals(FormationRecoverySave.Saved(0), repository(store).save(sample("source-1"), null))
        setAccount(ACCOUNT_B)
        assertEquals(FormationRecoveryLoad.Unavailable, repository(store).load("source-1"))
        assertEquals(FormationRecoveryList.Unavailable, repository(store).list())
        assertEquals(FormationRecoverySave.Unavailable, repository(store).save(sample("source-1"), 0))
        assertEquals(FormationRecoveryLoad.Missing, repository(store, ACCOUNT_B).load("source-1"))
        setAccount(ACCOUNT_A)
        assertEquals(FormationRecoveryLoad.Loaded(0, sample("source-1")), repository(store).load("source-1"))
    }

    @Test fun staleRevisionFailsAndExactSaveReplayDoesNotIncrementRevision() = withStore { store, _, _ ->
        val original = sample("source-1")
        val repository = repository(store)
        assertEquals(FormationRecoverySave.Saved(0), repository.save(original, null))
        assertEquals(FormationRecoverySave.Saved(0), repository.save(original, null))
        val next = original.copy(sourceRevision = 1, source = original.source.copy(text = "已确认修改。"))
        assertEquals(FormationRecoverySave.Saved(1), repository.save(next, 0))
        assertEquals(FormationRecoverySave.Conflict, repository.save(original, 0))
        assertEquals(FormationRecoveryLoad.Loaded(1, next), repository.load("source-1"))
    }

    private fun repository(store: FormationRecoveryFileStore, hash: String = ACCOUNT_A) = EncryptedFormationRecoveryRepository(hash, store)
    private fun sample(id: String, title: String = "隔离标题") = FormationRecoverySnapshot(id, 0, FormationRecoveryPhase.SEMANTIC_SPLIT,
        ConfirmedSource("隔离已确认正文。", SourceType.DIARY, SourceContentShape.ARTICLE), title, FormationSourceOrigin.Diary)
    private fun withStore(block: suspend (FormationRecoveryFileStore, File, (String) -> Unit) -> Unit) = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "recovery-repository-test-${UUID.randomUUID()}")
        var account = ACCOUNT_A
        val keys = object : FormationPayloadKeys {
            private val values = mutableMapOf<String, SecretKey>()
            override fun existing(accountSubjectHash: String, keyVersion: Int) = values["$accountSubjectHash:$keyVersion"]
            override fun createIfAbsent(accountSubjectHash: String, keyVersion: Int) = values.getOrPut("$accountSubjectHash:$keyVersion") {
                KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            }
            override fun deleteAccount(accountSubjectHash: String) { values.keys.removeAll { it.startsWith("$accountSubjectHash:") } }
        }
        try { block(FormationRecoveryFileStore(root, FormationPayloadCipher(keys), { it == account }), root) { account = it } }
        finally { check(!root.exists() || root.deleteRecursively()) }
    }
    private companion object { const val ACCOUNT_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"; const val ACCOUNT_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" }
}
