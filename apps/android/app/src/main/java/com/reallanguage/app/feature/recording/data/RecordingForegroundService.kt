package com.reallanguage.app.feature.recording.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.reallanguage.app.MainActivity
import com.reallanguage.app.R
import com.reallanguage.app.feature.recording.domain.ActiveRecording
import com.reallanguage.app.feature.recording.domain.RecordingRuntimeState
import com.reallanguage.app.feature.recording.presentation.ActiveRecordingViewModel
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
            ACTION_STOP -> if (intent.getStringExtra(EXTRA_RECORDING_ID) == recordingId &&
                intent.getStringExtra(EXTRA_ACCOUNT_STORAGE_KEY) == recordingDirectory?.name
            ) stopRecording()
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
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else {
                @Suppress("DEPRECATION") MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setMaxDuration(ActiveRecordingViewModel.MAX_DURATION_SECONDS * 1_000)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopRecording(maximumReached = true)
                }
                setOutputFile(output.absolutePath)
                prepare()
                start()
            }
            recordingId = id
            elapsedSeconds = 0
            elapsedTime = RecordingElapsedTime(SystemClock::elapsedRealtime)
            if (!owner.isCurrent()) { stopRecording(); return }
            publish(RecordingRuntimeState.Recording(ActiveRecording(id, 0)))
            ownerJob = serviceScope.launch {
                owner.runtime.identityRevision.collect { if (!owner.isCurrent()) stopRecording() }
            }
            timerJob = serviceScope.launch {
                while (isActive) {
                    delay(1_000)
                    elapsedSeconds = elapsedTime?.tick() ?: 0
                    publish(RecordingRuntimeState.Recording(
                        ActiveRecording(id, elapsedSeconds),
                    ))
                    if (elapsedSeconds >= ActiveRecordingViewModel.MAX_DURATION_SECONDS) {
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

    private fun stopRecording(maximumReached: Boolean = false) {
        val id = recordingId ?: return
        val sessionEnded = captureOwner?.isCurrent() != true
        // Native callbacks, button events and the fallback timer are serialized on Main.
        recordingId = null
        elapsedSeconds = if (maximumReached) ActiveRecordingViewModel.MAX_DURATION_SECONDS else elapsedTime?.tick() ?: elapsedSeconds
        timerJob?.cancel()
        try {
            recorder?.stop()
            releaseRecorder()
            val directory = requireNotNull(recordingDirectory)
            writeStopEvidence(directory, id, when {
                sessionEnded -> "SESSION_ENDED"
                maximumReached -> "MAX_DURATION"
                else -> "USER_STOPPED"
            })
            publish(if (sessionEnded) RecordingRuntimeState.Failed else RecordingRuntimeState.Completed(id, elapsedSeconds))
        } catch (_: Exception) {
            releaseRecorder()
            publish(RecordingRuntimeState.Failed)
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun writeStopEvidence(directory: File, id: String, reason: String) {
        val evidence = File(directory, "$id.recording.json")
        val temporary = File.createTempFile("recording-", ".tmp", directory)
        try {
            val value = JSONObject().put("formatVersion", 1).put("recordingId", id)
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
