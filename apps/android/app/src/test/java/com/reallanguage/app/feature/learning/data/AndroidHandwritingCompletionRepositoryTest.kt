package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.learning.domain.HandwritingCompletionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidHandwritingCompletionRepositoryTest {
    @Test fun exitedHandwritingEntryCannotCommitAfterSameAccountReauthentication() = runTest {
        val runtime = SessionRuntime().apply { update("isolated-first", "account") }
        var writes = 0
        val repository = AndroidHandwritingCompletionRepository(runtime, "c1", "old", LearningStepStore { _, _, _, _, _ -> writes++; true })
        runtime.clear()
        runtime.update("isolated-next", "account")
        assertEquals(HandwritingCompletionResult.Failed, repository.complete("s1", 2))
        assertEquals(0, writes)
    }

    @Test fun oldHandwritingEntryCannotAdoptAnotherAccountsMatchingChapter() = runTest {
        val runtime = SessionRuntime().apply { update("isolated-first", "account") }
        var writes = 0
        val repository = AndroidHandwritingCompletionRepository(runtime, "c1", "old", LearningStepStore { _, _, _, _, _ -> writes++; true })
        runtime.update("isolated-other", "other")
        assertEquals(HandwritingCompletionResult.Failed, repository.complete("s1", 2))
        assertEquals(0, writes)
    }

    @Test fun tokenRefreshPreservesHandwritingAndMidWriteLogoutCannotReportSuccess() = runTest {
        val runtime = SessionRuntime().apply { update("isolated-first", "account") }
        var switchDuringWrite = false
        val repository = AndroidHandwritingCompletionRepository(runtime, "c1", "current", LearningStepStore { _, _, _, _, _ ->
            if (switchDuringWrite) { runtime.clear(); runtime.update("isolated-return", "account") }; true
        })
        val epoch = runtime.accountEpoch
        runtime.update("isolated-refresh", "account")
        assertEquals(epoch, runtime.accountEpoch)
        assertEquals(HandwritingCompletionResult.Saved, repository.complete("s1", 2))
        switchDuringWrite = true
        assertEquals(HandwritingCompletionResult.Failed, repository.complete("s1", 2))
    }

    @Test fun `persists only two or more user-confirmed copies under the current account`() =
        runTest {
            val store = HandwritingStepStore()
            val repository = AndroidHandwritingCompletionRepository(
                SessionRuntime().apply { update("token", "account") },
                "c1",
                "cmd",
                store,
            )
            assertEquals(
                HandwritingCompletionResult.Failed,
                repository.complete("s1", 1),
            )
            assertEquals(
                HandwritingCompletionResult.Saved,
                repository.complete("s1", 2),
            )
            assertEquals(listOf("account", "c1", "s1", "HANDWRITING_COPY", "cmd"), store.values)
        }

    @Test fun `missing account or local write failure cannot advance`() = runTest {
        val missingAccount = AndroidHandwritingCompletionRepository(
            SessionRuntime(), "c1", "cmd", LearningStepStore { _, _, _, _, _ -> true },
        )
        assertEquals(HandwritingCompletionResult.Failed, missingAccount.complete("s1", 2))
        val failedWrite = AndroidHandwritingCompletionRepository(
            SessionRuntime().apply { update("token", "account") },
            "c1",
            "cmd",
            LearningStepStore { _, _, _, _, _ -> false },
        )
        assertEquals(HandwritingCompletionResult.Failed, failedWrite.complete("s1", 2))
    }
}

private class HandwritingStepStore : LearningStepStore {
    var values: List<String> = emptyList()
    override fun complete(
        accountId: String,
        chapterId: String,
        sentenceId: String,
        step: String,
        commandId: String,
    ): Boolean {
        values = listOf(accountId, chapterId, sentenceId, step, commandId)
        return true
    }
}
