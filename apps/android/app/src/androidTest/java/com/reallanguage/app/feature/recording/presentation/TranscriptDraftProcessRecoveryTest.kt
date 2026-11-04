package com.reallanguage.app.feature.recording.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.recording.data.HttpRecordingRepositories
import com.reallanguage.app.feature.recording.domain.RecordingPlaybackResult
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import com.reallanguage.app.feature.recording.data.AndroidLocalRecordingsRepository
import com.reallanguage.app.feature.recording.data.AndroidRecordingConfirmationRepository
import com.reallanguage.app.feature.recording.data.accountRecordingsDirectory
import com.reallanguage.app.feature.recording.domain.LocalRecordingsLoadResult
import com.reallanguage.app.feature.recording.domain.RecordingLoadResult
import com.reallanguage.app.feature.recording.domain.TranscriptionRequest
import com.reallanguage.app.feature.recording.domain.TranscriptLoadResult
import java.util.UUID

/** Run each phase separately, with am force-stop between them. No user account or provider is used. */
class TranscriptDraftProcessRecoveryTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val directory get() = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "transcript-draft-process-proof")
    private val file get() = File(directory, "proof-transcript.transcript.json")

    @Before fun prepare() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("draftProcessProof") == "true")
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        composeRule.waitUntil(5_000) { composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun open(): TranscriptResultViewModel {
        val runtime = SessionRuntime().apply { update("isolated-test-token", "draft-proof-account") }
        val repository = HttpRecordingRepositories(directory, object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse = error("Draft recovery must not call a provider")
        }, runtime) { RecordingPlaybackResult.Stopped }
        lateinit var model: TranscriptResultViewModel
        composeRule.runOnUiThread { model = TranscriptResultViewModel("proof-recording", "proof-transcript", "repair-proof", "confirm-proof", repository) }
        composeRule.setContent { RlTheme { TranscriptResultScreen(model.uiState.collectAsState().value, model::onAction) } }
        composeRule.waitUntil(5_000) { !model.uiState.value.loading }
        return model
    }

    @Test fun stageDraft() {
        directory.mkdirs()
        file.writeText("""{"accountId":"draft-proof-account","recordingId":"proof-recording","transcriptId":"proof-transcript","fullText":"今天我去公园。","language":"zh-CN","durationSeconds":4,"endReason":"USER_STOPPED","segments":[{"segmentId":"s1","startMs":0,"endMs":4000,"text":"今天我去公园。","speakerLabel":"UNKNOWN","confidence":null,"alternatives":[],"mostLikelyText":null,"mostLikelyIsInference":false}],"finalBoundary":{"acceptedEndMs":4000,"droppedIncompleteTail":false},"transcriptionCommandId":"proof-transcription","confirmedText":null,"confirmedSourceId":null}""")
        File(directory, "stage-pid").writeText(Process.myPid().toString())
        val model = open()
        composeRule.onNodeWithTag("transcript-result-editor").performScrollTo().performTextReplacement(DRAFT)
        composeRule.waitUntil(5_000) { model.uiState.value.draftSaved }
        composeRule.onNodeWithText("编辑已保存在本机").performScrollTo().assertIsDisplayed()
        assertEquals(DRAFT, JSONObject(file.readText()).getString("draftText"))
        assertTrue(JSONObject(file.readText()).isNull("confirmedSourceId"))
    }

    @Test fun readDraftAfterProcessRestart() {
        assertNotEquals("Must run in a new process", File(directory, "stage-pid").readText(), Process.myPid().toString())
        val model = open()
        assertEquals(DRAFT, model.uiState.value.currentText)
        assertTrue(model.uiState.value.draftSaved)
        assertTrue(model.uiState.value.manuallyEdited)
        assertEquals("今天我去公园。", model.uiState.value.transcript?.evidence?.originalText)
        assertTrue(JSONObject(file.readText()).isNull("confirmedSourceId"))
        composeRule.onNodeWithText("编辑已保存在本机").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("transcript-result-confirm").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            File(directory, "restored.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun existingRecordingEntryReopensSavedDraftWithoutAnotherProviderRequest() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.filesDir, "draft-proof-input.m4a")
        assertTrue("Supply the existing isolated synthetic audio fixture", input.isFile)
        val account = "draft-entry-proof-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("isolated-test-token", account) }
        val recordings = accountRecordingsDirectory(context.filesDir, account)
        recordings.mkdirs()
        val recordingId = "proof-entry-recording"
        val transcriptId = "proof-entry-transcript"
        val audio = File(recordings, "$recordingId.m4a")
        val transcriptFile = File(recordings, "$transcriptId.transcript.json")
        try {
            input.copyTo(audio)
            // Reuse previously verified local evidence fixture, not an AI or human quality assertion.
            val saved = JSONObject(file.readText()).put("accountId", account).put("recordingId", recordingId)
                .put("transcriptId", transcriptId).put("transcriptionCommandId", UUID.nameUUIDFromBytes("transcribe:$recordingId".toByteArray()).toString())
            transcriptFile.writeText(saved.toString())
            val entries = AndroidLocalRecordingsRepository(context, runtime).load() as LocalRecordingsLoadResult.Ready
            assertEquals(recordingId, entries.recordings.single().recordingId)
            val summary = (AndroidRecordingConfirmationRepository(context, runtime).load(recordingId) as RecordingLoadResult.Ready).recording
            val repository = HttpRecordingRepositories(context, object : HttpTransport {
                override suspend fun execute(request: HttpRequest): HttpResponse = error("Re-entry must reuse the saved transcript without HTTP")
            }, runtime)
            lateinit var transcription: RecordingTranscriptionViewModel
            composeRule.runOnUiThread {
                transcription = RecordingTranscriptionViewModel(TranscriptionRequest(recordingId, summary.durationSeconds,
                    "audio/mp4", "zh-CN", requireNotNull(summary.endReason)), saved.getString("transcriptionCommandId"), repository)
            }
            val result = transcription.effects.first() as RecordingTranscriptionEffect.OpenResult
            assertEquals(transcriptId, result.transcriptId)
            val restored = (repository.load(result.transcriptId) as TranscriptLoadResult.Ready).transcript
            assertEquals(DRAFT, restored.fullText)
            assertTrue(restored.hasUnconfirmedEdits)
            assertTrue(JSONObject(transcriptFile.readText()).isNull("confirmedSourceId"))
        } finally {
            audio.delete()
            transcriptFile.delete()
            recordings.delete() // Empty isolated account directory only, never recursive user cleanup.
        }
    }

    companion object { private const val DRAFT = "进程重启前修改：今天我和朋友去了公园，但还没有确认。" }
}
