package com.reallanguage.app.feature.formation.data

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.safety.domain.*
import com.reallanguage.app.feature.scene.domain.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Uses the production JSON, encrypted file store, and Android Keystore across new instances. */
class SceneFormationRecoveryPersistenceTest {
    @Test fun answeredScenesFromEveryEntryAndFormatPersistExactlyAcrossRepositoryRecreation() = withStore { fixture ->
        for ((index, scene) in sceneCases().withIndex()) {
            val repository = fixture.repository()
            assertEquals("Save case $index", FormationRecoverySave.Saved(0), repository.save(scene, null))
            assertEquals("Reopen case $index", FormationRecoveryLoad.Loaded(0, scene),
                fixture.repository().load(scene.sourceEntryId))
            assertEquals("Idempotent replay case $index", FormationRecoverySave.Saved(0),
                fixture.repository().save(scene, null))
        }
        val listed = fixture.repository().list() as FormationRecoveryList.Listed
        assertEquals(6, listed.items.size)
        assertTrue(listed.items.all { it.sourceType == SourceType.AI_SCENE })
        assertEquals(0, fixture.directory.walkTopDown().count { it.name.contains(".unreadable") })
        fixture.directory.walkTopDown().filter { it.isFile }.forEach { file ->
            val bytes = String(file.readBytes(), Charsets.UTF_8)
            sceneCases().forEach { scene ->
                assertFalse(bytes.contains(scene.source.text))
                assertFalse(bytes.contains(scene.sourceTitle))
                assertFalse(bytes.contains("你现在在哪里，对方是谁？"))
            }
        }
    }

    @Test fun legacySceneWithoutQuestionTextUpgradesInPlaceWithoutQuarantiningValidProgress() = withStore { fixture ->
        val codec = FormationRecoveryPayloadCodec()
        for ((index, current) in sceneCases().withIndex()) {
            // This reproduces the old production encoder, which silently omitted questionZh.
            val json = JSONObject(String(codec.encode(current), Charsets.UTF_8))
            val answers = json.getJSONObject("origin").getJSONObject("session").getJSONArray("answers")
            for (answer in 0 until answers.length()) answers.getJSONObject(answer).remove("questionZh")
            val legacyOrigin = current.origin as FormationSourceOrigin.Scene
            val legacy = current.copy(origin = legacyOrigin.copy(session = legacyOrigin.session.copy(
                answers = legacyOrigin.session.answers.map { it.copy(questionZh = null) },
            )))
            val owner = FormationPayloadOwner(ACCOUNT, current.sourceEntryId)
            assertEquals("Seed old encrypted case $index", FormationPayloadSave.Saved(0),
                fixture.store().compareAndSet(owner, null, 1, json.toString().toByteArray(Charsets.UTF_8)))
            assertEquals("Read old case $index", FormationRecoveryLoad.Loaded(0, legacy),
                fixture.repository().load(current.sourceEntryId))
            assertEquals("Upgrade case $index", FormationRecoverySave.Saved(1),
                fixture.repository().save(current, 0))
            assertEquals("Reopen upgraded case $index", FormationRecoveryLoad.Loaded(1, current),
                fixture.repository().load(current.sourceEntryId))
            assertEquals("Preserve revision on replay case $index", FormationRecoverySave.Saved(1),
                fixture.repository().save(current, 0))
        }
        assertEquals(6, (fixture.repository().list() as FormationRecoveryList.Listed).items.size)
        assertEquals("An old record missing an optional question remains valid; it must not be isolated",
            0, fixture.directory.walkTopDown().count { it.name.contains(".unreadable") })
    }

    private fun sceneCases(): List<FormationRecoverySnapshot> {
        val starts = listOf(
            SceneStart.DirectDescription("我手机没电，充电后给等我的朋友打微信，告诉他我会晚到。"),
            SceneStart.Template(PublicSceneTemplates.first { it.templateId == "planning" }),
            SceneStart.Recommendation(RecentSceneRecommendation("recent-late", "向朋友说明迟到", "", emptyList())),
        )
        return starts.flatMapIndexed { startIndex, start -> SceneContentType.entries.map { type ->
            val units = listOf(
                SceneDraftUnit("unit-1", if (type == SceneContentType.DIALOGUE) SceneSpeaker.USER else null,
                    "不好意思，我刚才手机没电了。", listOf("手机没电")),
                SceneDraftUnit("unit-2", if (type == SceneContentType.DIALOGUE) SceneSpeaker.OTHER else null,
                    "没事，你现在在哪里？", emptyList()),
                SceneDraftUnit("unit-3", if (type == SceneContentType.DIALOGUE) SceneSpeaker.USER else null,
                    "我在苏州街。", listOf("苏州街")),
            )
            val session = SceneQuestionSession(start, type,
                listOf(SceneAnswer("place", "在苏州街，给等我的朋友打微信", "你现在在哪里，对方是谁？"),
                    SceneAnswer("delay", "刚充上电，大约晚到十分钟", "手机恢复使用了吗，你预计晚到多久？")),
                listOf("手机没电", "苏州街", "刚充上电", "晚到十分钟"), emptyList(), "operation-$startIndex-${type.name}")
            val text = units.joinToString("\n") { unit ->
                if (type == SceneContentType.DIALOGUE)
                    "${if (unit.speaker == SceneSpeaker.USER) "用户" else "对方"}：${unit.textZh}"
                else unit.textZh
            }
            FormationRecoverySnapshot("scene-$startIndex-${type.name}", 0, FormationRecoveryPhase.SEMANTIC_SPLIT,
                ConfirmedSource(text, SourceType.AI_SCENE, SourceContentShape.valueOf(type.name)), "联系朋友说明迟到",
                FormationSourceOrigin.Scene(session, SceneGeneratedDraft("draft-$startIndex-${type.name}", type, units)))
        } }
    }

    private fun withStore(block: suspend (Fixture) -> Unit) = runBlocking {
        val id = UUID.randomUUID().toString().replace("-", "")
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "scene-recovery-test-$id")
        val fixture = Fixture(root, "reallanguage.formation.payload.test.$id")
        try { block(fixture) }
        finally {
            withContext(Dispatchers.IO) {
                check(!root.exists() || root.deleteRecursively())
                AndroidFormationPayloadKeys(fixture.keyNamespace).deleteAccount(ACCOUNT)
            }
        }
    }

    private class Fixture(val directory: File, val keyNamespace: String) {
        fun store() = FormationRecoveryFileStore(directory,
            FormationPayloadCipher(AndroidFormationPayloadKeys(keyNamespace)), { it == ACCOUNT })
        fun repository() = EncryptedFormationRecoveryRepository(ACCOUNT, store())
    }

    private companion object { const val ACCOUNT = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc" }
}
