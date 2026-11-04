package com.reallanguage.app.feature.settings.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.settings.domain.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VersionedSubsequentSettingsRepositoryTest {
    private val original = InitialSettingsDraft(3, false, BasicVocabulary.CET_6,
        setOf(PurposeVocabulary.GRE), "Clear", true, false, 3)
    private val proposed = original.copy(speechSpeedLevel = 4)
    private val runtime = SessionRuntime().apply { update("test-access", "test-owner") }
    private val codec = object : SubsequentSettingsWireCodec {
        override fun request(settings: InitialSettingsDraft) = "old-unversioned"
        override fun current(body: String) = when (body) { "old1", "old3" -> original; "new2" -> proposed; else -> null }
        override fun rowVersion(body: String) = when (body) { "old1" -> 1L; "new2" -> 2L; "old3" -> 3L; else -> null }
        override fun saved(body: String) = body == "saved2"
        override fun updatedVersion(body: String) = 2L.takeIf { body == "saved2" }
        override fun updateRequest(settings: InitialSettingsDraft, expectedRowVersion: Long) = "$expectedRowVersion:${settings.speechSpeedLevel}"
        override fun versionConflict(body: String) = body == "known-conflict"
    }
    @Test fun savesThroughDedicatedVersionedEndpointAndRequiresPersistedReadBack() = runTest {
        val requests = mutableListOf<HttpRequest>()
        val replies = ArrayDeque(listOf("old1", "saved2", "new2"))
        val repo = HttpSubsequentSettingsRepository(HttpTransport { request ->
            requests += request; HttpResponse(200, replies.removeFirst())
        }, codec, runtime)
        assertEquals(SubsequentSettingsLoadResult.Ready(original), repo.load())
        assertEquals(SubsequentSettingsSaveResult.Saved, repo.save(proposed, "save-1"))
        assertEquals(listOf("GET", "PUT", "GET"), requests.map { it.method })
        assertTrue(requests.all { it.path == "/api/v1/settings/current/revision" })
        assertEquals("1:4", requests[1].body)
    }
    @Test fun successStatusWithUnchangedOrUnreadableReadBackIsNotSaved() = runTest {
        for (readBack in listOf("old1", "{}")) {
            val replies = ArrayDeque(listOf("old1", "saved2", readBack))
            val repo = HttpSubsequentSettingsRepository(HttpTransport { HttpResponse(200, replies.removeFirst()) }, codec, runtime)
            repo.load()
            assertEquals(SubsequentSettingsSaveResult.Failed, repo.save(proposed, "save-1"))
        }
    }
    @Test fun noLoadedVersionOrChangedAccountCannotWrite() = runTest {
        val requests = mutableListOf<HttpRequest>()
        val repo = HttpSubsequentSettingsRepository(HttpTransport { requests += it; HttpResponse(200, "old1") }, codec, runtime)
        assertEquals(SubsequentSettingsSaveResult.Failed, repo.save(proposed, "save-1"))
        assertTrue(requests.isEmpty())
        repo.load()
        runtime.update("other-access", "other-owner")
        assertEquals(SubsequentSettingsSaveResult.Failed, repo.save(proposed, "save-1"))
        assertEquals(1, requests.size)
    }
    @Test fun lostResponseRetryKeepsTheOriginalVersionAndPayloadAndDoesNotReturnFalseSuccess() = runTest {
        val requests = mutableListOf<HttpRequest>()
        var puts = 0
        val repo = HttpSubsequentSettingsRepository(HttpTransport {
            requests += it
            when (it.method) {
                "GET" -> HttpResponse(200, if (puts == 0) "old1" else "new2")
                else -> { puts++; if (puts == 1) throw IOException("isolated lost response") else HttpResponse(200, "saved2") }
            }
        }, codec, runtime)
        repo.load()
        assertEquals(SubsequentSettingsSaveResult.Failed, repo.save(proposed, "save-1"))
        assertEquals(SubsequentSettingsSaveResult.Saved, repo.save(proposed, "save-1"))
        assertEquals(listOf("1:4", "1:4"), requests.filter { it.method == "PUT" }.map { it.body })
        assertEquals(SubsequentSettingsSaveResult.Failed, repo.save(original, "save-1"))
        assertEquals(2, puts)
    }
    @Test fun conflictDoesNotOverwriteOrReportSaved() = runTest {
        val requests = mutableListOf<HttpRequest>()
        val repo = HttpSubsequentSettingsRepository(HttpTransport {
            requests += it; if (it.method == "GET") HttpResponse(200, "old1") else HttpResponse(409, "conflict")
        }, codec, runtime)
        repo.load()
        assertEquals(SubsequentSettingsSaveResult.Failed, repo.save(proposed, "save-1"))
        assertEquals(1, requests.count { it.method == "PUT" })
    }
    @Test fun duplicateClicksShareVerifiedResultAndAChangedSessionDiscardsLateAcknowledgement() = runTest {
        val release = CompletableDeferred<Unit>()
        var puts = 0
        val repo = HttpSubsequentSettingsRepository(HttpTransport {
            if (it.method == "GET") HttpResponse(200, if (puts == 0) "old1" else "new2")
            else { puts++; release.await(); HttpResponse(200, "saved2") }
        }, codec, runtime)
        repo.load()
        val first = async { repo.save(proposed, "save-1") }
        runCurrent()
        val duplicate = async { repo.save(proposed, "save-1") }
        runCurrent()
        release.complete(Unit)
        assertEquals(SubsequentSettingsSaveResult.Saved, first.await())
        assertEquals(SubsequentSettingsSaveResult.Saved, duplicate.await())
        assertEquals(1, puts)

        val late = CompletableDeferred<Unit>()
        val repo2 = HttpSubsequentSettingsRepository(HttpTransport {
            if (it.method == "GET") HttpResponse(200, "old1") else { late.await(); HttpResponse(200, "saved2") }
        }, codec, runtime)
        repo2.load()
        val pending = async { repo2.save(proposed, "save-2") }
        runCurrent()
        runtime.clear()
        runtime.update("new-token", "test-owner")
        late.complete(Unit)
        assertEquals(SubsequentSettingsSaveResult.Failed, pending.await())
    }
    @Test fun cancellationFromLoadAndSaveRemainsCancellation() = runTest {
        val loading = HttpSubsequentSettingsRepository(HttpTransport { throw CancellationException("cancel") }, codec, runtime)
        try { loading.load(); fail("load swallowed cancellation") } catch (_: CancellationException) { }
        val saving = HttpSubsequentSettingsRepository(HttpTransport {
            if (it.method == "GET") HttpResponse(200, "old1") else throw CancellationException("cancel")
        }, codec, runtime)
        saving.load()
        try { saving.save(proposed, "save-1"); fail("save swallowed cancellation") } catch (_: CancellationException) { }
    }
    @Test fun knownConflictReachesBothSettingsAndEffectRepositoriesWithoutBlindRetry() = runTest {
        val repo = HttpSubsequentSettingsRepository(HttpTransport {
            if (it.method == "GET") HttpResponse(200, "old1") else HttpResponse(409, "known-conflict")
        }, codec, runtime)
        repo.load()
        assertEquals(SubsequentSettingsSaveResult.Conflict, repo.save(proposed, "conflict"))
        val effect = HttpSettingsEffectRepository(repo)
        assertEquals(SettingsEffectApplyResult.Conflict, effect.apply(SettingsEffectCommand(original, proposed, SettingsEffectScope.NextContent, "conflict")))
    }
    @Test fun cachedOldSuccessCannotPretendToSaveAfterANewerSnapshotWasRead() = runTest {
        val replies = ArrayDeque(listOf("old1", "saved2", "new2", "old3"))
        var requests = 0
        val repo = HttpSubsequentSettingsRepository(HttpTransport { requests++; HttpResponse(200, replies.removeFirst()) }, codec, runtime)
        repo.load()
        assertEquals(SubsequentSettingsSaveResult.Saved, repo.save(proposed, "old-command"))
        assertEquals(SubsequentSettingsLoadResult.Ready(original), repo.load())
        assertEquals(SubsequentSettingsSaveResult.Failed, repo.save(proposed, "old-command"))
        assertEquals(4, requests)
    }
}
