package com.reallanguage.app.feature.formation.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.FixedSourceLoad
import com.reallanguage.app.feature.formation.domain.FixedSourceRepository
import com.reallanguage.app.feature.formation.domain.FixedSourceProjection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidFixedSourceRepository(
    private val context: Context,
    private val runtime: SessionRuntime,
    private val codec: FixedSourcePayloadCodec = AesGcmFixedSourcePayloadCodec(),
) : FixedSourceRepository {
    private val ownerAccountId = runtime.accountId
    private val ownerAccountEpoch = runtime.accountEpoch
    private fun canRead() = ownerAccountId != null && runtime.accountId == ownerAccountId && runtime.accessToken != null && runtime.accountEpoch == ownerAccountEpoch
    override suspend fun load(formationFlowId: String): FixedSourceLoad = withContext(Dispatchers.IO) {
        val account = ownerAccountId
        if (account == null || !canRead() || formationFlowId.isBlank()) return@withContext FixedSourceLoad.Unavailable
        try {
            val source = FormationDatabase(context.applicationContext).use { helper ->
                helper.readableDatabase.readFixedSource(account, formationFlowId, codec)
            }
            if (!canRead()) FixedSourceLoad.Unavailable else source?.let { FixedSourceLoad.Loaded(it) } ?: FixedSourceLoad.Missing
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { FixedSourceLoad.Unavailable }
    }
}

/** Missing legacy rows are distinguishable from present but unauthenticatable data. Never backfill. */
internal fun SQLiteDatabase.readFixedSource(account: String, flowId: String, codec: FixedSourcePayloadCodec): FixedSourceProjection? =
    rawQuery("select source_entry_id,source_revision,payload_version,envelope from fixed_source_payloads where account_id=? and flow_id=?",
        arrayOf(account, flowId)).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        check(!cursor.isNull(0) && !cursor.isNull(1) && !cursor.isNull(2) && !cursor.isNull(3) && cursor.getLong(2) == 1L)
        val identity = FixedSourcePayloadIdentity(fixedSourceAccountHash(account), flowId, cursor.getString(0), cursor.getLong(1))
        val envelope = cursor.getBlob(3)
        try { requireNotNull(codec.decrypt(identity, envelope)) { "Fixed source is unavailable" } }
        finally { envelope.fill(0) }
    }
