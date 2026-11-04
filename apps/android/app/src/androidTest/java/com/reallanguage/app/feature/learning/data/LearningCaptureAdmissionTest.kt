package com.reallanguage.app.feature.learning.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import java.io.Closeable
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Repository admission only: no actual microphone, audio files, HTTP, SQL, credentials or AI calls. */
class LearningCaptureAdmissionTest {
    @Test fun readSpeakRejectsAnInitiallyBlockedConnection() = verify(Module.READ, Scenario.INITIALLY_BLOCKED)
    @Test fun fullSpeakingRejectsAnInitiallyBlockedConnection() = verify(Module.FULL, Scenario.INITIALLY_BLOCKED)
    @Test fun patternSpeakingRejectsAnInitiallyBlockedConnection() = verify(Module.PATTERN, Scenario.INITIALLY_BLOCKED)
    @Test fun sceneResponseRejectsAnInitiallyBlockedConnection() = verify(Module.SCENE, Scenario.INITIALLY_BLOCKED)

    @Test fun readSpeakStopsTheMicWhenConnectionIsRevokedInsideStart() = verify(Module.READ, Scenario.CONNECTION_REVOKED)
    @Test fun fullSpeakingStopsTheMicWhenConnectionIsRevokedInsideStart() = verify(Module.FULL, Scenario.CONNECTION_REVOKED)
    @Test fun patternSpeakingStopsTheMicWhenConnectionIsRevokedInsideStart() = verify(Module.PATTERN, Scenario.CONNECTION_REVOKED)
    @Test fun sceneResponseStopsTheMicWhenConnectionIsRevokedInsideStart() = verify(Module.SCENE, Scenario.CONNECTION_REVOKED)

    @Test fun readSpeakStopsTheMicWhenSameAccountSessionChangesInsideStart() = verify(Module.READ, Scenario.NEW_SESSION)
    @Test fun fullSpeakingStopsTheMicWhenSameAccountSessionChangesInsideStart() = verify(Module.FULL, Scenario.NEW_SESSION)
    @Test fun patternSpeakingStopsTheMicWhenSameAccountSessionChangesInsideStart() = verify(Module.PATTERN, Scenario.NEW_SESSION)
    @Test fun sceneResponseStopsTheMicWhenSameAccountSessionChangesInsideStart() = verify(Module.SCENE, Scenario.NEW_SESSION)

    @Test fun readSpeakAllowsOrdinaryTokenRefreshInsideStart() = verify(Module.READ, Scenario.TOKEN_REFRESH)
    @Test fun fullSpeakingAllowsOrdinaryTokenRefreshInsideStart() = verify(Module.FULL, Scenario.TOKEN_REFRESH)
    @Test fun patternSpeakingAllowsOrdinaryTokenRefreshInsideStart() = verify(Module.PATTERN, Scenario.TOKEN_REFRESH)
    @Test fun sceneResponseAllowsOrdinaryTokenRefreshInsideStart() = verify(Module.SCENE, Scenario.TOKEN_REFRESH)

    private fun verify(module: Module, scenario: Scenario) {
        Fixture(module).use { f ->
            val originalEpoch = f.runtime.accountEpoch
            when (scenario) {
                Scenario.INITIALLY_BLOCKED -> f.connectionAllowed = false
                Scenario.CONNECTION_REVOKED -> f.capture.onStart = { f.connectionAllowed = false }
                Scenario.NEW_SESSION -> f.capture.onStart = {
                    f.runtime.update("isolated-new-session", f.account, newSession = true)
                }
                Scenario.TOKEN_REFRESH -> f.capture.onStart = {
                    f.runtime.update("isolated-refreshed-session", f.account)
                }
            }

            val accepted = f.start()
            val label = "$module / $scenario"
            assertEquals(label, f.account, f.runtime.accountId)
            if (scenario == Scenario.NEW_SESSION) assertNotEquals(originalEpoch, f.runtime.accountEpoch)
            else assertEquals(label, originalEpoch, f.runtime.accountEpoch)
            when (scenario) {
                Scenario.INITIALLY_BLOCKED -> {
                    assertFalse(label, accepted)
                    assertEquals(label, 0, f.capture.starts)
                    assertFalse(label, f.capture.live)
                    assertEquals(label, 0, f.capture.stoppedLive)
                }
                Scenario.CONNECTION_REVOKED, Scenario.NEW_SESSION -> {
                    assertFalse("$label must reject the newly unauthorized capture", accepted)
                    assertEquals(label, 1, f.capture.starts)
                    assertFalse("$label must stop the mic, not merely report false", f.capture.live)
                    assertEquals(label, 1, f.capture.cancels)
                    assertEquals(label, 1, f.capture.stoppedLive)
                }
                Scenario.TOKEN_REFRESH -> {
                    assertTrue(label, accepted)
                    assertEquals(label, "isolated-refreshed-session", f.runtime.accessToken)
                    assertEquals(label, 1, f.capture.starts)
                    assertTrue(label, f.capture.live)
                    assertEquals(label, 0, f.capture.cancels)
                    assertEquals(label, 0, f.capture.stoppedLive)
                }
            }
            assertEquals("$label must not finish or submit a recording", 0, f.capture.finishes)
            assertEquals(label, 0, f.httpCalls)
            assertEquals(label, 0, f.trainingSaves)
        }
    }

