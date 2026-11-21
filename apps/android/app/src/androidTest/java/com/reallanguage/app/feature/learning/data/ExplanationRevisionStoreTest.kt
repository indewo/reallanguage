package com.reallanguage.app.feature.learning.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ExplanationRevisionStoreTest {
    private fun isolated(block: (Context, String) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "revision-test-" + UUID.randomUUID()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(ignored: String?, mode: Int) = base.getSharedPreferences(name, mode)
        }
        try { block(context, name) } finally { base.deleteSharedPreferences(name) }
    }
    @Test fun pendingIdentitySurvivesNewStoreAndCannotBeReplacedByRetry() = isolated { context, _ ->
        val first = AndroidExplanationRevisionStore(context).begin("scope")
        val reopened = AndroidExplanationRevisionStore(context)
        assertTrue(first.pending)
        assertEquals(first, reopened.read("scope"))
        assertEquals(first, reopened.begin("scope"))
    }
    @Test fun completedIdentityAllowsExplicitNextRevisionButLateCompletionCannotOverwriteIt() = isolated { context, _ ->
        val store = AndroidExplanationRevisionStore(context)
        val first = store.begin("scope")
        store.complete("scope", first.id)
        assertFalse(store.read("scope")!!.pending)
        val second = store.begin("scope")
        assertNotEquals(first.id, second.id)
        assertTrue(runCatching { store.complete("scope", first.id) }.isFailure)
        assertEquals(second, AndroidExplanationRevisionStore(context).read("scope"))
    }
    @Test fun corruptMetadataNeverSilentlyStartsAnotherPaidIdentity() = isolated { context, name ->
        val key = UUID.nameUUIDFromBytes("scope".toByteArray(Charsets.UTF_8)).toString()
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString(key, "broken").commit()
        val store = AndroidExplanationRevisionStore(context)
        assertTrue(runCatching { store.read("scope") }.isFailure)
        assertTrue(runCatching { store.begin("scope") }.isFailure)
        assertEquals("broken", context.getSharedPreferences(name, Context.MODE_PRIVATE).getString(key, null))
    }
}
