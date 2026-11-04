package com.reallanguage.app.feature.recording.data

import android.content.Context
import com.reallanguage.app.feature.recording.domain.LocalRecordingEntry
import com.reallanguage.app.feature.recording.domain.LocalRecordingsLoadResult
import com.reallanguage.app.feature.recording.domain.LocalRecordingsRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.reallanguage.app.core.session.SessionRuntime

class AndroidLocalRecordingsRepository(context: Context, private val runtime: SessionRuntime) : LocalRecordingsRepository {
    private val filesDir = context.applicationContext.filesDir
    private val ownerAccountId = runtime.accountId
    private fun ownsSession() = ownerAccountId != null && runtime.accountId == ownerAccountId && runtime.accessToken != null
    override suspend fun load(): LocalRecordingsLoadResult = withContext(Dispatchers.IO) {
        runCatching {
            if (!ownsSession()) return@runCatching LocalRecordingsLoadResult.Failed
            val accountId = ownerAccountId ?: return@runCatching LocalRecordingsLoadResult.Failed
            val directory = accountRecordingsDirectory(filesDir, accountId)
            val result = LocalRecordingsLoadResult.Ready(
                directory.listFiles { file -> file.isFile && file.extension == "m4a" }
                    ?.map { LocalRecordingEntry(it.nameWithoutExtension, it.lastModified()) }
                    ?.sortedByDescending(LocalRecordingEntry::createdAtEpochMillis)
                    ?: emptyList(),
            )
            if (ownsSession()) result else LocalRecordingsLoadResult.Failed
        }.getOrDefault(LocalRecordingsLoadResult.Failed)
    }
}
