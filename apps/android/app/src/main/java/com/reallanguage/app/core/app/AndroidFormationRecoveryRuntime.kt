package com.reallanguage.app.core.app

import android.content.Context
import android.util.Log
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.*
import com.reallanguage.app.feature.formation.domain.*
import java.io.File
import java.security.MessageDigest

/** One authenticated account epoch; creates neither a session credential nor an AI request. */
class AndroidFormationRecoveryRuntime(
    context: Context,
    private val session: SessionRuntime,
    directory: File = File(context.noBackupFilesDir, "formation-recovery"),
    keys: FormationPayloadKeys = AndroidFormationPayloadKeys(),
    fixedSources: FixedSourceRepository = AndroidFixedSourceRepository(context, session),
) {
    private val owner = session.accountId
    private val epoch = session.accountEpoch
    fun isCurrent(): Boolean = owner != null && session.accountId == owner && session.accessToken != null && session.accountEpoch == epoch
    private val ownerHash = MessageDigest.getInstance("SHA-256")
        .digest(owner.orEmpty().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private val store = FormationRecoveryFileStore(directory, FormationPayloadCipher(keys),
        canAccess = { hash -> hash == ownerHash && isCurrent() })
    val repository: FormationRecoveryRepository = EncryptedFormationRecoveryRepository(ownerHash, store)
    val journal = FormationProgressJournal(repository, reportFailure = { reason ->
        // Record only the technical stage, never source text, titles, account IDs or tokens.
        Log.w("FormationRecovery", "prepare_failed stage=${reason.name}")
    }, canAccess = ::isCurrent)
    val completion = FixedFormationRecoveryCompletion(repository, fixedSources,
        { sourceId, revision -> store.discard(FormationPayloadOwner(ownerHash, sourceId), revision) == FormationPayloadRemoval.Removed },
        ::isCurrent)
}
