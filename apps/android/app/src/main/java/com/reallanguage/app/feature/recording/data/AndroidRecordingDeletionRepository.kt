package com.reallanguage.app.feature.recording.data

import android.content.Context
import com.reallanguage.app.feature.recording.domain.RecordingDeletionRepository
import com.reallanguage.app.feature.recording.domain.RecordingDeletionResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.reallanguage.app.core.session.SessionRuntime

class AndroidRecordingDeletionRepository(context: Context, private val runtime: SessionRuntime) : RecordingDeletionRepository {
    private val filesDir = context.applicationContext.filesDir
    private val ownerAccountId = runtime.accountId

    override suspend fun delete(recordingId: String, commandId: String): RecordingDeletionResult =
        withContext(Dispatchers.IO) {
            val accountId = ownerAccountId ?: return@withContext RecordingDeletionResult.Failed
            if (runtime.accountId != accountId || runtime.accessToken == null || commandId.isBlank() ||
                !recordingId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
            ) return@withContext RecordingDeletionResult.Failed
            val recordingsDirectory = accountRecordingsDirectory(filesDir, accountId)
            val audio = File(recordingsDirectory, "$recordingId.m4a")
            val index = File(recordingsDirectory, "$recordingId.index")
            val evidence = File(recordingsDirectory, "$recordingId.recording.json")
            if (!audio.exists() && !index.exists() && !evidence.exists()) return@withContext RecordingDeletionResult.AlreadyDeleted
            try {
                val audioDeleted = !audio.exists() || audio.delete()
                val indexDeleted = !index.exists() || index.delete()
                val evidenceDeleted = !evidence.exists() || evidence.delete()
                if (audioDeleted && indexDeleted && evidenceDeleted && !audio.exists() && !index.exists() && !evidence.exists()) RecordingDeletionResult.Deleted
                else RecordingDeletionResult.Failed
            } catch (_: Exception) {
                RecordingDeletionResult.Failed
            }
        }

}
