package com.reallanguage.app.feature.formation.data

import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.safety.domain.*
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class FixedSourcePayloadIdentity(val accountSubjectHash: String, val formationFlowId: String, val sourceEntryId: String, val sourceRevision: Long)

interface FixedSourcePayloadCodec {
    fun encrypt(identity: FixedSourcePayloadIdentity, projection: FixedSourceProjection): ByteArray?
    fun decrypt(identity: FixedSourcePayloadIdentity, envelope: ByteArray): FixedSourceProjection?
}

class AesGcmFixedSourcePayloadCodec(private val keys: FormationPayloadKeys = AndroidFixedSourcePayloadKeys()) : FixedSourcePayloadCodec {
    private val cipher = FormationPayloadCipher(keys)
    override fun encrypt(identity: FixedSourcePayloadIdentity, projection: FixedSourceProjection): ByteArray? = sourceAttempt {
        require(projection.isValidFixedSource() && identity.matches(projection))
        val json = fields("version" to 1, "flowId" to projection.formationFlowId, "sourceEntryId" to projection.sourceEntryId,
            "sourceRevision" to projection.sourceRevision, "title" to projection.title,
            "source" to fields("text" to projection.source.text, "sourceType" to projection.source.sourceType.name,
                "contentShape" to projection.source.contentShape.name), "origin" to projection.origin.json(),
            "units" to JSONArray(projection.units.map { fields("unitId" to it.unitId, "order" to it.order, "textZh" to it.textZh) }),
            "selectedUnitIds" to JSONArray(projection.selectedUnitIds),
            "selectedCandidateIds" to JSONObject().apply { projection.selectedCandidateIds.toSortedMap().forEach { (id, selected) -> put(id, selected) } },
            "selectedTargetIds" to JSONArray(projection.selectedTargetIds.sorted())).toString()
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(json))
        val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
        try { cipher.encrypt(identity.envelopeIdentity(), bytes) } finally { bytes.fill(0) }
    }
    override fun decrypt(identity: FixedSourcePayloadIdentity, envelope: ByteArray): FixedSourceProjection? = sourceAttempt {
        val bytes = cipher.decrypt(identity.envelopeIdentity(), envelope) ?: return@sourceAttempt null
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            val parser = JSONTokener(text)
            val root = (parser.nextValue() as? JSONObject ?: error("Invalid fixed source")).checked(
                "version", "flowId", "sourceEntryId", "sourceRevision", "title", "source", "origin", "units",
                "selectedUnitIds", "selectedCandidateIds", "selectedTargetIds")
            require(parser.nextClean() == '\u0000' && root.integer("version") == 1L)
            val source = root.obj("source").checked("text", "sourceType", "contentShape")
            val units = root.array("units").let { values -> List(values.length()) { index ->
                val value = (values.get(index) as? JSONObject ?: error("Invalid fixed source unit")).checked("unitId", "order", "textZh")
                val order = value.integer("order").also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt()
                FixedSourceUnit(value.string("unitId"), order, value.string("textZh"))
            } }
            val targets = root.strings("selectedTargetIds").also { require(it.distinct().size == it.size) }
            FixedSourceProjection(root.string("flowId"), root.string("sourceEntryId"), root.integer("sourceRevision"),
                ConfirmedSource(source.string("text"), enumValueOf(source.string("sourceType")), enumValueOf(source.string("contentShape"))),
                root.string("title"), units, root.strings("selectedUnitIds"),
                root.obj("selectedCandidateIds").let { value -> value.keys().asSequence().associateWith { value.string(it) } },
                targets.toSet(), root.obj("origin").origin()).also { require(it.isValidFixedSource() && identity.matches(it)) }
        } finally { bytes.fill(0) }
    }
}

