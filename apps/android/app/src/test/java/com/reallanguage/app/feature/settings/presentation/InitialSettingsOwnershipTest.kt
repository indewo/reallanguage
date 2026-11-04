package com.reallanguage.app.feature.settings.presentation

import androidx.lifecycle.ViewModelStore
import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.settings.domain.BasicVocabulary
import com.reallanguage.app.feature.settings.domain.InitialSettingsCompletionResult
import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft
import com.reallanguage.app.feature.settings.domain.InitialSettingsRepository
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Captured real account/epoch predicate; memory repository and actual ViewModel cancellation only. */
@OptIn(ExperimentalCoroutinesApi::class)
class InitialSettingsOwnershipTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun ownershipLostBeforeDispatchMustNotSubmitThePreviousDraft() = runTest {
        var calls = 0
        withModel(save = { calls++; InitialSettingsCompletionResult.Completed }) { model, runtime, _ ->
            val effects = collectEffects(model)
            model.onAction(InitialSettingsAction.CompleteRequested)
            runtime.update("isolated-b", "settings-vm-b", newSession = true)
            runCurrent()
            assertEquals(0, calls)
            assertTrue(effects.isEmpty())
        }
    }

    @Test fun replacedAccountsLateCompletionCannotOpenHome() = assertLateResultIsIgnored("settings-vm-b")

    @Test fun sameAccountsNewSessionRejectsTheOldCompletion() = assertLateResultIsIgnored(ACCOUNT)

    @Test fun entryDisposalCancelsTheActualPendingSaveWithoutReportingAResult() = runTest {
        var cancelled = 0
        withModel(save = {
            try { awaitCancellation() } finally { cancelled++ }
        }) { model, _, store ->
            val effects = collectEffects(model)
            model.onAction(InitialSettingsAction.CompleteRequested)
            runCurrent()
            assertTrue(model.uiState.value.submitting)
            store.clear()
            runCurrent()
            assertEquals(1, cancelled)
            assertTrue(effects.isEmpty())
            assertNull(model.uiState.value.error)
        }
    }

    @Test fun nonCooperatingCompletionAfterEntryDisposalCannotPublishOldStateOrHome() = runTest {
        var callback: Continuation<InitialSettingsCompletionResult>? = null
        var released = false
        try {
            withModel(save = { suspendCoroutine { callback = it } }) { model, _, store ->
                val effects = collectEffects(model)
                model.onAction(InitialSettingsAction.CompleteRequested)
                runCurrent()
                assertTrue(callback != null)
                store.clear()
                runCurrent()
                val departed = model.uiState.value
                checkNotNull(callback).resume(InitialSettingsCompletionResult.Completed)
                released = true
                runCurrent()
                assertEquals("A disposed entry cannot publish a late success", departed, model.uiState.value)
                assertTrue(effects.isEmpty())
            }
        } finally {
            if (!released) callback?.resume(InitialSettingsCompletionResult.Completed)
            runCurrent()
        }
    }

    @Test fun cancellationPropagatesWithoutSuccessOrSaveFailureAndReleasesTheBusyState() = runTest {
        val entered = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()
        val cancelled = CancellationException("isolated setup save cancelled")
        withModel(save = {
            entered.complete(checkNotNull(currentCoroutineContext()[Job]))
            release.await()
            throw cancelled
        }) { model, _, _ ->
            val effects = collectEffects(model)
            model.onAction(InitialSettingsAction.CompleteRequested)
            runCurrent()
            val job = entered.await()
            var cause: Throwable? = null
            job.invokeOnCompletion { cause = it }
            release.complete(Unit)
            runCurrent()
            assertTrue("Cancellation must retain cancellation semantics", job.isCancelled)
            assertTrue(generateSequence(cause) { it.cause }.take(16).any {
                it is CancellationException && it.message == cancelled.message
            })
            assertFalse("A cancelled request must not leave a permanent submitting state", model.uiState.value.submitting)
            assertNull(model.uiState.value.error)
            assertTrue(effects.isEmpty())
        }
    }

    @Test fun sameOwnerRefreshDuringSaveKeepsTheDraftAndOpensHomeExactlyOnce() = runTest {
        val pending = CompletableDeferred<InitialSettingsCompletionResult>()
        val saved = mutableListOf<InitialSettingsDraft>()
        withModel(save = { saved += it; pending.await() }) { model, runtime, _ ->
            val effects = collectEffects(model)
            val draft = model.uiState.value.draft
            val epoch = runtime.accountEpoch
            repeat(3) { model.onAction(InitialSettingsAction.CompleteRequested) }
            runCurrent()
            runtime.update("isolated-refresh", ACCOUNT)
            assertEquals(epoch, runtime.accountEpoch)
            repeat(3) { model.onAction(InitialSettingsAction.CompleteRequested) }
            assertEquals(listOf(draft), saved)
            pending.complete(InitialSettingsCompletionResult.Completed)
            runCurrent()
            assertEquals(listOf(InitialSettingsEffect.OpenHome), effects)
            assertEquals(draft, model.uiState.value.draft)
            assertFalse(model.uiState.value.submitting)
            assertNull(model.uiState.value.error)
        }
    }

    private fun assertLateResultIsIgnored(nextAccount: String) = runTest {
        for (result in listOf(InitialSettingsCompletionResult.Completed, InitialSettingsCompletionResult.Failed(true))) {
            val pending = CompletableDeferred<InitialSettingsCompletionResult>()
            var calls = 0
            withModel(save = { calls++; pending.await() }) { model, runtime, _ ->
                val effects = collectEffects(model)
                model.onAction(InitialSettingsAction.CompleteRequested)
                runCurrent()
                assertEquals(1, calls)
                val epoch = runtime.accountEpoch
                runtime.update("isolated-new-session", nextAccount, newSession = true)
                assertTrue(runtime.accountEpoch > epoch)
                pending.complete(result)
                runCurrent()
                assertTrue("Old completion cannot navigate the replacement session", effects.isEmpty())
                assertNull("Old failure cannot become the new session's visible error", model.uiState.value.error)
                model.onAction(InitialSettingsAction.CompleteRequested)
                runCurrent()
                assertEquals("The old entry cannot initiate another save", 1, calls)
            }
        }
    }

    private suspend fun TestScope.withModel(
        save: suspend (InitialSettingsDraft) -> InitialSettingsCompletionResult,
        check: suspend (InitialSettingsViewModel, SessionRuntime, ViewModelStore) -> Unit,
    ) {
        val runtime = SessionRuntime().apply { update("isolated-original-a", ACCOUNT, newSession = true) }
        val account = runtime.accountId
        val epoch = runtime.accountEpoch
        val store = ViewModelStore()
        val model = InitialSettingsViewModel(
            validator = ConfirmedInitialSettingsValidator,
            repository = InitialSettingsRepository { save(it) },
            isOwnerActive = { runtime.accountId == account && runtime.accountEpoch == epoch && runtime.accessToken != null },
        )
        store.put("isolated-setup", model)
        model.onAction(InitialSettingsAction.FormalityChanged(3))
        model.onAction(InitialSettingsAction.SlangChanged(false))
        model.onAction(InitialSettingsAction.BasicVocabularyChanged(BasicVocabulary.CET_4))
        model.onAction(InitialSettingsAction.ContinueRequested)
        model.onAction(InitialSettingsAction.ContinueRequested)
        model.onAction(InitialSettingsAction.VoiceChanged(true))
        model.onAction(InitialSettingsAction.AutoPlayChanged(false))
        model.onAction(InitialSettingsAction.SpeechSpeedChanged(3))
        try { check(model, runtime, store) } finally { store.clear(); runCurrent() }
    }

    private fun TestScope.collectEffects(model: InitialSettingsViewModel): MutableList<InitialSettingsEffect> =
        mutableListOf<InitialSettingsEffect>().also { effects ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.toList(effects) }
        }

    private companion object { const val ACCOUNT = "settings-vm-a" }
}
