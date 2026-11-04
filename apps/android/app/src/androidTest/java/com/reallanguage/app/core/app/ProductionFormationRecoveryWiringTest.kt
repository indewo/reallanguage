package com.reallanguage.app.core.app

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.AndroidFormationPayloadKeys
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.home.domain.ResumeLearningKind
import com.reallanguage.app.feature.home.presentation.IncompleteLearningViewModel
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class ProductionFormationRecoveryWiringTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val owner = "recovery-graph-$id"
    private val directory = File(base.cacheDir, "recovery-graph-$id")
    private val isolated = IsolatedLearningDatabaseContext(base, "recovery-graph")
    private val context = object : ContextWrapper(isolated) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
    }
    private val session = SessionRuntime().apply { update("isolated-token", owner) }
    private val transport = HttpTransport { error("Restoring accepted local progress must not call a remote service") }
    private fun graph() = ProductionAppGraph(context, session, transport)
    private val source = HomeDiaryRoute.SemanticSplit("需要保留的完整日记内容。", entryId = "accepted-source")

    @Test fun actualFactoriesExposeThePersistedPreFixSourceAndReloadItWithoutAnAiRequest() = isolated {
        assertNotNull(graph().prepareFormation(source))
        val reopened = graph()
        assertEquals(source.text, reopened.readFormation(source.entryId)?.source?.text)
        withContext(Dispatchers.Main) {
            val vm = reopened.incompleteLearningFactory().create(IncompleteLearningViewModel::class.java)
            val store = ViewModelStore().apply { put("test", vm) }
            try {
                val state = withTimeout(5000) { vm.uiState.first { !it.loading } }
                assertFalse(state.loadFailed)
                assertEquals(listOf(source.entryId), state.entries.map { it.learningId })
                assertEquals(ResumeLearningKind.FORMATION, state.entries.single().kind)
                assertNull(state.entries.single().chapterNumber)
            } finally { store.clear() }
        }
        assertTrue(reopened.hasIncompleteLearning())
    }

    @Test fun resumeUsesTheSavedPhaseAndSentenceInsteadOfLookingForANonexistentFormalChapter() = isolated {
        val units = listOf(SemanticUnit("a", 0, "喝茶。"), SemanticUnit("b", 1, "散步。"))
        val chapters = listOf(EnglishChapterDraft("c", units.map { EnglishSentenceDraft(it.unitId, it.textZh, true,
            listOf(EnglishCandidate("e-${it.unitId}", if (it.unitId == "a") "I drink tea." else "I walk."))) }))
        val selected = HomeDiaryRoute.LearningTargetSelection("喝茶。散步。", units, chapters,
            mapOf("a" to "e-a", "b" to "e-b"), emptyList(), emptyList(), entryId = "target-page", sourceEntryId = "source-two")
        assertNotNull(graph().prepareFormation(selected, 1))
        val restored = graph().resolveResumeRoute(selected.sourceEntryId, "FORMATION:LEARNING_TARGET_SELECTION", null)
        assertTrue(restored is HomeDiaryRoute.LearningTargetSelection)
        assertEquals("target-page", (restored as HomeDiaryRoute.LearningTargetSelection).entryId)
        // Exact saved position remains available to the production factory, not inferred from a chapter number.
        assertEquals(1, graph().readFormation(selected.sourceEntryId)?.targetSentenceIndex)
    }

    private fun isolated(action: suspend () -> Unit) = runBlocking {
        try { action() } finally {
            withContext(Dispatchers.IO) {
                val hash = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) }
                AndroidFormationPayloadKeys().deleteAccount(hash)
                check(directory.name == "recovery-graph-$id"); directory.deleteRecursively()
                context.deleteDatabase("real-language-learning.db")
            }
        }
    }
}
