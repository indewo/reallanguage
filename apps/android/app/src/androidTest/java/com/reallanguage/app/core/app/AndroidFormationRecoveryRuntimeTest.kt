package com.reallanguage.app.core.app

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.AndroidFormationPayloadKeys
import com.reallanguage.app.feature.formation.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class AndroidFormationRecoveryRuntimeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString().replace("-", "")
    private val directory = File(context.cacheDir, "formation-runtime-$id")
    private val keys = AndroidFormationPayloadKeys("reallanguage.formation.payload.test.$id")
    private val session = SessionRuntime().apply { update("isolated-token", "owner-$id") }
    private val source = HomeDiaryRoute.SemanticSplit("已确认且尚未固定的完整中文。", entryId = "accepted-source")

    @Test fun reopeningTheAccountRuntimeReadsTheSavedSourceAndListsItsRealPreFixStage() = isolated {
        val saved = runtime().journal.prepare(source)
        assertNotNull("Production composition must actually use encrypted durable storage", saved)
        val reopened = runtime()
        assertEquals(saved, reopened.journal.read(source.entryId))
        val items = (reopened.repository.list() as FormationRecoveryList.Listed).items
        assertEquals(listOf(source.entryId), items.map { it.sourceEntryId })
        assertEquals(FormationRecoveryPhase.SEMANTIC_SPLIT, items.single().phase)
        assertTrue(directory.walkTopDown().filter { it.isFile }.none { file ->
            file.readBytes().toString(Charsets.UTF_8).contains(source.text)
        })
    }

    @Test fun tokenRefreshKeepsTheCurrentPageUsableButLogoutInvalidatesItsOldRuntime() = isolated {
        val current = runtime()
        val saved = current.journal.prepare(source)
        assertNotNull(saved)
        session.update("isolated-refresh", "owner-$id")
        assertEquals(saved, current.journal.read(source.entryId))
        session.clear(); session.update("isolated-token-2", "other-$id")
        assertNull(current.journal.read(source.entryId))
        assertEquals(FormationRecoveryList.Unavailable, current.repository.list())
        assertEquals(emptyList<FormationRecoveryItem>(), (runtime().repository.list() as FormationRecoveryList.Listed).items)
        session.clear(); session.update("isolated-return", "owner-$id")
        assertNull(current.journal.read(source.entryId))
        assertEquals(saved, runtime().journal.read(source.entryId))
    }

    private fun runtime() = AndroidFormationRecoveryRuntime(context, session, directory, keys)
    private fun isolated(block: suspend () -> Unit) = runBlocking {
        try { block() } finally {
            withContext(Dispatchers.IO) {
                listOf("owner-$id", "other-$id").forEach { owner ->
                    keys.deleteAccount(MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) })
                }
                check(directory.name == "formation-runtime-$id")
                directory.deleteRecursively()
            }
        }
    }
}
