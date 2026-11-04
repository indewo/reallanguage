package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.learning.domain.AudioDictationCompletionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidAudioDictationCompletionRepositoryTest {
    @Test fun exitedDictationEntryCannotCommitAfterSameAccountReauthentication() = runTest {
        val runtime = SessionRuntime().apply { update("isolated-first", "account") }
        var writes = 0
        val repository = AndroidAudioDictationCompletionRepository(runtime, "c1", "old", LearningStepStore { _, _, _, _, _ -> writes++; true })
        runtime.clear()
        runtime.update("isolated-return", "account")
        assertEquals(AudioDictationCompletionResult.Failed, repository.complete("s1"))
        assertEquals(0, writes)
    }

    @Test fun tokenRefreshPreservesDictationAndMidWriteLogoutCannotReportSuccess() = runTest {
        val runtime = SessionRuntime().apply { update("isolated-first", "account") }
        var switchDuringWrite = false
        val repository = AndroidAudioDictationCompletionRepository(runtime, "c1", "current", LearningStepStore { _, _, _, _, _ ->
            if (switchDuringWrite) { runtime.clear(); runtime.update("isolated-return", "account") }; true
        })
        val epoch = runtime.accountEpoch
        runtime.update("isolated-refresh", "account")
        assertEquals(epoch, runtime.accountEpoch)
        assertEquals(AudioDictationCompletionResult.Saved, repository.complete("s1"))
        switchDuringWrite = true
        assertEquals(AudioDictationCompletionResult.Failed, repository.complete("s1"))
    }

    @Test fun correctionUsesTheAtomicCorrectionCompletionPath() = runTest {
        var storedStep: String? = null
        val repository = AndroidAudioDictationCompletionRepository(
            SessionRuntime().apply { update("isolated", "account") }, "c1", "cmd",
            LearningStepStore { _, _, _, step, _ -> storedStep = step; true }, isCorrection = true,
        )
        assertEquals(AudioDictationCompletionResult.Saved, repository.complete("s1"))
        assertEquals("READ_WRITE_CORRECTION", storedStep)
    }

    @Test fun `old dictation cannot mark the new accounts sentence as complete`() = runTest {
        val runtime = SessionRuntime().apply { update("isolated", "old-account") }
        var writes = 0
        val repository = AndroidAudioDictationCompletionRepository(runtime, "c1", "cmd",
            LearningStepStore { _, _, _, _, _ -> writes++; true })
        runtime.update("isolated", "new-account")
        assertEquals(AudioDictationCompletionResult.Failed, repository.complete("s1"))
        assertEquals(0, writes)
    }

    @Test fun `account change during a save cannot report completion to the new session`() = runTest {
        val runtime = SessionRuntime().apply { update("isolated", "old-account") }
        val repository = AndroidAudioDictationCompletionRepository(runtime, "c1", "cmd",
            LearningStepStore { account, _, _, _, _ ->
                assertEquals("old-account", account)
                runtime.update("isolated", "new-account")
                true
            })
        assertEquals(AudioDictationCompletionResult.Failed, repository.complete("s1"))
    }

    @Test fun `saves the completed dictation step under the current account`() = runTest {
        var storedStep: String? = null
        val repository = AndroidAudioDictationCompletionRepository(
            SessionRuntime().apply { update("token", "account") },
            "c1",
            "cmd",
            LearningStepStore { _, _, _, step, _ -> storedStep = step; true },
        )
        assertEquals(AudioDictationCompletionResult.Saved, repository.complete("s1"))
        assertEquals("AUDIO_DICTATION", storedStep)
    }

    @Test fun `missing account and failed local writes stay failed`() = runTest {
        val missing = AndroidAudioDictationCompletionRepository(
            SessionRuntime(), "c1", "cmd", LearningStepStore { _, _, _, _, _ -> true },
        )
        assertEquals(AudioDictationCompletionResult.Failed, missing.complete("s1"))
        val failed = AndroidAudioDictationCompletionRepository(
            SessionRuntime().apply { update("token", "account") },
            "c1",
            "cmd",
            LearningStepStore { _, _, _, _, _ -> false },
        )
        assertEquals(AudioDictationCompletionResult.Failed, failed.complete("s1"))
    }
}
