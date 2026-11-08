package com.reallanguage.app.feature.recording.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import com.reallanguage.app.feature.recording.domain.LocalRecordingSummary
import com.reallanguage.app.feature.recording.domain.RecordingConfirmationRepository
import com.reallanguage.app.feature.recording.domain.RecordingLoadResult
import com.reallanguage.app.feature.recording.domain.RecordingPlaybackResult
import com.reallanguage.app.feature.recording.domain.RecordingEndReason
import com.reallanguage.app.feature.recording.domain.RECORDING_LIMIT_SECONDS
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.reallanguage.app.core.session.SessionRuntime

class AndroidRecordingConfirmationRepository(context: Context, private val runtime: SessionRuntime,
    private val isPlaybackAllowed: () -> Boolean = { true },
) : RecordingConfirmationRepository {
    private val filesDir = context.applicationContext.filesDir
    private val ownerAccountId = runtime.accountId
    private val ownerEpoch = runtime.accountEpoch
    private fun ownsSession() = ownerAccountId != null && runtime.accountId == ownerAccountId &&
        runtime.accountEpoch == ownerEpoch && runtime.accessToken != null && isPlaybackAllowed()
    private val playbackLock = Any()
    private var player: MediaPlayer? = null
    private var playingRecordingId: String? = null
    private val mutablePlaybackState = MutableStateFlow<RecordingPlaybackResult>(RecordingPlaybackResult.Stopped)
    override val playbackState = mutablePlaybackState.asStateFlow()

    override fun stopPlayback() { synchronized(playbackLock) { releasePlayer() } }

    override suspend fun load(recordingId: String): RecordingLoadResult = withContext(Dispatchers.IO) {
        val file = recordingFile(recordingId) ?: return@withContext RecordingLoadResult.Failed
        if (!file.isFile) return@withContext RecordingLoadResult.Failed
        try {
            val retriever = MediaMetadataRetriever()
            val durationMillis = try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                retriever.release()
            } ?: return@withContext RecordingLoadResult.Failed
            if (!ownsSession()) return@withContext RecordingLoadResult.Failed
            RecordingLoadResult.Ready(
                readRecordingSummary(file.parentFile!!, recordingId, durationMillis),
            )
        } catch (_: Exception) {
            RecordingLoadResult.Failed
        }
    }

    override suspend fun togglePlayback(recordingId: String): RecordingPlaybackResult = withContext(Dispatchers.IO) {
        synchronized(playbackLock) {
            val file = recordingFile(recordingId) ?: run {
                releasePlayer(RecordingPlaybackResult.Failed)
                return@synchronized RecordingPlaybackResult.Failed
            }
            if (playingRecordingId == recordingId && player?.isPlaying == true) {
                releasePlayer()
                return@synchronized RecordingPlaybackResult.Stopped
            }
            if (!file.isFile) {
                releasePlayer(RecordingPlaybackResult.Failed)
                return@synchronized RecordingPlaybackResult.Failed
            }
            try {
                releasePlayer()
                player = MediaPlayer()
                requireNotNull(player).apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { completed ->
                        synchronized(playbackLock) { if (player === completed) releasePlayer() }
                    }
                    setOnErrorListener { failed, _, _ ->
                        synchronized(playbackLock) { if (player === failed) releasePlayer(RecordingPlaybackResult.Failed) }
                        true
                    }
                    prepare()
                    check(ownsSession())
                    start()
                }
                playingRecordingId = recordingId
                mutablePlaybackState.value = RecordingPlaybackResult.Playing
                RecordingPlaybackResult.Playing
            } catch (_: Exception) {
                releasePlayer(RecordingPlaybackResult.Failed)
                RecordingPlaybackResult.Failed
            }
        }
    }

    private fun recordingFile(recordingId: String): File? {
        if (!ownsSession() || !recordingId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))) return null
        return File(accountRecordingsDirectory(filesDir, ownerAccountId!!), "$recordingId.m4a")
    }

    private fun releasePlayer(result: RecordingPlaybackResult = RecordingPlaybackResult.Stopped) {
        player?.release()
        player = null
        playingRecordingId = null
        mutablePlaybackState.value = result
    }

}

internal fun readRecordingSummary(directory: File, recordingId: String, durationMillis: Long): LocalRecordingSummary {
    require(recordingId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")))
    val file = File(directory, "$recordingId.recording.json")
    val evidence = if (file.isFile) JSONObject(file.readText()) else null
    val limit = when (evidence?.getInt("formatVersion")) {
        null, 1 -> 120  // Files from v106 and earlier were recorded with a two-minute cutoff.
        2 -> evidence.getInt("maxDurationSeconds").also { require(it == RECORDING_LIMIT_SECONDS) }
        else -> error("Unknown recording metadata")
    }
    val limitMillis = limit * 1_000L
    require(durationMillis in 1..limitMillis + 1_000)
    val reason = if (evidence != null) {
        require(evidence.getString("recordingId") == recordingId)
        evidence.getString("endReason").let { if (it in setOf("SESSION_ENDED", "INCOMPLETE")) null else RecordingEndReason.valueOf(it) }
    } else if (!file.exists() && durationMillis < limitMillis - 1_000) RecordingEndReason.USER_STOPPED else null
    if (reason == RecordingEndReason.MAX_DURATION) require(durationMillis >= limitMillis - 1_000)
    if (evidence?.optInt("formatVersion") == 1 && reason == RecordingEndReason.USER_STOPPED) require(durationMillis <= limitMillis)
    val seconds = if (reason == RecordingEndReason.MAX_DURATION) limit else minOf(limit, ((durationMillis + 999) / 1000).toInt())
    return LocalRecordingSummary(recordingId, seconds, reason)
}
