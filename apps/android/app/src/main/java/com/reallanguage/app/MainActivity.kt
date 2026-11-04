package com.reallanguage.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import com.reallanguage.app.core.app.ProductionApp
import com.reallanguage.app.core.app.ProductionAppGraph
import com.reallanguage.app.core.app.PasswordResetDeepLink
import com.reallanguage.app.core.app.parsePasswordResetDeepLink
import com.reallanguage.app.core.app.ReminderHomeRequest
import com.reallanguage.app.core.app.parseReminderHomeRequest
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.designsystem.RlKeyboardEditingHost
import androidx.compose.runtime.mutableStateOf
import com.reallanguage.app.core.session.requiresNearbyDevicesPermissionForLocalApi

class MainActivity : ComponentActivity() {
    private var networkObservation: AutoCloseable? = null
    private var productionStarted = false
    private val pendingPasswordResetLink = mutableStateOf<PasswordResetDeepLink?>(null)
    private val pendingReminderHome = mutableStateOf<ReminderHomeRequest?>(null)
    private val nearbyDevicesPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { startProductionApp() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPasswordResetLink.value = parsePasswordResetDeepLink(intent?.dataString)
        pendingReminderHome.value = parseReminderHomeRequest(intent?.action, intent?.dataString, java.util.UUID.randomUUID().toString())
        enterFocusedLearningMode()
        if (requiresLocalNetworkPermission() &&
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            nearbyDevicesPermission.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            startProductionApp()
        }
    }

    private fun startProductionApp() {
        if (productionStarted || isFinishing || isDestroyed) return
        productionStarted = true
        val graph = ProductionAppGraph(this)
        networkObservation = graph.startNetworkObservation()
        setContent {
            RlTheme {
                RlKeyboardEditingHost {
                    ProductionApp(
                        graph = graph,
                        passwordResetDeepLink = pendingPasswordResetLink.value,
                        onPasswordResetDeepLinkConsumed = {
                            pendingPasswordResetLink.value = null
                        },
                        reminderHomeRequest = pendingReminderHome.value,
                        onReminderHomeConsumed = { pendingReminderHome.value = null },
                    )
                }
            }
        }
    }

    private fun requiresLocalNetworkPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            requiresNearbyDevicesPermissionForLocalApi(BuildConfig.API_BASE_URL, Build.VERSION.SDK_INT)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingPasswordResetLink.value = parsePasswordResetDeepLink(intent.dataString)
        pendingReminderHome.value = parseReminderHomeRequest(intent.action, intent.dataString, java.util.UUID.randomUUID().toString())
    }

    override fun onDestroy() {
        networkObservation?.close()
        networkObservation = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFocusedLearningMode()
    }

    private fun enterFocusedLearningMode() {
        configureFocusedLearningWindow(window)
    }
}
