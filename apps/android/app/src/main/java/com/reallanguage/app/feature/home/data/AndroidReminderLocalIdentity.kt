package com.reallanguage.app.feature.home.data

import android.content.Context
import android.os.UserManager
import com.reallanguage.app.core.session.androidSessionCredentialStore
import com.reallanguage.app.feature.home.domain.ReminderLocalIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local ownership only. Does not refresh a session, retain a token, or authorize API access. */
suspend fun androidReminderLocalIdentity(context: Context): ReminderLocalIdentity? = withContext(Dispatchers.IO) {
    val application = context.applicationContext
    val stored = StoredSessionReminderLocalIdentity.load(androidSessionCredentialStore(application)) {
        application.getSystemService(UserManager::class.java)?.isUserUnlocked == true
    } ?: return@withContext null
    val deletionJournal = AndroidAccountDeletionCleanupJournal(application)
    val checked = object : ReminderLocalIdentity {
        override val accountId: String = stored.accountId
        override suspend fun isCurrent(): Boolean {
            // An alarm may create the process before MainActivity/startup has replayed deletion receipts.
            // Recheck the durable receipt at delivery time; never clear evidence or refresh credentials here.
            val pending = deletionJournal.read() as? AccountDeletionCleanupLoad.Ready ?: return false
            return pending.entries.none { it.accountId == accountId } && stored.isCurrent()
        }
    }
    checked.takeIf { it.isCurrent() }
}
