package com.reallanguage.app.feature.error.presentation

import com.reallanguage.app.feature.error.domain.ConnectionResumePoint

data class ConnectionErrorUiState(
    val resumePoint: ConnectionResumePoint,
    val overlayVisible: Boolean = true,
    val reconnecting: Boolean = false,
    val reconnectFailed: Boolean = false,
    val operationSubmitted: Boolean = false,
)

sealed interface ConnectionErrorAction {
    data object ReconnectRequested : ConnectionErrorAction
    data object ReturnRequested : ConnectionErrorAction
}

sealed interface ConnectionErrorEffect {
    data object LoginRequired : ConnectionErrorEffect
    data object InitialSettingsRequired : ConnectionErrorEffect
    data class Restore(val resumePoint: ConnectionResumePoint) : ConnectionErrorEffect
    data class ReturnUsingCurrentFlowRule(val pageId: String) : ConnectionErrorEffect
}
