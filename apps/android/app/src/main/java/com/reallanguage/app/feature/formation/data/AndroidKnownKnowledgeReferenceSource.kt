package com.reallanguage.app.feature.formation.data

import android.content.Context
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.KnownKnowledgeCandidate
import com.reallanguage.app.feature.formation.domain.KnownKnowledgeReferenceSource
import com.reallanguage.app.feature.formation.domain.isValidKnowledgeRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidKnownKnowledgeReferenceSource(context: Context, private val runtime: SessionRuntime) : KnownKnowledgeReferenceSource {
    private val applicationContext = context.applicationContext
    private val owner = runtime.accountId
    private val ownerEpoch = runtime.accountEpoch
    override suspend fun read(refs: List<String>): List<KnownKnowledgeCandidate>? = withContext(Dispatchers.IO) {
        val account = owner ?: return@withContext null
        val revision = runtime.identityRevision.value
        fun active() = runtime.accountId == account && runtime.accessToken != null && runtime.accountEpoch == ownerEpoch && runtime.identityRevision.value == revision
        if (!active() || refs.size > 16 || refs.distinct().size != refs.size || !refs.all(::isValidKnowledgeRef)) return@withContext null
        if (refs.isEmpty()) return@withContext emptyList()
        try { FormationDatabase(applicationContext).use { helper ->
            val result = refs.map { helper.readableDatabase.readKnownCandidate(account, it) ?: return@withContext null }
            if (active()) result else null
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
}
