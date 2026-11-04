package com.reallanguage.app.feature.recording.data

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.reallanguage.app.feature.recording.domain.RecordingRuntimeRepository
import com.reallanguage.app.feature.recording.domain.RecordingRuntimeState
import com.reallanguage.app.core.session.SessionRuntime
import kotlinx.coroutines.flow.StateFlow

class AndroidRecordingRuntimeRepository(context: Context, private val runtime: SessionRuntime) : RecordingRuntimeRepository {
    private val applicationContext = context.applicationContext
    private val ownerAccountId = runtime.accountId
    private val ownerEpoch = runtime.accountEpoch
    private val ownerKey = ownerAccountId?.let(::accountRecordingStorageKey)
    override val state: StateFlow<RecordingRuntimeState> = AccountRecordingRuntimeState(RecordingForegroundService.ownedRuntimeState, runtime, ownerAccountId)

    private fun requireOwner() {
        check(ownerAccountId != null && runtime.accountId == ownerAccountId && runtime.accountEpoch == ownerEpoch && runtime.accessToken != null) { "Recording account is no longer active" }
    }

    override suspend fun start() {
        requireOwner()
        val current = RecordingForegroundService.ownedRuntimeState.value
        check(current.state !is RecordingRuntimeState.Recording || current.accountStorageKey == ownerKey) { "Another account has an active recording" }
        val binding = PendingRecordingCaptureOwners.register(RecordingCaptureOwner(runtime, requireNotNull(ownerAccountId), ownerEpoch))
        val intent = Intent(applicationContext, RecordingForegroundService::class.java)
            .setAction(RecordingForegroundService.ACTION_START)
            .putExtra(RecordingForegroundService.EXTRA_ACCOUNT_STORAGE_KEY, ownerKey)
            .putExtra(RecordingForegroundService.EXTRA_CAPTURE_OWNER, binding)
        try { ContextCompat.startForegroundService(applicationContext, intent) }
        catch (failure: Exception) { PendingRecordingCaptureOwners.discard(binding); throw failure }
    }

    override suspend fun stop(recordingId: String) {
        requireOwner()
        require(recordingId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")))
        val current = RecordingForegroundService.ownedRuntimeState.value
        check(current.accountStorageKey == ownerKey) { "Recording belongs to another account" }
        if ((current.state as? RecordingRuntimeState.Completed)?.recordingId == recordingId) return
        check((current.state as? RecordingRuntimeState.Recording)?.active?.recordingId == recordingId) { "Recording is not active" }
        val intent = Intent(applicationContext, RecordingForegroundService::class.java)
            .setAction(RecordingForegroundService.ACTION_STOP)
            .putExtra(RecordingForegroundService.EXTRA_RECORDING_ID, recordingId)
            .putExtra(RecordingForegroundService.EXTRA_ACCOUNT_STORAGE_KEY, ownerKey)
        applicationContext.startService(intent)
    }
}
