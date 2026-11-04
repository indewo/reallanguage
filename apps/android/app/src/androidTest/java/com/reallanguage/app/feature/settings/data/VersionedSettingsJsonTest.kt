package com.reallanguage.app.feature.settings.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.settings.domain.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VersionedSettingsJsonTest {
    private val codec = JsonSubsequentSettingsWireCodec()
    private val original = InitialSettingsDraft(3, false, BasicVocabulary.CET_6,
        setOf(PurposeVocabulary.GRE), "Clear", true, false, 3)
    private fun body(settings: InitialSettingsDraft, version: Long) = JSONObject()
        .put("settings", JSONObject(codec.request(settings))).put("rowVersion", version).toString()

    @Test fun actualJsonRequestAndReadBackCarryEverySettingAndVersion() = runTest {
        val proposed = original.copy(formalityLevel = 5, slangEnabled = true, basicVocabulary = BasicVocabulary.CET_4,
            purposeVocabularies = setOf(PurposeVocabulary.IELTS_TOEFL), styleReference = "Brief examples",
            voiceEnabled = false, autoPlayEnabled = false, speechSpeedLevel = 4)
        val requests = mutableListOf<HttpRequest>()
        val replies = ArrayDeque(listOf(body(original, 1), """{"status":"SAVED","rowVersion":2}""", body(proposed, 2)))
        val repo = HttpSubsequentSettingsRepository(HttpTransport {
            requests += it; HttpResponse(200, replies.removeFirst())
        }, codec, SessionRuntime().apply { update("fixture-token", "fixture-owner") })
        assertEquals(SubsequentSettingsLoadResult.Ready(original), repo.load())
        assertEquals(SubsequentSettingsSaveResult.Saved, repo.save(proposed, "save-1"))
        val put = JSONObject(requests[1].body)
        assertEquals(1L, put.getLong("expectedRowVersion"))
        assertEquals(JSONObject(codec.request(proposed)).toString(), put.getJSONObject("settings").toString())
        assertEquals(proposed, codec.current(body(proposed, 2)))
        assertEquals(2L, codec.rowVersion(body(proposed, 2)))
    }
    @Test fun oldCompletedStatusOrCoercedAndPartialJsonNeverMeansSaved() {
        assertNull(codec.updatedVersion("""{"status":"COMPLETED","rowVersion":2}"""))
        for (value in listOf("\"2\"", "2.5", "0", "-1", "null", "9007199254740992")) {
            assertNull(codec.updatedVersion("""{"status":"SAVED","rowVersion":$value}"""))
        }
        assertNull(codec.current(codec.request(original)))
        val raw = JSONObject(body(original, 1))
        raw.getJSONObject("settings").put("slangEnabled", "false")
        assertNull(codec.current(raw.toString()))
        raw.getJSONObject("settings").put("slangEnabled", false).put("formalityLevel", 3.5)
        assertNull(codec.current(raw.toString()))
        raw.getJSONObject("settings").put("formalityLevel", 3).put("voiceEnabled", false).put("autoPlayEnabled", true)
        assertNull(codec.current(raw.toString()))
    }
    @Test fun wrongJsonSavedSnapshotDoesNotAcknowledgeTheRequestedValues() = runTest {
        val replies = ArrayDeque(listOf(body(original, 1), """{"status":"SAVED","rowVersion":2}""", body(original, 2)))
        val repo = HttpSubsequentSettingsRepository(HttpTransport { HttpResponse(200, replies.removeFirst()) },
            codec, SessionRuntime().apply { update("fixture-token", "fixture-owner") })
        repo.load()
        assertEquals(SubsequentSettingsSaveResult.Failed, repo.save(original.copy(speechSpeedLevel = 4), "save-1"))
    }
    @Test fun onlyTheExactVersionConflictWithAValidVersionIsClassifiedAsConflict() {
        assertTrue(codec.versionConflict("""{"error":{"code":"SETTINGS_VERSION_CONFLICT","details":{"rowVersion":2}}}"""))
        assertFalse(codec.versionConflict("""{"error":{"code":"OTHER_CONFLICT","details":{"rowVersion":2}}}"""))
        assertFalse(codec.versionConflict("""{"error":{"code":"SETTINGS_VERSION_CONFLICT","details":{"rowVersion":"2"}}}"""))
        assertFalse(codec.versionConflict("""{"error":{"code":"SETTINGS_VERSION_CONFLICT"}}"""))
    }
}
