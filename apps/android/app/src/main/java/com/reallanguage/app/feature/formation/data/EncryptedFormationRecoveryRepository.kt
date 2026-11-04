package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.feature.formation.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EncryptedFormationRecoveryRepository(
    private val accountSubjectHash: String,
    private val store: FormationRecoveryFileStore,
    private val codec: FormationRecoveryPayloadCodec = FormationRecoveryPayloadCodec(),
) : FormationRecoveryRepository {
    override suspend fun load(sourceEntryId: String): FormationRecoveryLoad = withContext(Dispatchers.IO) {
        val owner = FormationPayloadOwner(accountSubjectHash, sourceEntryId)
        when (val stored = store.load(owner)) {
            FormationPayloadLoad.Missing -> FormationRecoveryLoad.Missing
            FormationPayloadLoad.Unavailable -> FormationRecoveryLoad.Unavailable
            is FormationPayloadLoad.Loaded -> try {
                val snapshot = try { codec.decode(stored.plaintext) } catch (_: Exception) { null }
                if (snapshot?.sourceEntryId == sourceEntryId && stored.identity.owner == owner &&
                    store.hasCurrentAccess(stored.identity.owner))
                    FormationRecoveryLoad.Loaded(stored.identity.revision, snapshot)
                else FormationRecoveryLoad.Unavailable
            } finally { stored.plaintext.fill(0) }
        }
    }

    override suspend fun save(snapshot: FormationRecoverySnapshot, expectedRevision: Long?): FormationRecoverySave = withContext(Dispatchers.IO) {
        if (!snapshot.isValidForRecovery()) return@withContext FormationRecoverySave.Unavailable
        val owner = FormationPayloadOwner(accountSubjectHash, snapshot.sourceEntryId)
        var effectiveExpectedRevision = expectedRevision
        // An unreadable old envelope is never deleted. Preserve it under an account-local
        // recovery name, then rebuild from the current route's fully validated snapshot.
        if (load(snapshot.sourceEntryId) == FormationRecoveryLoad.Unavailable) {
            when (store.preserveUnavailable(owner) { plaintext ->
                try {
                    val decoded = codec.decode(plaintext)
                    decoded == null || decoded.sourceEntryId != snapshot.sourceEntryId || !decoded.isValidForRecovery()
                } catch (_: Exception) { true }
            }) {
                FormationPayloadPreservation.Preserved,
                FormationPayloadPreservation.Missing -> effectiveExpectedRevision = null
                FormationPayloadPreservation.BecameReadable -> return@withContext FormationRecoverySave.Conflict
                FormationPayloadPreservation.Failed -> return@withContext FormationRecoverySave.Unavailable
            }
        }
        try {
            val encoded = codec.encode(snapshot)
            try {
                when (val saved = store.compareAndSet(owner, effectiveExpectedRevision, 1, encoded)) {
                    FormationPayloadSave.Conflict -> FormationRecoverySave.Conflict
                    FormationPayloadSave.Failed -> FormationRecoverySave.Unavailable
                    is FormationPayloadSave.Saved -> when (val readBack = load(snapshot.sourceEntryId)) {
                        is FormationRecoveryLoad.Loaded -> if (readBack.revision == saved.revision && readBack.snapshot == snapshot)
                            FormationRecoverySave.Saved(saved.revision) else FormationRecoverySave.Conflict
                        else -> FormationRecoverySave.Unavailable
                    }
                }
            } finally { encoded.fill(0) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { FormationRecoverySave.Unavailable }
    }

    override suspend fun list(): FormationRecoveryList = withContext(Dispatchers.IO) {
        when (val index = store.list(accountSubjectHash)) {
            FormationPayloadListing.Unavailable -> FormationRecoveryList.Unavailable
            is FormationPayloadListing.Listed -> {
                val items = mutableListOf<FormationRecoveryItem>()
                for (identity in index.identities) {
                    val loaded = load(identity.owner.sourceEntryId) as? FormationRecoveryLoad.Loaded
                        ?: return@withContext FormationRecoveryList.Unavailable
                    if (loaded.revision != identity.revision) return@withContext FormationRecoveryList.Unavailable
                    items += FormationRecoveryItem(loaded.snapshot.sourceEntryId, loaded.revision, loaded.snapshot.phase,
                        loaded.snapshot.source.sourceType, loaded.snapshot.sourceTitle)
                }
                // A late logout/switch must also invalidate an empty or already-decoded list.
                val after = store.list(accountSubjectHash) as? FormationPayloadListing.Listed
                    ?: return@withContext FormationRecoveryList.Unavailable
                if (after.identities != index.identities) FormationRecoveryList.Unavailable else FormationRecoveryList.Listed(items)
            }
        }
    }
}
