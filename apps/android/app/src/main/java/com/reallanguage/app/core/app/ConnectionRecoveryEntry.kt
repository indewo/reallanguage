package com.reallanguage.app.core.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlImmersiveDialogWindow
import com.reallanguage.app.core.network.ConnectionIncident
import com.reallanguage.app.feature.error.presentation.ConnectionErrorAction
import com.reallanguage.app.feature.error.presentation.ConnectionErrorEffect
import com.reallanguage.app.feature.error.presentation.ConnectionErrorOverlay
import com.reallanguage.app.feature.error.presentation.ConnectionErrorViewModel

/** Keeps the live page mounted, but conceals its content until this incident is verified.
 * Returning delegates to the page's existing back handler, including its confirmation.
 * No business operation is automatically submitted again. */
@Composable
internal fun ConnectionRecoveryEntry(
    graph: ProductionAppGraph,
    incident: ConnectionIncident,
    route: Any,
    onFlowReturn: () -> Unit,
    onLoginRequired: () -> Unit,
    onInitialSettingsRequired: () -> Unit = {},
) {
    var returnGeneration by remember(incident) { mutableStateOf(0) }
    val owner = rememberEntryViewModelStoreOwner(listOf(incident, route, returnGeneration))
    val recovery: ConnectionErrorViewModel = viewModel(
        viewModelStoreOwner = owner,
        factory = graph.connectionRecoveryFactory(incident, route.javaClass.simpleName),
    )
    val state by recovery.uiState.collectAsState()
    val currentReturn by rememberUpdatedState(onFlowReturn)
    val currentLogin by rememberUpdatedState(onLoginRequired)
    val currentSetup by rememberUpdatedState(onInitialSettingsRequired)
    LaunchedEffect(recovery) {
        recovery.effects.collect { effect ->
            when (effect) {
                is ConnectionErrorEffect.Restore -> Unit // The gate removes the cover, not the page.
                is ConnectionErrorEffect.ReturnUsingCurrentFlowRule -> {
                    currentReturn()
                    returnGeneration++
                }
                ConnectionErrorEffect.LoginRequired -> if (recovery.canNavigateToLogin()) {
                    currentLogin()
                    graph.connectionGate.acceptVerifiedSignedOut(incident, recovery::canNavigateToLogin)
                }
                ConnectionErrorEffect.InitialSettingsRequired -> if (graph.connectionGate.isCurrent(incident)) {
                    currentSetup()
                    graph.connectionGate.restoreVerified(incident)
                }
            }
        }
    }
    Dialog(
        onDismissRequest = { recovery.onAction(ConnectionErrorAction.ReturnRequested) },
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        RlImmersiveDialogWindow()
        // A dim layer alone would expose cached private learning content while offline.
        RlPaperBackground {
            ConnectionErrorOverlay(state, recovery::onAction, handleSystemBack = false)
        }
    }
}
