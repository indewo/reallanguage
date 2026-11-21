package com.reallanguage.app.feature.learning.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SentenceAudioRevisionStoreTest {
    private fun isolated(block: (Context, android.content.SharedPreferences) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "audio-revision-test-" + UUID.randomUUID()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(ignored: String?, mode: Int) = base.getSharedPreferences(name, mode)
        }
        try { block(context, base.getSharedPreferences(name, Context.MODE_PRIVATE)) }
        finally { base.deleteSharedPreferences(name) }
    }
    @Test fun unconfirmedRetryKeepsItsIdentityAcrossInstancesAndExplicitRetryClicks() = isolated { context, _ ->
        assertEquals(0, SentenceAudioRevisionStore(context).request("scope", false))
        assertEquals(1, SentenceAudioRevisionStore(context).request("scope", true))
        val reopened = SentenceAudioRevisionStore(context)
        assertEquals(1, reopened.request("scope", true))
        assertEquals(1, reopened.request("scope", false))
        reopened.delivered("scope", 1)
        assertEquals(2, reopened.request("scope", true))
        assertEquals(2, reopened.request("scope", true))
        assertTrue(runCatching { reopened.delivered("scope", 1) }.isFailure)
        assertEquals(2, reopened.request("scope", true))
    }
    @Test fun legacyCounterMustReplayBeforeAnyNewPaidGeneration() = isolated { context, prefs ->
        assertTrue(prefs.edit().putInt("generation:scope", 3).commit())
        val store = SentenceAudioRevisionStore(context)
        assertEquals(3, store.request("scope", true))
        store.delivered("scope", 3)
        assertEquals(4, store.request("scope", true))
    }
    @Test fun legacyBooleanPreservesFirstRetryAndCorruptMetadataFailsClosed() = isolated { context, prefs ->
        assertTrue(prefs.edit().putBoolean("scope", true).commit())
        val store = SentenceAudioRevisionStore(context)
        assertEquals(1, store.request("scope", true))
        assertTrue(prefs.edit().putInt("generation:scope", -1).commit())
        assertTrue(runCatching { store.request("scope", true) }.isFailure)
        assertEquals(-1, prefs.getInt("generation:scope", 0))
    }
}