class AndroidFixedSourcePayloadKeys internal constructor(private val namespace: String = "reallanguage.fixed-source.payload",
    private val canUseAccount: (String) -> Boolean = com.reallanguage.app.core.session.DeletedAccountLocalAccess::allowsAccountHash,
) : FormationPayloadKeys {
    init { require(namespace == "reallanguage.fixed-source.payload" || namespace.matches(Regex("reallanguage\\.fixed-source\\.payload\\.test\\.[a-f0-9]{32}"))) }
    override fun existing(accountSubjectHash: String, keyVersion: Int): SecretKey? = synchronized(keyLock) {
        val alias = alias(accountSubjectHash, keyVersion)
        check(canUseAccount(accountSubjectHash)) { "Fixed source access is revoked" }
        val store = store()
        if (!store.containsAlias(alias)) null else requireNotNull(store.getKey(alias, null) as? SecretKey)
    }
    override fun createIfAbsent(accountSubjectHash: String, keyVersion: Int): SecretKey = synchronized(keyLock) {
        val alias = alias(accountSubjectHash, keyVersion)
        check(canUseAccount(accountSubjectHash)) { "Fixed source access is revoked" }
        val store = store()
        if (store.containsAlias(alias)) return@synchronized requireNotNull(store.getKey(alias, null) as? SecretKey)
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
    override fun deleteAccount(accountSubjectHash: String) = synchronized(keyLock) { store().deleteEntry(alias(accountSubjectHash, 1)) }
    private fun alias(accountSubjectHash: String, keyVersion: Int): String {
        require(validFormationAccountHash(accountSubjectHash) && keyVersion == 1)
        return "$namespace.$accountSubjectHash.v$keyVersion"
    }
    private fun store(): KeyStore {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Keystore work must run off the main thread" }
        return KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }
    private companion object { val keyLock = Any() }
}

internal fun fixedSourceAccountHash(accountId: String): String = sha256(accountId.toByteArray(Charsets.UTF_8))
private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun FixedSourcePayloadIdentity.matches(source: FixedSourceProjection) = formationFlowId == source.formationFlowId &&
    sourceEntryId == source.sourceEntryId && sourceRevision == source.sourceRevision
private fun FixedSourcePayloadIdentity.envelopeIdentity(): FormationPayloadIdentity {
    require(validFormationAccountHash(accountSubjectHash) && formationFlowId.isNotBlank() &&
        sourceEntryId.matches(Regex("[A-Za-z0-9][A-Za-z0-9:._-]{0,255}")) && sourceRevision >= 0)
    // Length framing prevents flow/source concatenation ambiguity. The purpose prefix is authenticated.
    val owner = "fixed-source:" + sha256("${formationFlowId.length}:$formationFlowId:${sourceEntryId.length}:$sourceEntryId".toByteArray(Charsets.UTF_8))
    return FormationPayloadIdentity(FormationPayloadOwner(accountSubjectHash, owner), sourceRevision, 1)
}
private inline fun <T> sourceAttempt(block: () -> T?): T? = try { block() }
catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { null }
private fun fields(vararg fields: Pair<String, Any?>) = JSONObject().apply { fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }
private fun JSONObject.checked(vararg fields: String) = apply { require(keys().asSequence().toSet() == fields.toSet()) }
private fun JSONObject.string(key: String) = get(key).let { require(it is String); it }
private fun JSONObject.nullableString(key: String): String? = get(key).let { if (it === JSONObject.NULL) null else { require(it is String); it } }
private fun JSONObject.integer(key: String): Long = get(key).let { require(it is Int || it is Long); (it as Number).toLong() }
private fun JSONObject.obj(key: String) = get(key) as? JSONObject ?: error("Invalid fixed source object")
private fun JSONObject.array(key: String) = get(key) as? JSONArray ?: error("Invalid fixed source array")
private fun JSONObject.strings(key: String) = array(key).let { values -> List(values.length()) { values.get(it).let { value -> require(value is String); value } } }
private fun FixedSourceOrigin.json(): JSONObject = when (this) {
    FixedSourceOrigin.Diary -> fields("kind" to "DIARY")
    is FixedSourceOrigin.Recording -> fields("kind" to "RECORDING", "recordingId" to recordingId, "transcriptId" to transcriptId)
    is FixedSourceOrigin.Scene -> fields("kind" to "SCENE", "operationId" to operationId, "draftId" to draftId)
    is FixedSourceOrigin.Unavailable -> fields("kind" to "UNAVAILABLE", "reviewRequestId" to reviewRequestId)
}
private fun JSONObject.origin(): FixedSourceOrigin = when (string("kind")) {
    "DIARY" -> { checked("kind"); FixedSourceOrigin.Diary }
    "RECORDING" -> { checked("kind", "recordingId", "transcriptId"); FixedSourceOrigin.Recording(string("recordingId"), string("transcriptId")) }
    "SCENE" -> { checked("kind", "operationId", "draftId"); FixedSourceOrigin.Scene(string("operationId"), string("draftId")) }
    "UNAVAILABLE" -> { checked("kind", "reviewRequestId"); FixedSourceOrigin.Unavailable(nullableString("reviewRequestId")) }
    else -> error("Invalid fixed source origin")
}
