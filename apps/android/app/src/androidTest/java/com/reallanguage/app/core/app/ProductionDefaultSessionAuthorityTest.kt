package com.reallanguage.app.core.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.navigation.AppStartupResult
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.auth.domain.LoginCommand
import com.reallanguage.app.feature.auth.domain.LoginResult
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual Graph/session wiring with UUID storage and memory-only synthetic credentials; no HTTP. */
class ProductionDefaultSessionAuthorityTest {
    @Test fun rebuildingTheDefaultGraphRetainsTheSameAuthenticatedAccountAndEpoch() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "default-authority-${UUID.randomUUID()}"
        val account = "account-$prefix"
        val directory = File(base.cacheDir, prefix)
        check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && directory.mkdir())
        val preferences = Collections.synchronizedSet(mutableSetOf<String>())
        val privateDb = IsolatedLearningDatabaseContext(base, prefix)
        val context = object : ContextWrapper(privateDb) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = "$prefix-$name"
                preferences.add(isolated)
                return base.getSharedPreferences(isolated, mode)
            }
        }
        val credentials = MemoryCredentials()
        var requests = 0
        val transport = HttpTransport { request ->
            check(request.method == "POST" && request.path == "/api/v1/auth/sessions")
            val body = JSONObject(request.body)
            check(body.getString("email") == "$prefix@example.invalid")
            check(body.getString("password") == "ISOLATED_PASSWORD_NOT_A_CREDENTIAL")
            requests += 1
            HttpResponse(200, JSONObject().put("status", "AUTHENTICATED")
                .put("accountId", account).put("accessToken", "ISOLATED_ACCESS_ONLY")
                .put("refreshToken", "ISOLATED_REFRESH_ONLY").put("settingsCompleted", true).toString())
        }
        // Deliberately omit sessionRuntime: injecting one would hide a broken default authority.
        val graph1 = ProductionAppGraph(context = context, httpTransport = transport, sessionCredentials = credentials)
        // Before the cleanup region: never clear or inspect another existing process account.
        assertNull("A preexisting process account must be left untouched", graph1.currentSessionOwner().accountId)
        try {
            val initial = graph1.currentSessionOwner()
            val result = graph1.loginForApp(LoginCommand("$prefix@example.invalid", "ISOLATED_PASSWORD_NOT_A_CREDENTIAL"))
            assertTrue(result is LoginResult.Authenticated)
            val authenticated = graph1.currentSessionOwner()
            assertEquals(account, authenticated.accountId)
            assertTrue("Explicit login must create a new epoch", authenticated.epoch > initial.epoch)
            val graph2 = ProductionAppGraph(context = context, httpTransport = transport, sessionCredentials = credentials)
            assertEquals("A default graph rebuild must retain the live account and epoch", authenticated, graph2.currentSessionOwner())
            assertEquals(1, requests)
            assertEquals(account, credentials.value?.accountId)
        } finally {
            // Revoke only the account established by this test, through the public startup seam.
            if (graph1.currentSessionOwner().accountId == account) {
                credentials.clear()
                assertEquals(AppStartupResult.SignedOut, graph1.resolveStartupForApp())
                assertNull(graph1.currentSessionOwner().accountId)
            }
            val database = privateDb.getDatabasePath("real-language-learning.db").canonicalFile
            check(database.parentFile == base.cacheDir.canonicalFile && database.name.startsWith("$prefix-"))
            check(privateDb.deleteDatabase("real-language-learning.db") || !database.exists())
            synchronized(preferences) { preferences.toList() }.forEach { name ->
                check(name.startsWith("$prefix-")); base.deleteSharedPreferences(name)
            }
            check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && directory.name == prefix)
            check(!directory.exists() || directory.deleteRecursively())
        }
    }

    private class MemoryCredentials : SessionCredentialStore {
        var value: StoredSession? = null
        override suspend fun load() = value
        override suspend fun save(session: StoredSession) { value = session }
        override suspend fun clear() { value = null }
    }
}
