package com.reallanguage.app.core.app

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.navigation.AppStartupResult
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.auth.domain.LoginCommand
import com.reallanguage.app.feature.auth.domain.LoginResult
import com.reallanguage.app.feature.recording.data.RecordingForegroundService
import com.reallanguage.app.feature.recording.data.accountRecordingStorageKey
import com.reallanguage.app.feature.recording.data.accountRecordingsDirectory
import com.reallanguage.app.feature.recording.data.readRecordingSummary
import com.reallanguage.app.feature.recording.domain.RecordingRuntimeState
import com.reallanguage.app.feature.recording.presentation.ActiveRecordingViewModel
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual default Graph -> factory -> foreground microphone/encoder -> original-file decoder.
 * No playback, semantic judgment, AI, server or physical-device claim; input may be emulator silence.
 * Graph storage and native-service storage are separate, explicitly owned UUID fixture locations. */
class ProductionDefaultGraphRecordingRevocationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun secondDefaultGraphSigningOutStopsTheOldBackgroundServiceAndPreservesDecodableAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        instrumentation.waitForIdleSync()
        assertFalse("Never disturb an existing capture", serviceState().state is RecordingRuntimeState.Recording)
        assertEquals("Host owns permission; the fixture never changes it", PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(base, Manifest.permission.RECORD_AUDIO))
        val prefix = "default-graph-recording-${UUID.randomUUID()}"
        val account = "owner-$prefix"
        val ownerKey = accountRecordingStorageKey(account)
        val graphDirectory = File(base.cacheDir, prefix)
        val serviceDirectory = accountRecordingsDirectory(base.filesDir, account)
        assertFalse("Native service directory must belong to this new UUID account", serviceDirectory.exists())
        val preferences = Collections.synchronizedSet(mutableSetOf<String>())
        val privateDb = IsolatedLearningDatabaseContext(base, prefix)
        val context = object : ContextWrapper(privateDb) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(graphDirectory, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(graphDirectory, "private").apply { mkdirs() }
            override fun getCacheDir() = File(graphDirectory, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = "$prefix-$name"
                preferences.add(isolated)
                return base.getSharedPreferences(isolated, mode)
            }
        }
        val firstCredentials = MemoryCredentials()
        var requests = 0
        val transport = HttpTransport { request ->
            check(++requests == 1 && request.method == "POST" && request.path == "/api/v1/auth/sessions")
            val body = JSONObject(requireNotNull(request.body))
            check(body.getString("email") == "$prefix@example.invalid")
            check(body.getString("password") == "ISOLATED_PASSWORD_NOT_A_CREDENTIAL")
            HttpResponse(200, JSONObject().put("status", "AUTHENTICATED").put("accountId", account)
                .put("accessToken", "ISOLATED_ACCESS_ONLY").put("refreshToken", "ISOLATED_REFRESH_ONLY")
                .put("settingsCompleted", true).toString())
        }
        // Omit SessionRuntime on BOTH graphs: an injected common runtime would mask this regression.
        val first = ProductionAppGraph(context = context, httpTransport = transport, sessionCredentials = firstCredentials)
        // Intentionally outside finally: an occupied default authority must not be cleared by us.
        assertTrue("Leave any existing process account untouched", first.currentSessionOwner().accountId == null)
        var backgrounded = false
        fun active(): RecordingRuntimeState.Recording? = serviceState().let {
            if (it.accountStorageKey == ownerKey) it.state as? RecordingRuntimeState.Recording else null
        }
        compose.setContent { Text("隔离默认会话录音服务测试；无AI与试听") }
        try {
            assertTrue(runBlocking { first.loginForApp(LoginCommand("$prefix@example.invalid", "ISOLATED_PASSWORD_NOT_A_CREDENTIAL")) } is LoginResult.Authenticated)
            val owner = first.currentSessionOwner()
            assertEquals(account, owner.accountId)
            lateinit var recording: ActiveRecordingViewModel
            compose.runOnIdle {
                recording = ViewModelProvider(compose.activity, first.activeRecordingFactory())[ActiveRecordingViewModel::class.java]
            }
            compose.waitUntil(10_000) { active()?.active?.elapsedSeconds?.let { it >= 2 } == true }
            val started = requireNotNull(active()).active
            assertEquals(started.recordingId, recording.uiState.value.recordingId)
            assertNotEquals(context.filesDir.canonicalFile, base.filesDir.canonicalFile)
            assertFalse("The real service must not silently write into the graph's substitute filesDir",
                accountRecordingsDirectory(context.filesDir, account).exists())

            compose.runOnIdle { compose.activity.viewModelStore.clear() }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            backgrounded = true
            compose.waitUntil(5_000) { active()?.active?.let {
                it.recordingId == started.recordingId && it.elapsedSeconds > started.elapsedSeconds
            } == true }
            assertEquals("Clearing the screen and entering background must not stop legal capture", started.recordingId, requireNotNull(active()).active.recordingId)

            val secondCredentials = MemoryCredentials() // Simulated lost installation credentials, never a real store.
            val second = ProductionAppGraph(context = context, httpTransport = transport, sessionCredentials = secondCredentials)
            assertEquals("Rebuilding a default graph retains the service's login authority", owner, second.currentSessionOwner())
            assertNull(runBlocking { secondCredentials.load() })
            assertEquals(AppStartupResult.SignedOut, runBlocking { second.resolveStartupForApp() })
            assertNull(first.currentSessionOwner().accountId)
            assertNull(second.currentSessionOwner().accountId)
            compose.waitUntil(5_000) {
                serviceState().let { it.accountStorageKey == ownerKey && it.state !is RecordingRuntimeState.Recording }
            }
            assertEquals(ownerKey, serviceState().accountStorageKey)
            assertEquals("Session revocation is not successful user completion", RecordingRuntimeState.Failed, serviceState().state)
            val original = File(serviceDirectory, "${started.recordingId}.m4a")
            assertTrue("The real service must preserve its own original", original.isFile && original.length() > 0)
            val duration = assertDecodableOriginal(original)
            assertEquals("SESSION_ENDED", JSONObject(File(serviceDirectory, "${started.recordingId}.recording.json").readText()).getString("endReason"))
            assertNull("Unknown tail cannot become a transcribable user stop", readRecordingSummary(serviceDirectory, started.recordingId, duration).endReason)
            assertEquals("Signing out with empty memory credentials needs no refresh/revoke HTTP", 1, requests)
        } finally {
            if (backgrounded) compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.runOnIdle { compose.activity.viewModelStore.clear() }
            // Even a RED separate-authority implementation is cleaned only through our first owner.
            try {
                if (first.currentSessionOwner().accountId == account) {
                    runBlocking { firstCredentials.clear(); first.resolveStartupForApp() }
                }
            } finally {
                instrumentation.waitForIdleSync()
                if (serviceState().accountStorageKey == ownerKey && serviceState().state is RecordingRuntimeState.Recording) {
                    base.stopService(Intent(base, RecordingForegroundService::class.java))
                    compose.waitUntil(5_000) { active() == null }
                    instrumentation.waitForIdleSync()
                }
                check(serviceDirectory.name == ownerKey && serviceDirectory.canonicalFile.parentFile == File(base.filesDir, "recordings").canonicalFile)
                check(!serviceDirectory.exists() || serviceDirectory.deleteRecursively())
                val database = privateDb.getDatabasePath(DATABASE).canonicalFile
                check(database.parentFile == base.cacheDir.canonicalFile && database.name.startsWith("$prefix-"))
                check(privateDb.deleteDatabase(DATABASE) || !database.exists())
                synchronized(preferences) { preferences.toList() }.forEach { name ->
                    check(name.startsWith("$prefix-")); base.deleteSharedPreferences(name)
                }
                check(graphDirectory.canonicalFile.parentFile == base.cacheDir.canonicalFile && graphDirectory.name == prefix)
                check(!graphDirectory.exists() || graphDirectory.deleteRecursively())
            }
        }
    }

    /** Decode at least one output buffer without rendering sound; metadata alone is not decoding. */
    private fun assertDecodableOriginal(file: File): Long {
        val metadata = MediaMetadataRetriever()
        val duration = try {
            metadata.setDataSource(file.absolutePath)
            requireNotNull(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull())
        } finally { metadata.release() }
        assertTrue("At least two seconds of capture must leave a readable recording", duration >= 1_000)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            try {
                decoder.configure(format, null, null, 0)
                decoder.start()
                val info = MediaCodec.BufferInfo()
                var inputEnded = false
                var decoded = false
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (!decoded && SystemClock.elapsedRealtime() < deadline) {
                    if (!inputEnded) {
                        val inputIndex = decoder.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val input = requireNotNull(decoder.getInputBuffer(inputIndex)).apply { clear() }
                            val size = extractor.readSampleData(input, 0)
                            inputEnded = size < 0
                            decoder.queueInputBuffer(inputIndex, 0, maxOf(size, 0), if (inputEnded) 0 else extractor.sampleTime,
                                if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            if (!inputEnded) extractor.advance()
                        }
                    }
                    val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
                    if (outputIndex >= 0) {
                        decoded = info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
                assertTrue("The retained original must decode to audio samples", decoded)
            } finally { runCatching { decoder.stop() }; decoder.release() }
        } finally { extractor.release() }
        return duration
    }

    private fun serviceState() = RecordingForegroundService.ownedRuntimeState.value
    private class MemoryCredentials : SessionCredentialStore {
        private var value: StoredSession? = null
        override suspend fun load() = value
        override suspend fun save(session: StoredSession) { value = session }
        override suspend fun clear() { value = null }
    }
    private companion object { const val DATABASE = "real-language-learning.db" }
}
