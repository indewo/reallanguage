package com.reallanguage.app.feature.error.data

import com.reallanguage.app.core.navigation.AppStartupRepository
import com.reallanguage.app.core.navigation.AppStartupResult
import com.reallanguage.app.core.network.ConnectionGuardedHttpTransport
import com.reallanguage.app.core.network.ConnectionIncident
import com.reallanguage.app.feature.error.domain.ConnectionRecoveryRepository
import com.reallanguage.app.feature.error.domain.ConnectionRecoveryResult
import kotlinx.coroutines.CancellationException

class AccountConnectionRecoveryRepository(
    private val incident: ConnectionIncident,
    private val gate: ConnectionGuardedHttpTransport,
    private val startup: AppStartupRepository,
    private val serviceReachable: suspend () -> Boolean,
    private val completedSignOutIsCurrent: () -> Boolean = { false },
) : ConnectionRecoveryRepository {
    override fun canNavigateToLogin(): Boolean = gate.isCurrent(incident) ||
        (gate.hasIncident(incident) && completedSignOutIsCurrent())

    override suspend fun verifyNetworkAndAccount(): ConnectionRecoveryResult {
        if (!gate.isCurrent(incident)) return ConnectionRecoveryResult.Unavailable
        return try {
            if (!serviceReachable() || !gate.isCurrent(incident)) return ConnectionRecoveryResult.Unavailable
            val result = startup.resolve()
            if (result == AppStartupResult.SignedOut && incident.owner.accountId == null && gate.isCurrent(incident)) {
                return if (gate.restoreVerified(incident)) ConnectionRecoveryResult.Restored else ConnectionRecoveryResult.Unavailable
            }
            if (result == AppStartupResult.SignedOut && canNavigateToLogin()) return ConnectionRecoveryResult.LoginRequired
            if (!gate.isCurrent(incident)) return ConnectionRecoveryResult.Unavailable
            when (result) {
                AppStartupResult.SignedOut -> ConnectionRecoveryResult.LoginRequired
                is AppStartupResult.SignedIn -> when {
                    !result.settingsCompleted -> ConnectionRecoveryResult.InitialSettingsRequired
                    gate.restoreVerified(incident) -> ConnectionRecoveryResult.Restored
                    else -> ConnectionRecoveryResult.Unavailable
                }
                AppStartupResult.ConnectionUnavailable -> ConnectionRecoveryResult.Unavailable
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ConnectionRecoveryResult.Unavailable
        }
    }
}
