package com.reallanguage.app.testing

import android.content.Context
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.AndroidContentFixRepository
import com.reallanguage.app.feature.formation.data.AesGcmFixedSourcePayloadCodec
import com.reallanguage.app.feature.formation.data.FormationPayloadKeys
import com.reallanguage.app.feature.formation.domain.KnowledgeExampleGenerationRepository
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.safety.domain.*

/** Real JCE encryption, isolated in this test process; device Keystore has dedicated namespace tests. */
fun explicitSourceFixRepository(context: Context, runtime: SessionRuntime,
    knowledgeExamples: KnowledgeExampleGenerationRepository? = null): AndroidContentFixRepository =
    AndroidContentFixRepository(context, runtime, knowledgeExamples = knowledgeExamples,
        sourceCipher = AesGcmFixedSourcePayloadCodec(ExplicitSourceTestKeys))

private object ExplicitSourceTestKeys : FormationPayloadKeys {
    private val keys = mutableMapOf<String, SecretKey>()
    @Synchronized override fun existing(accountSubjectHash: String, keyVersion: Int) = keys[accountSubjectHash]
    @Synchronized override fun createIfAbsent(accountSubjectHash: String, keyVersion: Int) = keys.getOrPut(accountSubjectHash) {
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
    @Synchronized override fun deleteAccount(accountSubjectHash: String) { keys.remove(accountSubjectHash) }
}

/** Test-only original-source fixture. The caller supplies the original text and ALL source units. */
fun ContentFixRequest.withExplicitSource(
    originalText: String,
    originalUnits: List<FixedSourceUnit>,
    sourceEntryId: String = "fixture:$formationFlowId",
): ContentFixRequest {
    val type = when (sourceType) { "QUICK_RECORDING" -> SourceType.RECORDING; else -> SourceType.valueOf(sourceType) }
    return copy(sourceProjection = FixedSourceProjection(formationFlowId, sourceEntryId, 0,
        ConfirmedSource(originalText, type, SourceContentShape.ARTICLE), sourceTitle, originalUnits,
        chapters.flatMap { it.sentences }.filter { it.isFocus }.map { it.unitId }, selectedCandidateIds, selectedTargetIds,
        if (type == SourceType.DIARY) FixedSourceOrigin.Diary else FixedSourceOrigin.Unavailable()))
}
