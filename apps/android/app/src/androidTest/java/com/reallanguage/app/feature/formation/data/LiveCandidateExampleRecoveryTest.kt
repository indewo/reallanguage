package com.reallanguage.app.feature.formation.data

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.UrlConnectionSessionTransport
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.safety.domain.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in real HTTP/provider sample against check-live-formation.mts --serve-android
 * --one-provider-call. Two client HTTP requests, at most ONE server provider attempt.
 * No main application database, existing account, fixed knowledge examples or UI routing.
 */
class LiveCandidateExampleRecoveryTest {
    @Test fun oneLiveExtractionSurvivesEncryptedRecreationAndSameCommandReplay() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCandidateExample") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runId = UUID.randomUUID().toString()
        val namespace = "reallanguage.formation.payload.test.${runId.replace("-", "")}"
        val directory = File(context.cacheDir, "live-candidate-example-$runId")
        val evidenceFile = File(context.filesDir, "live-candidate-example-$runId-result.json")
        val sessionFile = File(context.filesDir, "live-formation-session.json")
        val runtime = SessionRuntime()
        val httpStatuses = mutableListOf<Int>()
        val evidence = JSONObject()
            .put("sampleKind", "ISOLATED_CANDIDATE_EXAMPLE")
            .put("sourceTextEn", SOURCE_EN).put("sourceTextZh", SOURCE_ZH)
            .put("configuredProviderAttemptLimit", 1)
            // The true provider count belongs to the server's bounded-provider log.
            .put("actualProviderAttempts", JSONObject.NULL)
            .put("serverProviderLogRequired", true)
            .put("fullUserJourneyAccepted", false)
            .put("semanticQualityAccepted", false)
            .put("targets", JSONArray())
        var stage = "consume-isolated-session"
        var accountHash: String? = null
        var failureClass: String? = null
        var httpRequests = 0
        var keyRemoved = false
        var directoryRemoved = false
        var sessionConsumed = false
        try {
            check(sessionFile.isFile && sessionFile.length() in 1..16_384)
            check(sessionFile.canonicalFile.parentFile == context.filesDir.canonicalFile)
            val sessionBytes = sessionFile.readBytes()
            try {
                // Consume before parsing or making any request. Never expose this file in evidence.
                check(sessionFile.delete() && !sessionFile.exists())
                sessionConsumed = true
                val secret = JSONObject(String(sessionBytes, Charsets.UTF_8))
                val token = (secret.get("accessToken") as? String)?.takeIf(String::isNotBlank)
                    ?: error("Isolated session unavailable")
                val account = (secret.get("accountId") as? String)?.takeIf(String::isNotBlank)
                    ?: error("Isolated account unavailable")
                runtime.update(token, account)
                accountHash = MessageDigest.getInstance("SHA-256").digest(account.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            } finally { sessionBytes.fill(0) }
            val owner = requireNotNull(runtime.accountId)
            val ownerEpoch = runtime.accountEpoch
            val hash = requireNotNull(accountHash)
            fun active() = runtime.accountId == owner && runtime.accountEpoch == ownerEpoch && runtime.accessToken != null
            fun recovery() = EncryptedFormationRecoveryRepository(hash, FormationRecoveryFileStore(
                directory, FormationPayloadCipher(AndroidFormationPayloadKeys(namespace)),
                canAccess = { it == hash && active() },
            ))
            val sentence = EnglishSentenceDraft("u1", SOURCE_ZH, true, listOf(EnglishCandidate("e1", SOURCE_EN)))
            val chapters = listOf(EnglishChapterDraft("c1", listOf(sentence)))
            val selectedCandidates = mapOf("u1" to "e1")
            val commandId = UUID.randomUUID().toString()
            val delegate = UrlConnectionSessionTransport("http://10.0.2.2:3001", true)
            val transport = HttpTransport { request ->
                check(request.method == "POST" && request.path == "/api/v1/content-formation/learning-targets")
                check(httpRequests < 2)
                val requestBody = JSONObject(request.body)
                check(requestBody.getString("commandId") == commandId)
                val actualChapters = requestBody.getJSONArray("chapters")
                check(actualChapters.length() == 1)
                val actualSentences = actualChapters.getJSONObject(0).getJSONArray("sentences")
                check(actualSentences.length() == 1 && actualSentences.getJSONObject(0).getString("textEn") == SOURCE_EN)
                httpRequests++
                delegate.execute(request).also { response ->
                    httpStatuses += response.statusCode
                    if (httpRequests == 1 && response.statusCode == 200) {
                        // Whitelist only the neutral fixture's generated content, never headers/session/raw errors.
                        val raw = JSONObject(response.body).getJSONArray("targets")
                        evidence.put("deliveredTargets", JSONArray(List(raw.length()) { index ->
                            contentEvidence(raw.getJSONObject(index))
                        }))
                    }
                }
            }
            stage = "real-extraction-and-http-decode"
            val first = HttpLearningTargetRepository(transport, runtime, chapters, selectedCandidates, commandId).extract()
            assertTrue("Live target delivery unavailable", first is LearningTargetExtractionResult.Extracted)
            val extracted = first as LearningTargetExtractionResult.Extracted
            assertTrue("Useful representative target omitted", extracted.targets.isNotEmpty())
            assertTrue("Representative collocation omitted", extracted.targets.any { target ->
                target.type != LearningTargetType.WORD && target.sourceEvidence?.surfaceForm?.contains("worked up the courage") == true
            })
            extracted.targets.forEach { target ->
                assertEquals("u1", target.unitId)
                val sourceEvidence = requireNotNull(target.sourceEvidence)
                assertTrue(sourceEvidence.isValidForTarget(SOURCE_EN, target.display, target.type))
                assertEquals(listOf(sourceEvidence.surfaceForm), sourceEvidence.allowedForms)
                val example = requireNotNull(target.candidateExample)
                assertTrue(example.textEn.isNotBlank() && example.textZh.isNotBlank())
            }
            evidence.put("targets", JSONArray(extracted.targets.map { target ->
                JSONObject().put("type", target.type.name).put("display", target.display)
                    .put("explanationZh", target.explanationZh).put("unitId", target.unitId)
                    .put("sourceEvidence", requireNotNull(target.sourceEvidence).toJson())
                    .put("candidateExample", JSONObject().put("textEn", target.candidateExample!!.textEn)
                        .put("textZh", target.candidateExample.textZh))
            }))
            val snapshot = FormationRecoverySnapshot(
                sourceEntryId = "live-candidate-$runId", sourceRevision = 0,
                phase = FormationRecoveryPhase.LEARNING_TARGET_SELECTION,
                source = ConfirmedSource(SOURCE_ZH, SourceType.DIARY, SourceContentShape.ARTICLE),
                sourceTitle = "隔离候选例句验收", origin = FormationSourceOrigin.Diary,
                units = listOf(SemanticUnit("u1", 0, SOURCE_ZH)), selectedUnitIds = listOf("u1"),
                chapters = listOf(ChapterDraft("c1", listOf("u1"))), englishChapters = chapters,
                selectedCandidateIds = selectedCandidates, targets = extracted.targets,
                queryableExpressions = extracted.queryableExpressions,
                selectedTargetIds = extracted.targets.mapTo(mutableSetOf()) { it.targetId },
                targetSelectionEntryId = "live-candidate-selection-$runId",
            )
            stage = "encrypted-save-and-new-repository-readback"
            assertEquals(FormationRecoverySave.Saved(0), recovery().save(snapshot, null))
            val restored = recovery().load(snapshot.sourceEntryId)
            assertEquals(FormationRecoveryLoad.Loaded(0, snapshot), restored)
            val restoredSnapshot = (restored as FormationRecoveryLoad.Loaded).snapshot
            evidence.put("recreatedRepositoryReadBackEqual", true)
            val plaintextNeedles = listOf(SOURCE_ZH, SOURCE_EN, snapshot.sourceTitle) + extracted.targets.flatMap {
                listOf(requireNotNull(it.candidateExample).textEn, it.candidateExample.textZh)
            }
            directory.walkTopDown().filter(File::isFile).forEach { file ->
                val raw = file.readBytes()
                try { assertFalse("Temporary store exposed source or example plaintext", plaintextNeedles.any { String(raw, Charsets.UTF_8).contains(it) }) }
                finally { raw.fill(0) }
            }
            evidence.put("plaintextAbsentFromEncryptedStore", true)
            stage = "same-command-http-replay"
            val replayed = HttpLearningTargetRepository(transport, runtime, restoredSnapshot.englishChapters,
                restoredSnapshot.selectedCandidateIds, commandId,
                initialQueryableExpressions = restoredSnapshot.queryableExpressions).extract()
            assertEquals(extracted, replayed)
            assertEquals(FormationRecoveryLoad.Loaded(0, snapshot), recovery().load(snapshot.sourceEntryId))
            assertEquals(listOf(200, 200), httpStatuses)
            assertEquals(2, httpRequests)
            evidence.put("sameCommandReplayedEqual", true).put("savedRevisionUnchangedAfterReplay", true)
            stage = "completed"
        } catch (failure: Throwable) {
            failureClass = failure.javaClass.simpleName
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                    check(directory.name == "live-candidate-example-$runId")
                    check(!directory.exists() || directory.deleteRecursively())
                    directoryRemoved = !directory.exists()
                    accountHash?.let { hash ->
                        val keys = AndroidFormationPayloadKeys(namespace)
                        keys.deleteAccount(hash)
                        keyRemoved = keys.existing(hash, 1) == null
                        check(keyRemoved)
                    }
                } catch (failure: Throwable) {
                    if (failureClass == null) { stage = "scoped-cleanup"; failureClass = failure.javaClass.simpleName }
                } finally { runtime.clear() }
                evidence.put("status", if (failureClass == null) "PASSED" else "FAILED")
                    .put("stage", stage).put("failureClass", failureClass ?: JSONObject.NULL)
                    .put("clientHttpRequests", httpRequests).put("httpStatuses", JSONArray(httpStatuses))
                    .put("isolatedSessionConsumed", sessionConsumed)
                    .put("temporaryEncryptedFilesRemoved", directoryRemoved).put("temporaryKeystoreAliasRemoved", keyRemoved)
                check(!evidenceFile.exists())
                evidenceFile.writeText(evidence.toString())
                // Only a generated non-secret artifact filename is printed.
                println("Candidate-example evidence: ${evidenceFile.name}")
            }
        }
        if (failureClass != null) throw AssertionError("Isolated candidate example check failed at $stage ($failureClass); private exception details omitted")
    }

    private fun contentEvidence(target: JSONObject): JSONObject {
        val result = JSONObject()
        for (key in listOf("targetId", "unitId", "display", "explanationZh", "type")) {
            (target.opt(key) as? String)?.let { result.put(key, it) }
        }
        val example = target.opt("candidateExample")
        result.put("candidateExamplePresent", target.has("candidateExample"))
        if (example is JSONObject) {
            result.put("candidateExample", JSONObject().apply {
                for (key in listOf("textEn", "textZh")) (example.opt(key) as? String)?.let { put(key, it) }
            })
        } else result.put("candidateExample", JSONObject.NULL)
        return result
    }

    private companion object {
        const val SOURCE_EN = "Yesterday I worked up the courage to ask for feedback."
        const val SOURCE_ZH = "昨天我鼓起勇气请求反馈。"
    }
}
