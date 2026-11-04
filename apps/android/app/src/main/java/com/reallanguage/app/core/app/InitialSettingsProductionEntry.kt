package com.reallanguage.app.core.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reallanguage.app.feature.settings.presentation.InitialSettingsAction
import com.reallanguage.app.feature.settings.presentation.InitialSettingsEffect
import com.reallanguage.app.feature.settings.presentation.InitialSettingsScreen
import com.reallanguage.app.feature.settings.presentation.InitialSettingsStep
import com.reallanguage.app.feature.settings.presentation.InitialSettingsViewModel

/** One in-memory draft per authenticated session. A token refresh does not replace the entry. */
@Composable
internal fun InitialSettingsProductionEntry(
    graph: ProductionAppGraph,
    onCompleted: () -> Unit,
    onLoginRequired: () -> Unit,
) {
    val revision by graph.connectionSessionRevision.collectAsState()
    val identity = remember(graph, revision) { graph.currentSessionOwner() }
    val owner = rememberEntryViewModelStoreOwner(identity)
    val model: InitialSettingsViewModel = viewModel(viewModelStoreOwner = owner, factory = graph.initialSettingsFactory)
    val state by model.uiState.collectAsState()
    val incident by graph.connectionGate.incident.collectAsState()
    val completed by rememberUpdatedState(onCompleted)
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(model) {
        model.effects.collect { effect ->
            if (effect == InitialSettingsEffect.OpenHome && graph.isSessionOwnerCurrent(identity)) completed()
        }
    }
    LaunchedEffect(incident) {
        if (incident != null) { focus.clearFocus(force = true); keyboard?.hide() }
    }
    BackHandler(enabled = state.step != InitialSettingsStep.EXPRESSION_BASICS) {
        if (!state.submitting) model.onAction(InitialSettingsAction.BackRequested)
    }
    Box(Modifier.fillMaxSize().then(if (incident != null || identity.accountId == null) {
        Modifier.graphicsLayer { alpha = 0f }.clearAndSetSemantics {}
    } else Modifier)) {
        InitialSettingsScreen(state, model::onAction)
    }
    incident?.let { current ->
        // Expiry clears the session before the verified sign-out effect is delivered. Keep
        // that recovery request alive even though the private setup draft resets meanwhile.
        val recoveryRoute = remember(current) { "initial-settings:${current.owner.epoch}:${state.step.name}" }
        ConnectionRecoveryEntry(
            graph = graph,
            incident = current,
            route = recoveryRoute,
            onFlowReturn = { backDispatcher?.onBackPressed() },
            onLoginRequired = onLoginRequired,
            // Already in the same session's setup. The cover alone is removed.
            onInitialSettingsRequired = {},
        )
    }
}
