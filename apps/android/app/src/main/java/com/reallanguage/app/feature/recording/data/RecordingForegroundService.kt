package com.reallanguage.app.feature.recording.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.reallanguage.app.MainActivity
import com.reallanguage.app.R
import com.reallanguage.app.feature.recording.domain.ActiveRecording
import com.reallanguage.app.feature.recording.domain.RecordingRuntimeState
import com.reallanguage.app.feature.recording.domain.RECORDING_LIMIT_SECONDS
import kotlin.math.sqrt
import java.io.File
import java.util.UUID
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RecordingForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recorder: MediaRecorder? = null
    private var timerJob: Job? = null
    private var recordingId: String? = null
    private var elapsedSeconds = 0
    private val levels = ArrayDeque<Float>(LEVEL_SAMPLES)
    private var elapsedTime: RecordingElapsedTime? = null
    private var recordingDirectory: File? = null
    private var captureOwner: RecordingCaptureOwner? = null
    private var ownerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> PendingRecordingCaptureOwners.take(intent.getStringExtra(EXTRA_CAPTURE_OWNER))?.let { owner ->
                if (owner.storageKey == intent.getStringExtra(EXTRA_ACCOUNT_STORAGE_KEY)) startRecording(owner)
            }
            ACTION_STOP, ACTION_PAUSE, ACTION_RESUME -> if (intent.getStringExtra(EXTRA_RECORDING_ID) == recordingId &&
                intent.getStringExtra(EXTRA_ACCOUNT_STORAGE_KEY) == recordingDirectory?.name
            ) when (intent.action) {
                ACTION_STOP -> stopRecording()
                ACTION_PAUSE -> setPaused(true)
                ACTION_RESUME -> setPaused(false)
            }
        }
        if (recorder == null) stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun startRecording(owner: RecordingCaptureOwner) {
        if (recorder != null) return
        if (!owner.isCurrent()) return
        val accountStorageKey = owner.storageKey
        if (!accountStorageKey.matches(Regex("^[a-f0-9]{64}$"))) return
        val id = UUID.randomUUID().toString()
        val directory = File(File(filesDir, RECORDINGS_DIRECTORY), accountStorageKey).apply { mkdirs() }
        val output = File(directory, "$id.m4a")
        recordingDirectory = directory
        captureOwner = owner
        try {
            // A killed process or failed final write must never look like an explicit user stop.
            // Refuse to start collecting audio if this initial evidence cannot be persisted.
            writeStopEvidence(directory, id, "INCOMPLETE")
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, notification())
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            recorder = device
            device.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(48_000)
                setMaxDuration(RECORDING_LIMIT_SECONDS * 1_000)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopRecording(maximumReached = true)
                }
                setOutputFile(output.absolutePath)
                prepare()
                start()
            }
            recordingId = id
            elapsedSeconds = 0
            levels.clear()
            elapsedTime = RecordingElapsedTime(SystemClock::elapsedRealtime)
            if (!owner.isCurrent()) { stopRecording(); return }
            publish(RecordingRuntimeState.Recording(ActiveRecording(id, 0)))
            ownerJob = serviceScope.launch {
                owner.runtime.identityRevision.collect { if (!owner.isCurrent()) stopRecording() }
            }
            timerJob = serviceScope.launch {
                while (isActive) {
                    delay(150)
                    if (elapsedTime?.paused == true) continue
                    elapsedSeconds = elapsedTime?.tick() ?: 0
                    val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    if (levels.size >= LEVEL_SAMPLES) levels.removeFirst()
                    levels.addLast(sqrt(amplitude.coerceIn(0, 32767) / 32767f))
                    publishCapture(id)
                    if (elapsedSeconds >= RECORDING_LIMIT_SECONDS) {
                        stopRecording(maximumReached = true)
                        break
                    }
                }
            }
        } catch (_: Exception) {
            releaseRecorder()
            output.delete()
            File(directory, "$id.recording.json").delete()
            publish(RecordingRuntimeState.Failed)
            stopSelf()
        }
    }

    private fun setPaused(paused: Boolean) {
        val id = recordingId ?: return
        val clock = elapsedTime ?: return
        if (paused == clock.paused) return
        if (captureOwner?.isCurrent() != true) { stopRecording(); return }
        try {
            if (paused) recorder?.pause() else recorder?.resume()
            clock.setPaused(paused)
            elapsedSeconds = clock.tick()
            publishCapture(id)
        } catch (_: RuntimeException) {
            // Finalize the original file if the audio device becomes unavailable.
            stopRecording(interrupted = true)
        }
    }

    private fun publishCapture(id: String) {
        publish(RecordingRuntimeState.Recording(ActiveRecording(id, elapsedSeconds, elapsedTime?.paused == true, levels.toList())))
    }

    private fun stopRecording(maximumReached: Boolean = false, interrupted: Boolean = false) {
        val id = recordingId ?: return
        val sessionEnded = captureOwner?.isCurrent() != true
        // Native callbacks, button events and the fallback timer are serialized on Main.
        recordingId = null
        elapsedSeconds = if (maximumReached) RECORDING_LIMIT_SECONDS else elapsedTime?.tick() ?: elapsedSeconds
        timerJob?.cancel()
        try {
            val directory = requireNotNull(recordingDirectory)
            try {
                recorder?.stop()
            } catch (alreadyStopped: RuntimeException) {
                // Some encoders finalize the container before posting MAX_DURATION to the main looper.
                // Accept that race only after reading a finalized file that actually spans the limit.
                if (!maximumReached || !hasCompleteHour(File(directory, "$id.m4a"))) throw alreadyStopped
            }
            releaseRecorder()
            writeStopEvidence(directory, id, when {
                sessionEnded -> "SESSION_ENDED"
                interrupted -> "INCOMPLETE"
                maximumReached -> "MAX_DURATION"
                else -> "USER_STOPPED"
            })
            publish(if (sessionEnded || interrupted) RecordingRuntimeState.Failed else RecordingRuntimeState.Completed(id, elapsedSeconds))
        } catch (_: Exception) {
            releaseRecorder()
            publish(RecordingRuntimeState.Failed)
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun hasCompleteHour(file: File): Boolean = runCatching {
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(file.absolutePath)
            val millis = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val limit = RECORDING_LIMIT_SECONDS * 1000L
            millis != null && millis in (limit - 1000)..(limit + 1000)
        } finally { metadata.release() }
    }.getOrDefault(false)

    private fun writeStopEvidence(directory: File, id: String, reason: String) {
        val evidence = File(directory, "$id.recording.json")
        val temporary = File.createTempFile("recording-", ".tmp", directory)
        try {
            val value = JSONObject().put("formatVersion", 2).put("maxDurationSeconds", RECORDING_LIMIT_SECONDS).put("recordingId", id)
                .put("endReason", reason).toString()
            temporary.writeText(value)
            check(temporary.renameTo(evidence) && evidence.readText() == value)
        } finally { temporary.delete() }
    }

    private fun releaseRecorder() {
        ownerJob?.cancel()
        ownerJob = null
        captureOwner = null
        recorder?.release()
        recorder = null
        recordingId = null
    }

    private fun publish(state: RecordingRuntimeState) {
        mutableOwnedRuntimeState.value = OwnedRecordingRuntimeState(recordingDirectory?.name, state)
        mutableRuntimeState.value = state
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.recording_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        timerJob?.cancel()
        if (recorder != null) publish(RecordingRuntimeState.Failed)
        releaseRecorder()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        internal const val EXTRA_CAPTURE_OWNER = "capture-owner"
        private const val LEVEL_SAMPLES = 128
        const val ACTION_PAUSE = "com.reallanguage.app.recording.PAUSE"
        const val ACTION_RESUME = "com.reallanguage.app.recording.RESUME"
        const val ACTION_START = "com.reallanguage.app.recording.START"
        const val ACTION_STOP = "com.reallanguage.app.recording.STOP"
        const val EXTRA_RECORDING_ID = "recording_id"
        const val EXTRA_ACCOUNT_STORAGE_KEY = "account_storage_key"
        private const val CHANNEL_ID = "active_recording"
        private const val NOTIFICATION_ID = 1001
        private const val RECORDINGS_DIRECTORY = "recordings"
        private val mutableRuntimeState = MutableStateFlow<RecordingRuntimeState>(RecordingRuntimeState.Starting)
        val runtimeState: StateFlow<RecordingRuntimeState> = mutableRuntimeState.asStateFlow()
        private val mutableOwnedRuntimeState = MutableStateFlow(OwnedRecordingRuntimeState(null, RecordingRuntimeState.Starting))
        internal val ownedRuntimeState: StateFlow<OwnedRecordingRuntimeState> = mutableOwnedRuntimeState.asStateFlow()
    }
}
