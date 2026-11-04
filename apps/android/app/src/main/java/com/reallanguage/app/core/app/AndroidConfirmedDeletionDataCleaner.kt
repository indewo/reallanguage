package com.reallanguage.app.core.app

import android.content.Context
import com.reallanguage.app.core.session.DeletedAccountLocalAccess
import com.reallanguage.app.core.vocabulary.AndroidVocabularyPoolPreference
import com.reallanguage.app.feature.formation.data.*
import com.reallanguage.app.feature.home.data.*
import kotlinx.coroutines.CancellationException
import java.io.File

/** Only a verified server-deletion receipt authorizes the separate per-account draft cleanup capability. */
internal class AndroidConfirmedDeletionDataCleaner(
    private val context: Context,
    private val journal: AccountDeletionCleanupJournal,
) : LocalAccountDataCleaner {
    override suspend fun clear(accountId: String): Boolean = try {
        val pending = journal.read() as? AccountDeletionCleanupLoad.Ready
        if (accountId.isBlank() || pending?.entries?.count { it.accountId == accountId } != 1 ||
            DeletedAccountLocalAccess.allowsAccount(accountId)) false
        else {
            val hash = fixedSourceAccountHash(accountId)
            val drafts = FormationRecoveryFileStore(File(context.noBackupFilesDir, "formation-recovery"),
                FormationPayloadCipher(AndroidFormationPayloadKeys(canUseAccount = { true })),
                canAccess = { false }, canClearAccount = { requested ->
                    requested == hash && !DeletedAccountLocalAccess.allowsAccount(accountId)
                })
            AndroidDailyReminderRuntime(context).clearAfterAccountDeletion(accountId) &&
                AndroidAccountDataCleaner(context).clear(accountId) &&
                AndroidVocabularyPoolPreference(context).clear(accountId) &&
                drafts.clearAccount(hash) == FormationAccountClear.Cleared
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
}
