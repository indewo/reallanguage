package com.reallanguage.app.feature.recording.data

import com.reallanguage.app.core.session.SessionRuntime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Process-local handoff to the native service; no account or credential is placed in an Intent. */
internal class RecordingCaptureOwner(
    val runtime: SessionRuntime,
    private val accountId: String,
    private val epoch: Long,
) {
    val storageKey = accountRecordingStorageKey(accountId)
    fun isCurrent() = synchronized(runtime) {
        runtime.accountId == accountId && runtime.accountEpoch == epoch && runtime.accessToken != null
    }
}

internal object PendingRecordingCaptureOwners {
    private val owners = ConcurrentHashMap<String, RecordingCaptureOwner>()
    fun register(owner: RecordingCaptureOwner): String {
        check(owner.isCurrent()) { "Recording session is no longer active" }
        return UUID.randomUUID().toString().also { owners[it] = owner }
    }
    fun take(id: String?): RecordingCaptureOwner? = id?.let { owners.remove(it) }
    fun discard(id: String) { owners.remove(id) }
}
