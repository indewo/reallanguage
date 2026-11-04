package com.reallanguage.app.feature.recording.data

import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.recording.domain.RecordingRuntimeState
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

internal data class OwnedRecordingRuntimeState(
    val accountStorageKey: String?,
    val state: RecordingRuntimeState,
)

/** No independent coroutine scope: collection follows the owning screen's lifetime. */
internal class AccountRecordingRuntimeState(
    private val source: StateFlow<OwnedRecordingRuntimeState>,
    private val runtime: SessionRuntime,
    private val ownerAccountId: String? = runtime.accountId,
) : StateFlow<RecordingRuntimeState> {
    private val ownerKey = ownerAccountId?.let(::accountRecordingStorageKey)
    private val ownerEpoch = runtime.accountEpoch
    override val value: RecordingRuntimeState
        get() {
            if (ownerAccountId == null || runtime.accountId != ownerAccountId || runtime.accountEpoch != ownerEpoch || runtime.accessToken == null) return RecordingRuntimeState.Failed
            val current = source.value
            if (current.accountStorageKey == ownerKey) return current.state
            return if (current.state is RecordingRuntimeState.Recording) RecordingRuntimeState.Failed else RecordingRuntimeState.Starting
        }
    override val replayCache: List<RecordingRuntimeState> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<RecordingRuntimeState>): Nothing {
        combine(source, runtime.identityRevision) { _, _ -> value }
            .distinctUntilChanged().collect { collector.emit(it) }
        error("Recording state source unexpectedly completed")
    }
}
