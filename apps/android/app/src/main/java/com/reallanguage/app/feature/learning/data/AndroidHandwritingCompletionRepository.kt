package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.learning.domain.HandwritingCompletionRepository
import com.reallanguage.app.feature.learning.domain.HandwritingCompletionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidHandwritingCompletionRepository(
    private val runtime: SessionRuntime,
    private val chapterId: String,
    private val commandId: String,
    private val stepStore: LearningStepStore,
) : HandwritingCompletionRepository {
    private val ownerAccountId = runtime.accountId
    private val ownerAccountEpoch = runtime.accountEpoch
    private fun ownerIsActive() = ownerAccountId != null && runtime.accountId == ownerAccountId &&
        runtime.accountEpoch == ownerAccountEpoch && runtime.accessToken != null

    override suspend fun complete(
        sentenceId: String,
        confirmedCopyCount: Int,
    ): HandwritingCompletionResult {
        val accountId = ownerAccountId ?: return HandwritingCompletionResult.Failed
        if (!ownerIsActive() || confirmedCopyCount < 2) return HandwritingCompletionResult.Failed
        return if (
            withContext(Dispatchers.IO) { ownerIsActive() && stepStore.completeWhileAuthorized(
                accountId,
                chapterId,
                sentenceId,
                "HANDWRITING_COPY",
                commandId,
            ) { ownerIsActive() } } && ownerIsActive()
        ) {
            HandwritingCompletionResult.Saved
        } else {
            HandwritingCompletionResult.Failed
        }
    }
}