    private enum class Module { READ, FULL, PATTERN, SCENE }
    private enum class Scenario { INITIALLY_BLOCKED, CONNECTION_REVOKED, NEW_SESSION, TOKEN_REFRESH }

    private class Capture : ReadSpeakAudioCapture {
        var onStart: () -> Unit = {}
        var starts = 0
        var cancels = 0
        var finishes = 0
        var stoppedLive = 0
        var live = false
        override fun start(): Boolean {
            check(!live)
            starts++
            live = true
            onStart()
            return true
        }
        override fun finish(): CapturedReadSpeakAudio? {
            finishes++
            throw AssertionError("Admission must not finish or submit audio")
        }
        override fun cancel() {
            cancels++
            if (live) stoppedLive++
            live = false
        }
    }

    private class Fixture(module: Module) : Closeable {
        val account = "capture-admission-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("isolated-original-session", account) }
        val capture = Capture()
        var connectionAllowed = true
        var httpCalls = 0
        var trainingSaves = 0
        private val transport = HttpTransport {
            httpCalls++
            throw AssertionError("Admission must not perform HTTP")
        }
        private val entry: Pair<() -> Boolean, () -> Unit> = when (module) {
            Module.READ -> AndroidReadSpeakRepository(
                transport = transport, runtime = runtime, chapterId = "isolated-chapter",
                targetTextEn = "I feel ready.", commandId = UUID.randomUUID().toString(), capture = capture,
                stepStore = LearningStepStore { _, _, _, _, _ ->
                    trainingSaves++
                    throw AssertionError("Admission must not save training")
                },
                isCaptureAllowed = { connectionAllowed },
            ).let { it::startCapture to it::cancelCapture }
            Module.FULL -> AndroidFullSentenceSpeakingRepository(
                transport = transport, runtime = runtime, chapterId = "isolated-chapter",
                targetTextEn = "I feel ready.", capture = capture,
                isCaptureAllowed = { connectionAllowed },
            ).let { it::startCapture to it::cancelCapture }
            Module.PATTERN -> AndroidPatternSpeakingRepository(
                transport = transport, runtime = runtime,
                chapter = FixedLearningChapter("isolated-chapter", 1,
                    listOf(FixedLearningSentence("isolated-sentence", "I feel ready.", "我准备好了。", true))),
                capture = capture, isCaptureAllowed = { connectionAllowed },
            ).let { it::startCapture to it::cancelCapture }
            Module.SCENE -> AndroidSceneResponseRepository(
                context = NoStorageContext(InstrumentationRegistry.getInstrumentation().targetContext),
                transport = transport, runtime = runtime, capture = capture,
                isCaptureAllowed = { connectionAllowed },
            ).let { it::startCapture to it::cancelCapture }
        }
        fun start(): Boolean = entry.first()
        override fun close() {
            try { entry.second() } finally { capture.cancel(); runtime.clear() }
        }
    }

    /** Scene's constructor only retains the context; any accidental storage access must fail closed. */
    private class NoStorageContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = throw AssertionError("No database access in admission tests")
        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
        ): SQLiteDatabase = throw AssertionError("No database access in admission tests")
        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?,
        ): SQLiteDatabase = throw AssertionError("No database access in admission tests")
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            throw AssertionError("No preferences or credentials in admission tests")
    }
}
